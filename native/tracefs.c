/* tracefs.c — uprobe(getaddrinfo) / kprobe(v4l2_open,snd_pcm_open) / exec tracepoint
 *
 * 设备事实（2026-11-06 实测，Android 17 / 6.x kernel）：
 *   - 动态事件定义文件 uprobe_events / kprobe_events 只存在于全局
 *     /sys/kernel/tracing/ 下，instances/<name>/ 里没有这两个文件；
 *   - 但可以在全局注册事件后，在 instances/privprobe/events/.../enable 单独 enable，
 *     并读 instances/privprobe/trace_pipe，从而与小米 bcc 等全局消费者隔离。
 *   - 因此：注册走全局，enable/trace_pipe 走实例；实例不可用时退回全局并 warn。
 */
#include "privd.h"

#include <ctype.h>
#include <dirent.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

char g_trace_base[PATH_MAX];
int g_trace_using_instance = 0;
char g_uprobe_libc[PATH_MAX];

static char g_trace_root[PATH_MAX];
static int g_created_instance = 0;

static int g_up_added[2] = {0, 0}; /* getaddrinfo, android_getaddrinfofornet */
static int g_kp_added[2] = {0, 0}; /* v4l2_open, snd_pcm_open */
static int g_exec_enabled = 0;

static pthread_t g_reader_thread;
static volatile int g_reader_run = 0;
static int g_pipe_fd = -1;
static int g_reader_started = 0;

static const char *UPROBE_EVENTS[2] = {"getaddrinfo", "android_getaddrinfofornet"};
static const char *KPROBE_EVENTS[2] = {"v4l2_open", "snd_pcm_open"};

static int dynamic_path(char *out, size_t outsz, const char *file);
static int del_dynamic(const char *file, const char *group_event);

/* 事件组名：默认 PRIVGAI / PRIVOPEN；--isolate 时加 TEST 前缀避免与 App 的 privd 冲突 */
static char g_gai_grp[32] = "PRIVGAI";
static char g_kp_grp[32] = "PRIVOPEN";

/* 读取 /proc/<pid>/stat 的 starttime（field 22），用于区分 pid 复用。 */
static unsigned long long proc_starttime(int pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    char buf[2048];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = '\0';
    /* comm 在括号里，可能包含空格/括号，从最后一个 ')' 之后再数字段 */
    char *p = strrchr(buf, ')');
    if (!p) return 0;
    p++;
    int field = 3; /* state 是第 3 个字段 */
    while (*p && field <= 22) {
        while (*p == ' ') p++;
        char *tok = p;
        while (*p && *p != ' ') p++;
        if (field == 22) return strtoull(tok, NULL, 10);
        field++;
    }
    return 0;
}

/* P1-4：事件组名 = <PREFIX>PRIVGAI_<pid>_<starttime>，实例名 = privprobe_<pid>_<starttime>。
 * 第二个 privd 不会删掉第一个的全局事件；pid 复用也能用 starttime 区分。 */
static void init_groups(void) {
    unsigned long long st = proc_starttime((int)getpid());
    snprintf(g_gai_grp, sizeof(g_gai_grp), "%sPRIVGAI_%d_%llu", g_group_prefix, (int)getpid(), st);
    snprintf(g_kp_grp, sizeof(g_kp_grp), "%sPRIVOPEN_%d_%llu", g_group_prefix, (int)getpid(), st);
}

static int pid_alive(int pid) {
    if (pid <= 0) return 0;
    if (kill((pid_t)pid, 0) == 0) return 1;
    return errno == EPERM;
}

/* 解析组名/实例名里的 owner：返回 0 表示格式匹配（pid+starttime 都合法），-1 不匹配。 */
static int parse_owner(const char *name, int *pid_out, unsigned long long *start_out) {
    const char *u2 = strrchr(name, '_');
    if (!u2 || !u2[1]) return -1;
    const char *u1 = NULL;
    for (const char *p = name; p < u2; p++)
        if (*p == '_') u1 = p;
    if (!u1 || u1 + 1 >= u2) return -1;
    for (const char *x = u1 + 1; x < u2; x++)
        if (!isdigit((unsigned char)*x)) return -1;
    for (const char *x = u2 + 1; *x; x++)
        if (!isdigit((unsigned char)*x)) return -1;
    int pid = atoi(u1 + 1);
    unsigned long long st = strtoull(u2 + 1, NULL, 10);
    if (pid <= 0 || st == 0) return -1;
    *pid_out = pid;
    *start_out = st;
    return 0;
}

/* owner（pid + starttime）是否仍然存活；pid 复用视为已死。 */
static int owner_alive(int pid, unsigned long long start) {
    if (!pid_alive(pid)) return 0;
    unsigned long long cur = proc_starttime(pid);
    if (cur == 0) return 0;
    return cur == start;
}

/* 兼容旧格式（只有 _<pid>）：返回 pid，无 starttime。 */
static int group_owner_pid_compat(const char *name) {
    const char *us = strrchr(name, '_');
    if (!us || !us[1]) return -1;
    for (const char *x = us + 1; *x; x++)
        if (!isdigit((unsigned char)*x)) return -1;
    return atoi(us + 1);
}

/* 判断 group 是否是 privd 自己的动态事件组（基名 PRIVGAI/PRIVOPEN，允许 TEST 前缀），
 * 返回 1：新格式带 _<pid>_<starttime>；返回 2：旧格式只带 _<pid>；0 不是自己的组。 */
