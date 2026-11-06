/* util.c — NDJSON 输出、去重、/proc/net uid 缓存、/proc/<pid>/status 查询 */
#include "privd.h"

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

long long now_ms(void) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long long)tv.tv_sec * 1000LL + tv.tv_usec / 1000;
}

static pthread_mutex_t g_out_mu = PTHREAD_MUTEX_INITIALIZER;

/* 每秒事件上限：超过后丢弃数据事件并只 warn 一次，防止刷爆 stdout */
static long long g_rate_win = 0;
static int g_rate_count = 0;
static int g_rate_warned = 0;

/* stdout 写入失败（App 进程死亡 / 管道关闭 → EPIPE）时置 g_stop，主循环随即清理退出，
 * 避免 privd 变成孤儿 root 进程。 */
static void out_fail_check(int fail) {
    if (!fail && !ferror(stdout)) return;
    clearerr(stdout);
    g_stop = 1;
}

void emit_json(const char *json) {
    long long now = now_ms();
    long long win = now / 1000;
    if (g_stop) return;
    pthread_mutex_lock(&g_out_mu);
    if (g_stop) {
        pthread_mutex_unlock(&g_out_mu);
        return;
    }
    if (win != g_rate_win) {
        g_rate_win = win;
        g_rate_count = 0;
        g_rate_warned = 0;
    }
    g_rate_count++;
    if (g_rate_count > 1000 && !strstr(json, "\"t\":\"status\"")) {
        if (!g_rate_warned) {
            g_rate_warned = 1;
            int fail = fprintf(stdout,
                               "{\"v\":1,\"t\":\"warn\",\"ts\":%lld,"
                               "\"msg\":\"event rate >1000/s, sampling\"}\n",
                               now_ms()) < 0;
            if (fflush(stdout) == EOF) fail = 1;
            out_fail_check(fail);
        }
        pthread_mutex_unlock(&g_out_mu);
        return;
    }
    int fail = 0;
    if (fputs(json, stdout) == EOF) fail = 1;
    else if (fputc('\n', stdout) == EOF) fail = 1;
    else if (fflush(stdout) == EOF) fail = 1;
    out_fail_check(fail);
    pthread_mutex_unlock(&g_out_mu);
}

void emit_status(const char *feature, int ok, const char *message) {
    char esc[1024], buf[1400];
    json_escape(message, esc, sizeof(esc));
    snprintf(buf, sizeof(buf), "{\"v\":1,\"t\":\"status\",\"feature\":\"%s\","
             "\"ok\":%s,\"msg\":\"%s\",\"ts\":%lld}",
             feature, ok ? "true" : "false", esc, now_ms());
    emit_json(buf);
}

void logmsg(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fputs(PRIVD_NAME ": ", stderr);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    va_end(ap);
    fflush(stderr);
}

static void emit_simple(const char *t, const char *msg) {
    char esc[PRIVD_BUFSZ / 2];
    char buf[PRIVD_BUFSZ];
    json_escape(msg ? msg : "", esc, sizeof(esc));
    snprintf(buf, sizeof(buf), "{\"v\":1,\"t\":\"%s\",\"ts\":%lld,\"msg\":\"%s\"}", t, now_ms(), esc);
    emit_json(buf);
}

void emit_warn(const char *fmt, ...) {
    char msg[PRIVD_BUFSZ / 2];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    logmsg("warn: %s", msg);
    emit_simple("warn", msg);
}

void emit_error(const char *fmt, ...) {
    char msg[PRIVD_BUFSZ / 2];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    logmsg("error: %s", msg);
    emit_simple("error", msg);
}

/* UTF-8 序列校验：返回合法序列字节数；非法返回 0。
 * 拒绝 overlong、surrogate、>U+10FFFF 以及截断序列。 */
static int utf8_seq_len(const unsigned char *p, int avail) {
    unsigned char c = p[0];
    if (c < 0x80) return 1;
    if (c < 0xC2) return 0; /* continuation byte / overlong 2-byte */
    if (c < 0xE0) {
        if (avail < 2 || (p[1] & 0xC0) != 0x80) return 0;
        return 2;
    }
    if (c < 0xF0) {
        if (avail < 3 || (p[1] & 0xC0) != 0x80 || (p[2] & 0xC0) != 0x80) return 0;
        if (c == 0xE0 && p[1] < 0xA0) return 0; /* overlong */
        if (c == 0xED && p[1] >= 0xA0) return 0; /* surrogate */
        return 3;
    }
    if (c < 0xF5) {
        if (avail < 4 || (p[1] & 0xC0) != 0x80 || (p[2] & 0xC0) != 0x80 ||
            (p[3] & 0xC0) != 0x80)
            return 0;
        if (c == 0xF0 && p[1] < 0x90) return 0; /* overlong */
        if (c == 0xF4 && p[1] >= 0x90) return 0; /* > U+10FFFF */
        return 4;
    }
    return 0;
}

