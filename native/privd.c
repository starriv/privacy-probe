/* privd.c — root 隐私采集器主程序（NDJSON over stdout） */
#include "privd.h"

#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#include <linux/if_ether.h>
#include <linux/if_packet.h>

volatile sig_atomic_t g_stop = 0;
int g_want_pcap = 0;
int g_want_uprobe = 0;
int g_want_kprobe = 0;
int g_want_exec = 0;
int g_want_uid = 0;
int g_dns_port = 53;

char g_instance_name[64] = "privprobe";
int g_instance_explicit = 0;
char g_group_prefix[16] = "";
int g_owner_pid = 0;

static const char *g_iface_filter = NULL;
static pid_t g_parent_pid = 0;

static void usage(FILE *f) {
    fprintf(f,
            "privd v%d — privacy-probe root collector\n"
            "usage: privd [--dry-run] [--pcap] [--uprobe] [--kprobe-open] [--exec]\n"
            "             [--uid-lookup] [--iface <name>] [--dns-port <n>]\n"
            "             [--owner-pid <pid>] [--instance <name>] [--isolate]\n",
            PRIVD_VERSION);
}

static void on_signal(int sig) {
    (void)sig;
    g_stop = 1;
}

/* 兜底：无论从哪条路径 exit，都确保 tracefs 事件/实例被清理并 flush stdout。
 * SIGTERM 路径走正常清理；这里覆盖 started==0 提前返回、异常退出等情况。 */
static volatile sig_atomic_t g_atexit_done = 0;
static void cleanup_atexit(void) {
    if (g_atexit_done) return;
    g_atexit_done = 1;
    netcap_stop();
    tracefs_stop();
    uidcache_stop();
    fflush(stdout);
    fflush(stderr);
}

static long long proc_start_ts(void) {
    return now_ms();
}

/* 构造 JSON 字符串数组片段。
 * P1-1：snprintf 被截断时返回 would-be 长度，直接累加会让 *len 超过 outsz，
 * 下一次 outsz - *len 发生 size_t 下溢（ASan 复现的栈溢出）。这里显式夹取。 */
static void append_json_str(char *out, size_t outsz, size_t *len, const char *s, int *first) {
    char esc[256];
    if (*len >= outsz) return;
    json_escape(s, esc, sizeof(esc));
    size_t rem = outsz - *len;
    int n = snprintf(out + *len, rem, "%s\"%s\"", *first ? "" : ",", esc);
    if (n < 0) return;
    if ((size_t)n >= rem) {
        out[outsz - 1] = '\0';
        *len = outsz - 1;
        *first = 0;
        return;
    }
    *len += (size_t)n;
    *first = 0;
}

static int pcap_capable(void) {
    if (netcap_enum(g_iface_filter) <= 0) return 0;
    int fd = socket(AF_PACKET, SOCK_RAW | SOCK_CLOEXEC, htons(ETH_P_ALL));
    if (fd < 0) return 0;
    int ok = 0;
    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof(sll));
    sll.sll_family = AF_PACKET;
    sll.sll_protocol = htons(ETH_P_ALL);
    sll.sll_ifindex = (int)if_nametoindex(g_iface_list[0]);
    if (sll.sll_ifindex > 0 && bind(fd, (struct sockaddr *)&sll, sizeof(sll)) == 0) ok = 1;
    close(fd);
    return ok;
}

static int uid_lookup_capable(void) {
    char buf[64];
    return read_file_str("/proc/net/tcp", buf, sizeof(buf)) > 0;
}