static int is_privd_group(const char *grp, char *base_out, size_t basesz) {
    size_t glen = strlen(grp);
    const char *slash = strchr(grp, '/');
    if (slash) glen = (size_t)(slash - grp);
    if (glen == 0 || glen >= 120) return 0;
    char base[128];
    memcpy(base, grp, glen);
    base[glen] = '\0';
    const char *found = strstr(base, "PRIVGAI");
    if (!found) found = strstr(base, "PRIVOPEN");
    if (!found) return 0;
    if (base_out) snprintf(base_out, basesz, "%s", found);
    int pid = 0;
    unsigned long long st = 0;
    if (parse_owner(found, &pid, &st) == 0) return 1;
    if (group_owner_pid_compat(found) > 0) return 2;
    return 0;
}

/* 清理同一 App 目录下残留的旧 privd 进程（老版本没有 PDEATHSIG，可能变孤儿）。
 * 只认 exe 指向「本进程所在目录/privd*」的进程，不会碰无关进程。 */
static void kill_orphan_privd(void) {
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n <= 0) return;
    self[n] = '\0';
    char *slash = strrchr(self, '/');
    if (!slash) return;
    *slash = '\0';
    char prefix[PATH_MAX];
    snprintf(prefix, sizeof(prefix), "%s/privd", self);
    DIR *d = opendir("/proc");
    if (!d) return;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] < '1' || de->d_name[0] > '9') continue;
        int pid = atoi(de->d_name);
        if (pid <= 0 || pid == (int)getpid()) continue;
        char link[64], target[PATH_MAX];
        snprintf(link, sizeof(link), "/proc/%d/exe", pid);
        ssize_t m = readlink(link, target, sizeof(target) - 1);
        if (m <= 0) continue;
        target[m] = '\0';
        if (strncmp(target, prefix, strlen(prefix)) != 0) continue;
        logmsg("cleanup orphan privd pid %d (%s)", pid, target);
        kill((pid_t)pid, SIGTERM);
    }
    closedir(d);
}

/* P1-3：SIGKILL/force-stop 后全局事件定义/实例可能残留。启动时清理「owner 已死」的自己的
 * 组与实例。顺序：先 rmdir 实例（解除实例内 enable，否则全局事件删不掉），再删事件定义。
 * 同时兼容旧版本固定组名 PRIVGAI/PRIVOPEN 与实例 privprobe/privradar。 */
static void cleanup_stale_artifacts(void) {
    kill_orphan_privd();

    char instdir[PATH_MAX];
    dynamic_path(instdir, sizeof(instdir), "instances");
    DIR *d = opendir(instdir);
    if (d) {
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            const char *nm = de->d_name;
            if (strncmp(nm, "privprobe", 9) != 0 && strncmp(nm, "privradar", 9) != 0) continue;
            int stale = 0;
            if (!strcmp(nm, "privprobe") || !strcmp(nm, "privradar")) {
                stale = 1; /* 旧版本固定实例名 */
            } else {
                int pid = 0;
                unsigned long long st = 0;
                if (parse_owner(nm, &pid, &st) == 0) {
                    stale = !owner_alive(pid, st);
                } else {
                    int p2 = group_owner_pid_compat(nm);
                    stale = (p2 > 0) && !pid_alive(p2);
                }
            }
            if (!stale) continue;
            char full[PATH_MAX];
            snprintf(full, sizeof(full), "%s/%s", instdir, nm);
            if (rmdir(full) == 0) logmsg("cleanup stale instance %s", nm);
        }
        closedir(d);
    }

    const char *files[2] = {"uprobe_events", "kprobe_events"};
    for (int f = 0; f < 2; f++) {
        char path[PATH_MAX];
        dynamic_path(path, sizeof(path), files[f]);
        /* tracefs 伪文件不支持 fseek/ftell 得到大小，必须用 read 循环读 */
        size_t sz = 0;
        char *buf = NULL;
        {
            int fd = open(path, O_RDONLY | O_CLOEXEC);
            if (fd < 0) continue;
            size_t cap = 16384;
            buf = malloc(cap);
            if (!buf) {
                close(fd);
                continue;
            }
            for (;;) {
                if (sz + 4096 + 1 > cap) {
                    if (cap >= 512 * 1024) break;
                    char *nb = realloc(buf, cap * 2);
                    if (!nb) break;
                    buf = nb;
                    cap *= 2;
                }
                ssize_t n = read(fd, buf + sz, cap - sz - 1);
                if (n < 0) {
                    if (errno == EINTR) continue;
                    break;
                }
                if (n == 0) break;
                sz += (size_t)n;
            }
            close(fd);
            buf[sz] = '\0';
        }
        if (!buf || sz == 0) {
            free(buf);
            continue;
        }
        char *save = NULL;
        for (char *line = strtok_r(buf, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
            char *ge = NULL;
            if (!strncmp(line, "p:", 2) || !strncmp(line, "r:", 2)) ge = line + 2;
            else continue;
            char *sp = strchr(ge, ' ');
            if (sp) *sp = '\0';
            size_t gl = strlen(ge);
            if (!gl || gl >= 120) continue;
            if (!strchr(ge, '/')) continue;
            char grp[128];
            const char *slash = strchr(ge, '/');
            snprintf(grp, sizeof(grp), "%.*s", slash ? (int)(slash - ge) : (int)strlen(ge), ge);
            char base[128] = {0};
            int kind = is_privd_group(grp, base, sizeof(base));
            int stale = 0;
            if (kind == 1) {
                int pid = 0;
                unsigned long long st = 0;
                if (parse_owner(base, &pid, &st) == 0) stale = !owner_alive(pid, st);
            } else if (kind == 2) {
                int p2 = group_owner_pid_compat(base);
                stale = (p2 > 0) && !pid_alive(p2);
            } else if (!strcmp(grp, "PRIVGAI") || !strcmp(grp, "PRIVOPEN")) {
                stale = 1; /* 旧版本固定组名 */
            }
            if (!stale) continue;
            del_dynamic(files[f], ge);
            logmsg("cleanup stale %s event %s", files[f], ge);
        }
        free(buf);
    }
}