/* JSON 字符串转义（不含外层引号）。输出永远是合法 UTF-8：
 * 非法字节（0x80-0xFF 中不符合 UTF-8 的、控制字符之外的）替换为 U+FFFD。
 * P0-1：DNS/SNI/comm 等来源的原始字节可能不是 UTF-8，直接透传会让 NDJSON 非法。 */
char *json_escape(const char *in, char *out, size_t outsz) {
    size_t o = 0;
    if (outsz == 0) return out;
    if (!in) in = "";
    const unsigned char *p = (const unsigned char *)in;
    while (*p) {
        if (o + 12 >= outsz) break;
        switch (*p) {
            case '"': out[o++] = '\\'; out[o++] = '"'; p++; continue;
            case '\\': out[o++] = '\\'; out[o++] = '\\'; p++; continue;
            case '\n': out[o++] = '\\'; out[o++] = 'n'; p++; continue;
            case '\r': out[o++] = '\\'; out[o++] = 'r'; p++; continue;
            case '\t': out[o++] = '\\'; out[o++] = 't'; p++; continue;
            default: break;
        }
        if (*p < 0x20) {
            int n = snprintf(out + o, outsz - o, "\\u%04x", *p);
            if (n > 0) o += (size_t)n;
            p++;
            continue;
        }
        if (*p < 0x80) {
            out[o++] = (char)*p++;
            continue;
        }
        int n = utf8_seq_len(p, 64); /* 输入以 NUL 结尾，最多再看 4 字节 */
        if (n == 0) {
            /* 非法字节：替换为 U+FFFD，继续扫描下一个字节 */
            out[o++] = '\\';
            out[o++] = 'u';
            out[o++] = 'F';
            out[o++] = 'F';
            out[o++] = 'F';
            out[o++] = 'D';
            p++;
            continue;
        }
        for (int i = 0; i < n; i++) out[o++] = (char)p[i];
        p += n;
    }
    out[o] = '\0';
    return out;
}

/* ---------------- 去重 ---------------- */

#define DEDUPE_SLOTS 1024
typedef struct {
    uint64_t hash;
    long long ts;
} dslot;
static dslot g_dedupe[DEDUPE_SLOTS];
static pthread_mutex_t g_dedupe_mu = PTHREAD_MUTEX_INITIALIZER;

static uint64_t fnv1a(const char *s, uint64_t h) {
    while (s && *s) {
        h ^= (unsigned char)*s++;
        h *= 1099511628211ULL;
    }
    return h;
}

int dedupe_allow(const char *t, const char *name, const char *dst, int pid) {
    uint64_t h = 1469598103934665603ULL;
    h = fnv1a(t, h);
    h = fnv1a(name, h);
    h = fnv1a(dst, h);
    h ^= (uint64_t)(uint32_t)pid * 2654435761ULL;
    long long now = now_ms();
    size_t idx = (size_t)(h % DEDUPE_SLOTS);
    int allow = 1;
    pthread_mutex_lock(&g_dedupe_mu);
    if (g_dedupe[idx].hash == h && now - g_dedupe[idx].ts < 1000) {
        allow = 0;
    } else {
        g_dedupe[idx].hash = h;
        g_dedupe[idx].ts = now;
    }
    pthread_mutex_unlock(&g_dedupe_mu);
    return allow;
}

/* ---------------- 文件 / proc ---------------- */

int read_file_str(const char *path, char *out, size_t outsz) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, out, outsz - 1);
    close(fd);
    if (n < 0) return -1;
    out[n] = '\0';
    return (int)n;
}

int read_uid_for_pid(int pid) {
    if (pid <= 0) return -1;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    char buf[4096];
    if (read_file_str(path, buf, sizeof(buf)) < 0) return -1;
    char *p = buf;
    while (p && *p) {
        char *nl = strchr(p, '\n');
        if (nl) *nl = '\0';
        if (!strncmp(p, "Uid:", 4)) {
            int uid = -1;
            if (sscanf(p + 4, "%d", &uid) == 1) return uid;
            return -1;
        }
        if (!nl) break;
        p = nl + 1;
    }
    return -1;
}