int main(int argc, char **argv) {
    int dry_run = 0;

    /* P0-3：父进程（su）死亡时内核发 SIGTERM；立即检查父进程是否已经变成 init。 */
    pid_t parent = getppid();
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);
    if (getppid() != parent || getppid() == 1) {
        fprintf(stderr, PRIVD_NAME ": parent already gone, exit\n");
        return 1;
    }
    g_parent_pid = parent;

    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (!strcmp(a, "--dry-run")) dry_run = 1;
        else if (!strcmp(a, "--pcap")) g_want_pcap = 1;
        else if (!strcmp(a, "--uprobe")) g_want_uprobe = 1;
        else if (!strcmp(a, "--kprobe-open")) g_want_kprobe = 1;
        else if (!strcmp(a, "--exec")) g_want_exec = 1;
        else if (!strcmp(a, "--uid-lookup")) g_want_uid = 1;
        else if (!strcmp(a, "--owner-pid") && i + 1 < argc) g_owner_pid = atoi(argv[++i]);
        else if (!strcmp(a, "--instance") && i + 1 < argc) {
            snprintf(g_instance_name, sizeof(g_instance_name), "%s", argv[++i]);
            g_instance_explicit = 1;
        }
        else if (!strcmp(a, "--isolate")) {
            /* 诊断用：换实例名/事件组前缀，避免与 App 正在跑的 privd 互相干扰 */
            snprintf(g_instance_name, sizeof(g_instance_name), "privprobe-test");
            snprintf(g_group_prefix, sizeof(g_group_prefix), "TEST");
        } else if (!strcmp(a, "--iface") && i + 1 < argc) g_iface_filter = argv[++i];
        else if (!strcmp(a, "--dns-port") && i + 1 < argc) g_dns_port = atoi(argv[++i]);
        else if (!strcmp(a, "-h") || !strcmp(a, "--help")) {
            usage(stdout);
            return 0;
        } else if (!strcmp(a, "--version")) {
            printf("%d\n", PRIVD_VERSION);
            return 0;
        } else {
            emit_warn("unknown argument: %s", a);
        }
    }
    if (!g_want_pcap && !g_want_uprobe && !g_want_kprobe && !g_want_exec) {
        /* 默认探针集合；dry-run 额外检查 kprobe/exec，方便诊断页一次看全能力 */
        g_want_pcap = 1;
        g_want_uprobe = 1;
        g_want_uid = 1;
        if (dry_run) {
            g_want_kprobe = 1;
            g_want_exec = 1;
        }
    }
    if (g_dns_port <= 0 || g_dns_port > 65535) g_dns_port = 53;

    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    atexit(cleanup_atexit);

    int ifaces = netcap_enum(g_iface_filter);
    int libc_ok = tracefs_locate_libc();

    /* hello：首行，绝对第一 */
    char feats[256] = {0};
    size_t fl = 0;
    int first = 1;
    if (g_want_pcap) append_json_str(feats, sizeof(feats), &fl, "pcap", &first);
    if (g_want_uprobe) append_json_str(feats, sizeof(feats), &fl, "uprobe", &first);
    if (g_want_kprobe) append_json_str(feats, sizeof(feats), &fl, "kprobe-open", &first);
    if (g_want_exec) append_json_str(feats, sizeof(feats), &fl, "exec", &first);
    if (g_want_uid) append_json_str(feats, sizeof(feats), &fl, "uid-lookup", &first);

    char ifs[512] = {0};
    size_t il = 0;
    first = 1;
    for (int i = 0; i < ifaces && i < 8; i++)
        append_json_str(ifs, sizeof(ifs), &il, g_iface_list[i], &first);

    char libc_esc[512] = {0};
    if (libc_ok == 0) json_escape(g_uprobe_libc, libc_esc, sizeof(libc_esc));

    if (dry_run) {
        int caps = tracefs_capabilities();
        int pcap_ok = pcap_capable();
        int uid_ok = uid_lookup_capable();
        char buf[PRIVD_BUFSZ];
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"hello\",\"ts\":%lld,\"pid\":%d,\"version\":\"1.0.0\","
                 "\"features\":[%s],\"ifaces\":[%s],\"uprobe_libc\":\"%s\",\"dry_run\":true,"
                 "\"caps\":{\"tracefs\":%s,\"instance\":%s,\"uprobe\":%s,\"kprobe\":%s,"
                 "\"exec\":%s,\"pcap\":%s,\"uid_lookup\":%s}}",
                 proc_start_ts(), getpid(), feats, ifs, libc_esc,
                 (caps & CAP_TRACEFS) ? "true" : "false",
                 (caps & CAP_INSTANCE) ? "true" : "false", (caps & CAP_UPROBE) ? "true" : "false",
                 (caps & CAP_KPROBE) ? "true" : "false", (caps & CAP_EXEC) ? "true" : "false",
                 pcap_ok ? "true" : "false", uid_ok ? "true" : "false");
        emit_json(buf);

        if (!(caps & CAP_TRACEFS)) emit_warn("dry-run: tracefs unavailable");
        if (g_want_uprobe && !(caps & CAP_UPROBE)) emit_warn("dry-run: uprobe unavailable");
        if (g_want_uprobe && !(caps & CAP_INSTANCE))
            emit_warn("dry-run: tracefs instance unavailable (global fallback)");
        if (g_want_kprobe && !(caps & CAP_KPROBE)) emit_warn("dry-run: kprobe unavailable");
        if (g_want_exec && !(caps & CAP_EXEC)) emit_warn("dry-run: exec tracepoint unavailable");
        if (g_want_pcap && !pcap_ok) emit_warn("dry-run: AF_PACKET unavailable (root?)");
        if (g_want_pcap && ifaces <= 0) emit_warn("dry-run: no capturable network interface");
        if (g_want_uid && !uid_ok) emit_warn("dry-run: /proc/net unreadable");
        if (libc_ok != 0) emit_warn("dry-run: bionic libc.so not found");
        logmsg("dry-run complete");
        return 0;
    }

    {
        char buf[PRIVD_BUFSZ];
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"hello\",\"ts\":%lld,\"pid\":%d,\"version\":\"1.0.0\","
                 "\"features\":[%s],\"ifaces\":[%s],\"uprobe_libc\":\"%s\",\"dns_port\":%d,"
                 "\"owner_pid\":%d}",
                 proc_start_ts(), getpid(), feats, ifs, libc_esc, g_dns_port, g_owner_pid);
        emit_json(buf);
    }

    int started = 0;
    if (g_want_uid) {
        if (uidcache_start() == 0) started++;
        else emit_warn("uid-lookup: start failed");
    }
    if (g_want_pcap) {
        if (netcap_start() == 0) started++;
        else emit_status("pcap", 0, "抓包线程启动失败");
    }
    if (g_want_uprobe || g_want_kprobe || g_want_exec) {
        if (tracefs_start() == 0) started++;
        else {
            if (g_want_uprobe) emit_status("uprobe", 0, "tracefs 启动失败");
            if (g_want_kprobe) emit_status("kprobe-open", 0, "tracefs 启动失败");
        }
    }
    if (started == 0) {
        emit_error("no collector subsystem started");
        return 1;
    }

    /* 启动它的 App 进程已经死掉（stale su 拉起）→ 直接退出 */
    if (g_owner_pid > 0 && kill((pid_t)g_owner_pid, 0) != 0 && errno == ESRCH) {
        logmsg("owner pid %d already gone, exit", g_owner_pid);
        g_stop = 1;
    }

    /* P0-3 watchdog：每秒检查父进程（su）和 owner（App）是否还活着。
     * 两者任一消失 → 清理并退出，避免成为孤儿 root 进程。 */
    while (!g_stop) {
        for (int i = 0; i < 10 && !g_stop; i++) usleep(100 * 1000);
        if (g_stop) break;
        pid_t pp = getppid();
        if (pp != g_parent_pid || pp == 1) {
            logmsg("parent exited (ppid %d -> %d), shutting down", (int)g_parent_pid, (int)pp);
            g_stop = 1;
            break;
        }
        if (g_owner_pid > 0 && kill((pid_t)g_owner_pid, 0) != 0 && errno == ESRCH) {
            logmsg("owner app %d gone, shutting down", g_owner_pid);
            g_stop = 1;
            break;
        }
    }

    logmsg("shutting down");
    netcap_stop();
    tracefs_stop();
    uidcache_stop();
    return 0;
}