/* ---------------- 文件 helper ---------------- */

static int write_file(const char *path, const char *s, int append) {
    int flags = O_WRONLY | O_CLOEXEC | (append ? O_APPEND : O_TRUNC);
    int fd = open(path, flags);
    if (fd < 0) return -1;
    size_t len = strlen(s);
    ssize_t w = write(fd, s, len);
    int err = errno;
    close(fd);
    if (w < 0) {
        errno = err;
        return -1;
    }
    if ((size_t)w != len) {
        errno = EIO;
        return -1;
    }
    return 0;
}

static int find_trace_root(void) {
    const char *cands[2] = {"/sys/kernel/tracing", "/sys/kernel/debug/tracing"};
    struct stat st;
    for (int i = 0; i < 2; i++) {
        if (stat(cands[i], &st) == 0 && S_ISDIR(st.st_mode) && access(cands[i], W_OK) == 0) {
            snprintf(g_trace_root, sizeof(g_trace_root), "%s", cands[i]);
            return 0;
        }
    }
    return -1;
}

static void setup_base(void) {
    char inst_dir[PATH_MAX];
    snprintf(inst_dir, sizeof(inst_dir), "%s/instances", g_trace_root);
    char inst[PATH_MAX];
    snprintf(inst, sizeof(inst), "%s/%s", inst_dir, g_instance_name);
    int ok = 0;
    if (mkdir(inst, 0777) == 0) {
        g_created_instance = 1;
        ok = 1;
    } else if (errno == EEXIST) {
        ok = 1; /* 复用上次残留实例，退出时会 rmdir */
    }
    if (ok) {
        char pipe[PATH_MAX];
        snprintf(pipe, sizeof(pipe), "%s/trace_pipe", inst);
        if (access(pipe, F_OK) == 0) {
            snprintf(g_trace_base, sizeof(g_trace_base), "%s", inst);
            g_trace_using_instance = 1;
            return;
        }
        rmdir(inst);
    }
    snprintf(g_trace_base, sizeof(g_trace_base), "%s", g_trace_root);
    g_trace_using_instance = 0;
    emit_warn("tracefs: instance unavailable, fallback to global tracing");
}

/* 注册/启用失败时从实例退回全局 */
static void fallback_to_global(void) {
    if (!g_trace_using_instance) return;
    emit_warn("tracefs: instance enable failed, fallback to global");
    snprintf(g_trace_base, sizeof(g_trace_base), "%s", g_trace_root);
    g_trace_using_instance = 0;
}

static int dynamic_path(char *out, size_t outsz, const char *file) {
    return snprintf(out, outsz, "%s/%s", g_trace_root, file) >= 0 ? 0 : -1;
}

static int enable_path(char *out, size_t outsz, const char *group_event) {
    return snprintf(out, outsz, "%s/events/%s/enable", g_trace_base, group_event) >= 0 ? 0 : -1;
}

static int add_dynamic(const char *file, const char *def) {
    char path[PATH_MAX];
    dynamic_path(path, sizeof(path), file);
    return write_file(path, def, 1);
}

static int del_dynamic(const char *file, const char *group_event) {
    char def[256];
    snprintf(def, sizeof(def), "-:%s\n", group_event);
    return add_dynamic(file, def);
}

/* 事件 enable 状态跟踪。
 * 全局 fallback 时 tracefs 的 enable 文件没有引用计数：若别的工具（bcc 等）原本已 enable，
 * 我们退出时写 0 会把它关掉。这里记录 enable 前状态，退出时恢复；实例模式下只动自己的实例。 */
#define EV_MAX 8
typedef struct {
    char ge[128];
    char path[PATH_MAX];
    int kind; /* 0=none, 1=instance, 2=global */
    int prev; /* global: enable 前的值；-1 未知 */
} ev_state;
static ev_state g_ev[EV_MAX];
static int g_ev_n = 0;

static ev_state *ev_slot(const char *ge) {
    for (int i = 0; i < g_ev_n; i++)
        if (!strcmp(g_ev[i].ge, ge)) return &g_ev[i];
    if (g_ev_n >= EV_MAX) return NULL;
    ev_state *st = &g_ev[g_ev_n++];
    memset(st, 0, sizeof(*st));
    snprintf(st->ge, sizeof(st->ge), "%s", ge);
    st->prev = -1;
    return st;
}

static int read_enable_state(const char *path) {
    char b[8];
    if (read_file_str(path, b, sizeof(b)) <= 0) return -1;
    return b[0] == '1';
}

static int enable_event(const char *group_event) {
    ev_state *st = ev_slot(group_event);
    if (!st) return -1;
    char path[PATH_MAX];
    enable_path(path, sizeof(path), group_event);
    if (g_trace_using_instance) {
        if (write_file(path, "1\n", 0) == 0) {
            snprintf(st->path, sizeof(st->path), "%s", path);
            st->kind = 1;
            st->prev = -1;
            return 0;
        }
        fallback_to_global();
        enable_path(path, sizeof(path), group_event);
    }
    int prev = read_enable_state(path);
    if (write_file(path, "1\n", 0) != 0) return -1;
    snprintf(st->path, sizeof(st->path), "%s", path);
    st->kind = 2;
    st->prev = prev;
    return 0;
}

static void disable_event(const char *group_event) {
    ev_state *st = ev_slot(group_event);
    if (!st || st->kind == 0 || !st->path[0]) return;
    if (st->kind == 2 && st->prev >= 0) {
        write_file(st->path, st->prev ? "1\n" : "0\n", 0); /* 恢复 enable 前状态 */
    } else {
        write_file(st->path, "0\n", 0);
    }
    st->kind = 0;
    st->prev = -1;
    st->path[0] = '\0';
}