/* ---------------- uid 缓存（port → uid） ---------------- */

#define UIDCACHE_PORTS 65536
static int *g_uid_by_port = NULL;
static pthread_mutex_t g_uid_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_uid_thread;
static volatile int g_uid_run = 0;

static void uidcache_parse_file(const char *path, int *tbl) {
    FILE *f = fopen(path, "re");
    if (!f) return;
    char line[1024];
    /* 跳过表头 */
    if (!fgets(line, sizeof(line), f)) {
        fclose(f);
        return;
    }
    while (fgets(line, sizeof(line), f)) {
        char *save = NULL;
        char *tok = strtok_r(line, " \t\n", &save);
        int idx = 0;
        const char *local = NULL;
        int uid = -1;
        char localbuf[64] = {0};
        while (tok) {
            if (idx == 1) {
                snprintf(localbuf, sizeof(localbuf), "%s", tok);
                local = localbuf;
            } else if (idx == 7) {
                uid = atoi(tok);
                break;
            }
            tok = strtok_r(NULL, " \t\n", &save);
            idx++;
        }
        if (!local || uid < 0) continue;
        const char *colon = strrchr(local, ':');
        if (!colon || strlen(colon + 1) < 4) continue;
        unsigned port = 0;
        if (sscanf(colon + 1, "%x", &port) != 1) continue;
        if (port < UIDCACHE_PORTS) tbl[port] = uid;
    }
    fclose(f);
}

static long long g_last_refresh_ms = 0;

static void uidcache_refresh(void) {
    int *tbl = malloc(sizeof(int) * UIDCACHE_PORTS);
    if (!tbl) return;
    for (int i = 0; i < UIDCACHE_PORTS; i++) tbl[i] = -1;
    /* UDP 放在最前：DNS/QUIC 的短命 socket 可能在 tcp 大表解析期间就关闭了 */
    uidcache_parse_file("/proc/net/udp", tbl);
    uidcache_parse_file("/proc/net/udp6", tbl);
    uidcache_parse_file("/proc/net/tcp", tbl);
    uidcache_parse_file("/proc/net/tcp6", tbl);
    pthread_mutex_lock(&g_uid_mu);
    int *old = g_uid_by_port;
    g_uid_by_port = tbl;
    g_last_refresh_ms = now_ms();
    pthread_mutex_unlock(&g_uid_mu);
    free(old);
}

static void *uid_thread_fn(void *arg) {
    (void)arg;
    while (!g_stop && g_uid_run) {
        uidcache_refresh();
        /* 200ms 粒度睡眠，便于及时退出 */
        for (int i = 0; i < 5 && !g_stop && g_uid_run; i++) usleep(200 * 1000);
    }
    return NULL;
}

int uidcache_start(void) {
    if (g_uid_run) return 0;
    g_uid_run = 1;
    uidcache_refresh();
    if (pthread_create(&g_uid_thread, NULL, uid_thread_fn, NULL) != 0) {
        g_uid_run = 0;
        return -1;
    }
    return 0;
}

void uidcache_stop(void) {
    if (!g_uid_run) return;
    g_uid_run = 0;
    pthread_join(g_uid_thread, NULL);
    pthread_mutex_lock(&g_uid_mu);
    free(g_uid_by_port);
    g_uid_by_port = NULL;
    pthread_mutex_unlock(&g_uid_mu);
}

int uidcache_by_port(int port) {
    if (port < 0 || port >= UIDCACHE_PORTS || !g_uid_by_port) return -1;
    pthread_mutex_lock(&g_uid_mu);
    int uid = g_uid_by_port ? g_uid_by_port[port] : -1;
    pthread_mutex_unlock(&g_uid_mu);
    return uid;
}

/* 查询失败时（短连接 socket 可能晚于 1s 缓存出现）按需刷新，最多 300ms 一次 */
int uidcache_lookup_fresh(int port) {
    int uid = uidcache_by_port(port);
    if (uid >= 0) return uid;
    static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
    long long now = now_ms();
    int do_refresh = 0;
    pthread_mutex_lock(&mu);
    if (now - g_last_refresh_ms > 300) {
        g_last_refresh_ms = now;
        do_refresh = 1;
    }
    pthread_mutex_unlock(&mu);
    if (do_refresh) {
        uidcache_refresh();
        uid = uidcache_by_port(port);
    }
    if (getenv("PRIVD_DEBUG_UID")) logmsg("uid lookup port=%d -> %d", port, uid);
    return uid;
}