/* ---------------- ELF64 .dynsym 解析 ---------------- */

static int elf_symbol_value(const char *path, const char *sym, uint64_t *out) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(Elf64_Ehdr)) {
        close(fd);
        return -1;
    }
    size_t fsz = (size_t)st.st_size;
    void *map = mmap(NULL, fsz, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (map == MAP_FAILED) return -1;
    int rc = -1;
    const unsigned char *m = (const unsigned char *)map;
    /* P1-11：畸形 libc 不能让 privd SIGBUS/SEGV —— 对 ELF/section/字符串表做严格范围校验 */
    if (memcmp(m, ELFMAG, SELFMAG) == 0 && m[EI_CLASS] == ELFCLASS64 && m[EI_DATA] == ELFDATA2LSB) {
        const Elf64_Ehdr *eh = (const Elf64_Ehdr *)m;
        uint64_t shoff = eh->e_shoff;
        uint64_t shnum = eh->e_shnum;
        if (shoff > 0 && shnum > 0 && eh->e_shentsize == sizeof(Elf64_Shdr) &&
            shoff <= fsz && shnum * sizeof(Elf64_Shdr) <= (uint64_t)fsz - shoff &&
            eh->e_shstrndx < shnum) {
            const Elf64_Shdr *sh = (const Elf64_Shdr *)(m + shoff);
            const Elf64_Shdr *shstr = &sh[eh->e_shstrndx];
            if (shstr->sh_offset <= fsz && shstr->sh_size <= fsz - shstr->sh_offset) {
                const char *names = (const char *)(m + shstr->sh_offset);
                size_t namesz = (size_t)shstr->sh_size;
                const Elf64_Shdr *symtab = NULL;
                const Elf64_Shdr *strtab = NULL;
                for (uint64_t i = 0; i < shnum; i++) {
                    if (sh[i].sh_name >= namesz) continue;
                    const char *nm = names + sh[i].sh_name;
                    if (!memchr(nm, 0, namesz - sh[i].sh_name)) continue;
                    if (!strcmp(nm, ".dynsym")) symtab = &sh[i];
                    else if (!strcmp(nm, ".dynstr")) strtab = &sh[i];
                }
                if (symtab && strtab && symtab->sh_entsize == sizeof(Elf64_Sym) &&
                    symtab->sh_offset <= fsz && symtab->sh_size <= fsz - symtab->sh_offset &&
                    strtab->sh_offset <= fsz && strtab->sh_size <= fsz - strtab->sh_offset &&
                    strtab->sh_size > 0) {
                    const Elf64_Sym *syms = (const Elf64_Sym *)(m + symtab->sh_offset);
                    size_t count = (size_t)(symtab->sh_size / sizeof(Elf64_Sym));
                    const char *strs = (const char *)(m + strtab->sh_offset);
                    size_t strsz = (size_t)strtab->sh_size;
                    for (size_t i = 0; i < count && rc != 0; i++) {
                        if (syms[i].st_name >= strsz) continue;
                        const char *name = strs + syms[i].st_name;
                        if (!memchr(name, 0, strsz - syms[i].st_name)) continue;
                        int stype = ELF64_ST_TYPE(syms[i].st_info);
                        /* 只接受函数符号（STT_FUNC / GNU_IFUNC）且已定义：避免挂到任意指令中间 */
                        if (syms[i].st_value && syms[i].st_shndx != SHN_UNDEF &&
                            (stype == STT_FUNC || stype == STT_GNU_IFUNC) && !strcmp(name, sym)) {
                            *out = syms[i].st_value;
                            rc = 0;
                        }
                    }
                }
            }
        }
    }
    munmap(map, fsz);
    return rc;
}

int find_uprobe_libc(void) {
    const char *cands[3] = {"/apex/com.android.runtime/lib64/bionic/libc.so",
                            "/system/lib64/libc.so",
                            "/apex/com.android.runtime/lib64/bionic/libc.so"};
    for (int i = 0; i < 3; i++) {
        if (access(cands[i], R_OK) == 0) {
            snprintf(g_uprobe_libc, sizeof(g_uprobe_libc), "%s", cands[i]);
            return 0;
        }
    }
    return -1;
}

/* 返回成功注册的事件数。P1-15：绝不使用硬编码偏移 fallback —— 解析失败就禁用该 uprobe
 * （挂到错误地址会打错指令），并输出 warn 让状态/UI 可见 unhealthy。 */
static int register_uprobes(void) {
    int n = 0;
    for (int i = 0; i < 2; i++) {
        uint64_t off = 0;
        char ge[128];
        snprintf(ge, sizeof(ge), "%s/%s", g_gai_grp, UPROBE_EVENTS[i]);
        if (elf_symbol_value(g_uprobe_libc, UPROBE_EVENTS[i], &off) != 0) {
            emit_warn("uprobe: cannot resolve %s in %s .dynsym; event disabled (no fallback)",
                      UPROBE_EVENTS[i], g_uprobe_libc);
            continue;
        }
        char def[512];
        snprintf(def, sizeof(def), "p:%s %s:0x%llx node=+0(%%x0):string\n", ge, g_uprobe_libc,
                 (unsigned long long)off);
        int ok = add_dynamic("uprobe_events", def) == 0;
        if (!ok) {
            del_dynamic("uprobe_events", ge);
            ok = add_dynamic("uprobe_events", def) == 0;
        }
        if (!ok) {
            emit_warn("uprobe: add %s failed: %s", UPROBE_EVENTS[i], strerror(errno));
            continue;
        }
        if (enable_event(ge) != 0) {
            emit_warn("uprobe: enable %s failed: %s", UPROBE_EVENTS[i], strerror(errno));
            del_dynamic("uprobe_events", ge);
            continue;
        }
        g_up_added[i] = 1;
        n++;
    }
    if (n > 0) {
        logmsg("uprobe: %d event(s) on %s (instance=%d)", n, g_uprobe_libc, g_trace_using_instance);
    } else {
        emit_warn("uprobe: no event registered (dynsym parse failed); uprobe disabled");
    }
    return n;
}

static void unregister_uprobes(void) {
    for (int i = 0; i < 2; i++) {
        if (!g_up_added[i]) continue;
        char ge[128];
        snprintf(ge, sizeof(ge), "%s/%s", g_gai_grp, UPROBE_EVENTS[i]);
        disable_event(ge);
        del_dynamic("uprobe_events", ge);
        g_up_added[i] = 0;
    }
}

static void register_kprobes(void) {
    int n = 0;
    for (int i = 0; i < 2; i++) {
        char ge[128];
        snprintf(ge, sizeof(ge), "%s/%s", g_kp_grp, KPROBE_EVENTS[i]);
        char def[256];
        /* snd_pcm_open(name, stream, mode): x2 = stream（0=playback, 1=capture）。
         * 取参数能让 Kotlin 区分录音(capture)与播放(playback)；失败则退回无参。 */
        if (i == 1)
            snprintf(def, sizeof(def), "p:%s %s stream=%%x2\n", ge, KPROBE_EVENTS[i]);
        else
            snprintf(def, sizeof(def), "p:%s %s\n", ge, KPROBE_EVENTS[i]);
        int ok = add_dynamic("kprobe_events", def) == 0;
        if (!ok) {
            del_dynamic("kprobe_events", ge);
            ok = add_dynamic("kprobe_events", def) == 0;
        }
        if (!ok && i == 1) {
            /* 退回无参数版本（内核不支持寄存器取值时） */
            snprintf(def, sizeof(def), "p:%s %s\n", ge, KPROBE_EVENTS[i]);
            ok = add_dynamic("kprobe_events", def) == 0;
        }
        if (!ok) {
            emit_warn("kprobe: %s unavailable: %s", KPROBE_EVENTS[i], strerror(errno));
            continue;
        }
        if (enable_event(ge) != 0) {
            emit_warn("kprobe: enable %s failed: %s", KPROBE_EVENTS[i], strerror(errno));
            del_dynamic("kprobe_events", ge);
            continue;
        }
        g_kp_added[i] = 1;
        n++;
    }
    if (n > 0) logmsg("kprobe: %d event(s)", n);
}

static void unregister_kprobes(void) {
    for (int i = 0; i < 2; i++) {
        if (!g_kp_added[i]) continue;
        char ge[128];
        snprintf(ge, sizeof(ge), "%s/%s", g_kp_grp, KPROBE_EVENTS[i]);
        disable_event(ge);
        del_dynamic("kprobe_events", ge);
        g_kp_added[i] = 0;
    }
}

static void enable_exec(void) {
    if (enable_event("sched/sched_process_exec") == 0) {
        g_exec_enabled = 1;
    } else {
        emit_warn("exec: enable sched_process_exec failed: %s", strerror(errno));
    }
}

static void disable_exec(void) {
    if (!g_exec_enabled) return;
    disable_event("sched/sched_process_exec");
    g_exec_enabled = 0;
}

/* ---------------- /proc/<pid>/fd 解析真实设备路径 ---------------- */

/* 扫描 /proc/<pid>/fd，返回 fd 号最大的匹配链接（最后打开的那个）。
 * needle 为路径前缀（如 "/dev/video"、"/dev/snd/pcm"），suffix 非 0 时要求目标以该字符结尾。 */
static int fd_find_latest(int pid, const char *needle, char suffix, char *out, size_t outsz) {
    char dir[64];
    snprintf(dir, sizeof(dir), "/proc/%d/fd", pid);
    DIR *d = opendir(dir);
    if (!d) return -1;
    int best = -1;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] < '0' || de->d_name[0] > '9') continue;
        char link[PATH_MAX];
        snprintf(link, sizeof(link), "%s/%s", dir, de->d_name);
        char target[PATH_MAX];
        ssize_t n = readlink(link, target, sizeof(target) - 1);
        if (n <= 0) continue;
        target[n] = '\0';
        if (!strstr(target, needle)) continue;
        if (suffix && target[n - 1] != suffix) continue;
        int fd = atoi(de->d_name);
        if (fd > best) {
            best = fd;
            snprintf(out, outsz, "%s", target);
            char *del = strstr(out, " (deleted)");
            if (del) *del = '\0';
        }
    }
    closedir(d);
    return best >= 0 ? 0 : -1;
}

/* v4l2_open 的 kprobe 事件里没有设备号；在事件处理时扫描 /proc/<pid>/fd 拿真实 /dev/videoN。
 * 失败时不伪造 video0，输出 "/dev/video"（Kotlin 侧仍可归为 CAMERA，只是无具体号）。 */
static void resolve_video_path(int pid, char *out, size_t outsz) {
    for (int i = 0; i < 3; i++) {
        if (fd_find_latest(pid, "/dev/video", 0, out, outsz) == 0) return;
        usleep(2000);
    }
    snprintf(out, outsz, "/dev/video");
}

/* 音频 capture/playback 的真实设备号同样从 fd 解析；拿不到时退回 C0D0 + 方向。 */
static void resolve_snd_path(int pid, int capture, char *out, size_t outsz) {
    char suffix = capture ? 'c' : 'p';
    for (int i = 0; i < 3; i++) {
        if (fd_find_latest(pid, "/dev/snd/pcm", suffix, out, outsz) == 0) return;
        usleep(2000);
    }
    snprintf(out, outsz, "/dev/snd/pcmC0D0%c", suffix);
}

/* ---------------- trace 行解析 ---------------- */

/* getaddrinfo 也会被用于解析 IP 字面量，这类噪声不输出 */
static int looks_like_ip_literal(const char *s) {
    if (!s || !*s) return 1;
    int dots = 0, digits = 1;
    for (const char *p = s; *p; p++) {
        if (*p == '.') dots++;
        else if (*p == ':') return 1; /* IPv6 */
        else if (!isdigit((unsigned char)*p)) digits = 0;
    }
    return digits && (dots == 3 || dots == 0);
}

static void emit_gai(const char *name, int pid, const char *comm) {
    if (!name || !*name) return;
    if (looks_like_ip_literal(name)) return;
    if (!dedupe_allow("gai", name, "", pid)) return;
    char esc[1024];
    char ec[128];
    char buf[PRIVD_BUFSZ];
    json_escape(name, esc, sizeof(esc));
    json_escape(comm ? comm : "", ec, sizeof(ec));
    int uid = read_uid_for_pid(pid);
    if (uid >= 0) {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"gai\",\"ts\":%lld,\"name\":\"%s\",\"pid\":%d,\"uid\":%d,"
                 "\"comm\":\"%s\"}",
                 now_ms(), esc, pid, uid, ec);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"gai\",\"ts\":%lld,\"name\":\"%s\",\"pid\":%d,\"comm\":\"%s\"}",
                 now_ms(), esc, pid, ec);
    }
    emit_json(buf);
}

static void emit_open(const char *path, int pid, const char *comm) {
    if (!dedupe_allow("open", path, "", pid)) return;
    char ec[128];
    char buf[PRIVD_BUFSZ];
    json_escape(comm ? comm : "", ec, sizeof(ec));
    int uid = read_uid_for_pid(pid);
    if (uid >= 0) {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"open\",\"ts\":%lld,\"path\":\"%s\",\"pid\":%d,\"uid\":%d,"
                 "\"comm\":\"%s\"}",
                 now_ms(), path, pid, uid, ec);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"open\",\"ts\":%lld,\"path\":\"%s\",\"pid\":%d,\"comm\":\"%s\"}",
                 now_ms(), path, pid, ec);
    }
    emit_json(buf);
}

static void emit_exec(const char *path, int pid, const char *comm) {
    int uid = read_uid_for_pid(pid);
    /* P1-11：exec 只保留应用进程（uid>=10000）；root/shell/系统服务（uid<10000）
     * 的进程创建（su/curl/dumpsys/cameraserver 等）都是噪声，直接丢弃。 */
    if (uid < 10000) return;
    /* 额外的 comm 黑名单：App 进程内执行的 shell 工具 */
    static const char *noise[] = {"dumpsys", "pm", "sh", "getprop", "cmd", "app_process",
                                  "toybox", "logcat", "su", "ps", "top", "kill", "am",
                                  "settings", "service", "dpm", "wm", "sm", "content",
                                  "screencap", "input", "monkey", "id", "env", "dd",
                                  "cat", "unzip", "grep", "sed", "awk", "head", "tail",
                                  "sort", "tr", "strings"};
    for (size_t i = 0; i < sizeof(noise) / sizeof(noise[0]); i++) {
        if (comm && !strcmp(comm, noise[i])) return;
    }
    if (!dedupe_allow("exec", comm ? comm : "", "", pid)) return;
    char ec[128];
    char ep[512];
    char buf[PRIVD_BUFSZ];
    json_escape(comm ? comm : "", ec, sizeof(ec));
    json_escape(path ? path : "", ep, sizeof(ep));
    if (uid >= 0) {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"exec\",\"ts\":%lld,\"pid\":%d,\"uid\":%d,\"comm\":\"%s\","
                 "\"path\":\"%s\"}",
                 now_ms(), pid, uid, ec, ep);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"exec\",\"ts\":%lld,\"pid\":%d,\"comm\":\"%s\",\"path\":\"%s\"}",
                 now_ms(), pid, ec, ep);
    }
    emit_json(buf);
}

/* 从 trace 参数里抽 node="..." */
static int extract_quoted(const char *args, const char *key, char *out, size_t outsz) {
    const char *p = strstr(args, key);
    if (!p) return -1;
    p = strchr(p, '"');
    if (!p) return -1;
    p++;
    size_t o = 0;
    while (*p && *p != '"' && o + 1 < outsz) out[o++] = *p++;
    out[o] = '\0';
    return o > 0 ? 0 : -1;
}

static int extract_field(const char *args, const char *key, char *out, size_t outsz) {
    const char *p = strstr(args, key);
    if (!p) return -1;
    p += strlen(key);
    size_t o = 0;
    while (*p && *p != ' ' && *p != '\n' && o + 1 < outsz) out[o++] = *p++;
    out[o] = '\0';
    return o > 0 ? 0 : -1;
}

static char *strrstr(const char *hay, const char *needle) {
    const char *last = NULL;
    const char *p = hay;
    while ((p = strstr(p, needle)) != NULL) {
        last = p;
        p++;
    }
    return (char *)last;
}

static int is_known_event(const char *ev) {
    return !strcmp(ev, UPROBE_EVENTS[0]) || !strcmp(ev, UPROBE_EVENTS[1]) ||
           !strcmp(ev, KPROBE_EVENTS[0]) || !strcmp(ev, KPROBE_EVENTS[1]) ||
           !strcmp(ev, "sched_process_exec");
}

static void dispatch_trace_event(const char *ev, char *args, int pid, char *comm) {
    if (!strcmp(ev, UPROBE_EVENTS[0]) || !strcmp(ev, UPROBE_EVENTS[1])) {
        char name[256];
        if (extract_quoted(args, "node=", name, sizeof(name)) == 0) emit_gai(name, pid, comm);
        return;
    }
    if (!strcmp(ev, KPROBE_EVENTS[0])) {
        /* P2-8：设备号真实 —— 从 /proc/<pid>/fd 找实际打开的 /dev/videoN */
        char vpath[PATH_MAX];
        resolve_video_path(pid, vpath, sizeof(vpath));
        emit_open(vpath, pid, comm);
        return;
    }
    if (!strcmp(ev, KPROBE_EVENTS[1])) {
        char sbuf[32];
        if (extract_field(args, "stream=", sbuf, sizeof(sbuf)) == 0) {
            unsigned long stream = strtoul(sbuf, NULL, 0);
            char path[PATH_MAX];
            resolve_snd_path(pid, stream == 1, path, sizeof(path));
            emit_open(path, pid, comm);
        } else {
            /* 拿不到方向：给不带方向的通用路径，Kotlin 会忽略（宁可少报不误报） */
            emit_open("/dev/snd/pcm", pid, comm);
        }
        return;
    }
    if (!strcmp(ev, "sched_process_exec")) {
        /* P1-6：filename 可被 exec 的进程控制（可能包含 "pid="），从后往前解析 pid，
         * 并校验 pid 是纯数字且合理；filename 取到最后一个 " pid=" 之前。 */
        char *pidp = strrstr(args, " pid=");
        int epid = pid;
        if (pidp) {
            const char *v = pidp + 5;
            long val = 0;
            int ok = (*v != '\0');
            for (const char *x = v; *x && *x != ' '; x++) {
                if (!isdigit((unsigned char)*x)) {
                    ok = 0;
                    break;
                }
                val = val * 10 + (*x - '0');
                if (val > 4194304) {
                    ok = 0;
                    break;
                }
            }
            if (ok && val > 0) epid = (int)val;
        }
        char path[512] = {0};
        char *fp = strstr(args, "filename=");
        if (fp) {
            const char *v = fp + 9;
            const char *endp = pidp ? pidp : v + strlen(v);
            if (endp < v) endp = v + strlen(v);
            size_t n = (size_t)(endp - v);
            if (n >= sizeof(path)) n = sizeof(path) - 1;
            memcpy(path, v, n);
            path[n] = '\0';
        }
        emit_exec(path, epid, comm);
    }
}

/* P1-5：comm 由 App 控制，可能包含 " [" 让首个 header 误判、事件静默丢失。
 * 逐个候选 " [" 严格校验（cpu 数字、时间戳含 '.'、pid 前有 '-'），
 * 且只接受已知事件名，避免 comm 注入。 */
static void handle_trace_line(char *line) {
    char *scan = line;
    while ((scan = strstr(scan, " [")) != NULL) {
        char *br = scan;
        char *rb = strchr(br + 2, ']');
        if (!rb) return;
        int ok = (rb > br + 2);
        for (char *x = br + 2; ok && x < rb; x++)
            if (!isdigit((unsigned char)*x)) ok = 0;
        char *tscolon = ok ? strstr(rb, ": ") : NULL;
        int hasdot = 0;
        if (tscolon)
            for (char *x = rb + 1; x < tscolon; x++)
                if (*x == '.') hasdot = 1;
        if (!ok || !tscolon || !hasdot) {
            scan += 2;
            continue;
        }
        char *digits_end = br;
        while (digits_end > line && digits_end[-1] == ' ') digits_end--;
        char *digits_beg = digits_end;
        while (digits_beg > line && isdigit((unsigned char)digits_beg[-1])) digits_beg--;
        if (digits_beg == digits_end || digits_beg == line || digits_beg[-1] != '-') {
            scan += 2;
            continue;
        }
        int pid = atoi(digits_beg);
        if (pid <= 0 || pid > 4194304) {
            scan += 2;
            continue;
        }
        char comm[32] = {0};
        size_t cs = 0;
        size_t ce = (size_t)(digits_beg - 1 - line);
        while (cs < ce && line[cs] == ' ') cs++;
        if (ce - cs >= sizeof(comm)) ce = cs + sizeof(comm) - 1;
        memcpy(comm, line + cs, ce - cs);
        comm[ce - cs] = '\0';
        char *evp = tscolon + 2;
        char *eve = strstr(evp, ": ");
        if (!eve) return;
        size_t evlen = (size_t)(eve - evp);
        if (evlen == 0 || evlen >= 64) {
            scan += 2;
            continue;
        }
        char ev[64];
        memcpy(ev, evp, evlen);
        ev[evlen] = '\0';
        if (!is_known_event(ev)) {
            scan += 2;
            continue;
        }
        dispatch_trace_event(ev, eve + 2, pid, comm);
        return;
    }
}

static void *reader_fn(void *arg) {
    (void)arg;
    char *linebuf = malloc(65536);
    if (!linebuf) {
        if (g_want_uprobe) emit_status("uprobe", 0, "tracefs 缓冲区分配失败");
        if (g_want_kprobe) emit_status("kprobe-open", 0, "tracefs 缓冲区分配失败");
        return NULL;
    }
    if (g_want_uprobe) emit_status("uprobe", g_up_added[0] || g_up_added[1],
        g_up_added[0] || g_up_added[1] ? "DNS 调用链已就绪" : "DNS 调用链注册失败");
    if (g_want_kprobe) emit_status("kprobe-open", g_kp_added[0] && g_kp_added[1],
        g_kp_added[0] && g_kp_added[1] ? "相机和音频内核探针已就绪" : "内核探针覆盖不完整");
    size_t linelen = 0;
    char chunk[16384];
    while (g_reader_run && !g_stop) {
        struct pollfd pfd;
        pfd.fd = g_pipe_fd;
        pfd.events = POLLIN;
        pfd.revents = 0;
        int pr = poll(&pfd, 1, 200);
        if (pr <= 0) continue;
        ssize_t n = read(g_pipe_fd, chunk, sizeof(chunk));
        if (n < 0) {
            if (errno == EAGAIN || errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        for (ssize_t i = 0; i < n; i++) {
            if (chunk[i] == '\n') {
                linebuf[linelen] = '\0';
                if (linelen > 0) handle_trace_line(linebuf);
                linelen = 0;
            } else if (linelen + 1 < 65536) {
                linebuf[linelen++] = chunk[i];
            } else {
                linelen = 0;
            }
        }
    }
    free(linebuf);
    if (g_reader_run && !g_stop) {
        if (g_want_uprobe) emit_status("uprobe", 0, "tracefs 读取中断");
        if (g_want_kprobe) emit_status("kprobe-open", 0, "tracefs 读取中断");
    }
    return NULL;
}

/* ---------------- 对外 ---------------- */

int tracefs_locate_libc(void) { return find_uprobe_libc(); }

int tracefs_start(void) {
    if (find_trace_root() != 0) {
        emit_warn("tracefs: no writable tracing directory");
        return -1;
    }
    init_groups();
    if (!g_instance_explicit) {
        size_t l = strlen(g_instance_name);
        unsigned long long st = proc_starttime((int)getpid());
        if (l + 32 < sizeof(g_instance_name))
            snprintf(g_instance_name + l, sizeof(g_instance_name) - l, "_%d_%llu", (int)getpid(), st);
    }
    cleanup_stale_artifacts();
    setup_base();
    if (g_want_uprobe) {
        if (find_uprobe_libc() != 0)
            emit_warn("uprobe: libc not found; uprobe disabled");
        else
            (void)register_uprobes();
    }
    if (g_want_kprobe) register_kprobes();
    if (g_want_exec) enable_exec();

    char pipe[PATH_MAX];
    snprintf(pipe, sizeof(pipe), "%s/trace_pipe", g_trace_base);
    g_pipe_fd = open(pipe, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (g_pipe_fd < 0) {
        emit_warn("tracefs: open %s failed: %s", pipe, strerror(errno));
        unregister_uprobes();
        unregister_kprobes();
        disable_exec();
        return -1;
    }
    g_reader_run = 1;
    if (pthread_create(&g_reader_thread, NULL, reader_fn, NULL) != 0) {
        g_reader_run = 0;
        close(g_pipe_fd);
        g_pipe_fd = -1;
        emit_warn("tracefs: reader thread failed");
        unregister_uprobes();
        unregister_kprobes();
        disable_exec();
        return -1;
    }
    g_reader_started = 1;
    logmsg("tracefs: base=%s instance=%d", g_trace_base, g_trace_using_instance);
    return 0;
}

void tracefs_stop(void) {
    if (g_reader_started) {
        g_reader_run = 0;
        pthread_join(g_reader_thread, NULL);
        g_reader_started = 0;
    }
    if (g_pipe_fd >= 0) {
        close(g_pipe_fd);
        g_pipe_fd = -1;
    }
    unregister_uprobes();
    unregister_kprobes();
    disable_exec();
    if (g_trace_using_instance && g_trace_base[0]) {
        rmdir(g_trace_base);
        g_trace_using_instance = 0;
    }
    g_created_instance = 0;
}

/* dry-run 能力自检：注册临时事件后立刻删除，不启动线程 */
int tracefs_capabilities(void) {
    int caps = 0;
    if (find_trace_root() != 0) return 0;
    caps |= CAP_TRACEFS;

    char inst[PATH_MAX];
    snprintf(inst, sizeof(inst), "%s/instances/privprobe-chk", g_trace_root);
    if (mkdir(inst, 0777) == 0 || errno == EEXIST) {
        char pipe[PATH_MAX];
        snprintf(pipe, sizeof(pipe), "%s/trace_pipe", inst);
        if (access(pipe, F_OK) == 0) caps |= CAP_INSTANCE;
        rmdir(inst);
    }

    /* P1-15：dynsym 解析失败即视为 uprobe 不可用，不使用任何硬编码偏移 */
    if (find_uprobe_libc() == 0) {
        uint64_t off = 0;
        if (elf_symbol_value(g_uprobe_libc, "getaddrinfo", &off) == 0) {
            char def[512];
            snprintf(def, sizeof(def),
                     "p:PRIVCHK/getaddrinfo %s:0x%llx node=+0(%%x0):string\n", g_uprobe_libc,
                     (unsigned long long)off);
            if (add_dynamic("uprobe_events", def) == 0) caps |= CAP_UPROBE;
            del_dynamic("uprobe_events", "PRIVCHK/getaddrinfo");
        }
    }
    {
        char def[256];
        snprintf(def, sizeof(def), "p:PRIVCHK/v4l2_open v4l2_open\n");
        if (add_dynamic("kprobe_events", def) == 0) {
            caps |= CAP_KPROBE;
            del_dynamic("kprobe_events", "PRIVCHK/v4l2_open");
        } else {
            del_dynamic("kprobe_events", "PRIVCHK/v4l2_open");
        }
    }
    {
        char path[PATH_MAX];
        snprintf(path, sizeof(path), "%s/events/sched/sched_process_exec/enable", g_trace_root);
        if (access(path, W_OK) == 0) caps |= CAP_EXEC;
    }
    return caps;
}
