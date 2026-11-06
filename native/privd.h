/*
 * privd — root privacy collector daemon (privacy-probe)
 *
 * 编译：scripts/build-native.sh（NDK 28, aarch64-linux-android31-clang, PIE）
 * 输出：app/src/main/assets/bin/arm64-v8a/privd
 *
 * 线程模型：
 *   main      参数解析 / 能力自检 / 信号等待 / 清理
 *   uidcache  /proc/net/{tcp,udp,tcp6,udp6} → local port→uid，1s 刷新
 *   netcap    AF_PACKET 原始套接字（每接口一个 socket），解析 DNS/SNI/HTTP/SYN
 *   trace     tracefs 全局注册 uprobe/kprobe，实例内 enable + 读 trace_pipe
 *
 * stdout 只允许 NDJSON；日志走 stderr。
 */
#ifndef PRIVD_H
#define PRIVD_H

#include <limits.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>

#define PRIVD_VERSION 1
#define PRIVD_NAME "privd"
#define PRIVD_BUFSZ 3072
#define PRIVD_MAX_IFACES 16

extern volatile sig_atomic_t g_stop;
extern int g_want_pcap;
extern int g_want_uprobe;
extern int g_want_kprobe;
extern int g_want_exec;
extern int g_want_uid;
extern int g_dns_port;

/* 诊断/隔离（--isolate）：默认实例/事件组名与线上一致；测试时可避免与 App 的 privd 冲突 */
extern char g_instance_name[64];
extern int g_instance_explicit;
extern char g_group_prefix[16];
/* 启动它的 App 进程 pid（--owner-pid）；该进程消失时 privd 自行退出 */
extern int g_owner_pid;

/* ---------------- 输出 ---------------- */
long long now_ms(void);
void logmsg(const char *fmt, ...);
void emit_json(const char *json);
void emit_warn(const char *fmt, ...);
void emit_error(const char *fmt, ...);
void emit_status(const char *feature, int ok, const char *message);
char *json_escape(const char *in, char *out, size_t outsz);
/* 1s 窗口内同 (t,name,dst,pid) 去重；允许返回 1 */
int dedupe_allow(const char *t, const char *name, const char *dst, int pid);

/* ---------------- 通用 ---------------- */
int read_file_str(const char *path, char *out, size_t outsz);
int read_uid_for_pid(int pid);

/* ---------------- uid 缓存 ---------------- */
int uidcache_start(void);
void uidcache_stop(void);
int uidcache_by_port(int port); /* -1 = 未知 */
int uidcache_lookup_fresh(int port); /* 未命中时按需刷新 */

/* ---------------- AF_PACKET ---------------- */
extern char g_iface_list[PRIVD_MAX_IFACES][16];
extern int g_iface_count;
int netcap_enum(const char *iface_filter);
int netcap_start(void);
void netcap_stop(void);

/* ---------------- tracefs ---------------- */
int tracefs_start(void);
void tracefs_stop(void);
/* dry-run 自检：返回位掩码，未启动任何线程 */
#define CAP_TRACEFS 0x01
#define CAP_INSTANCE 0x02
#define CAP_UPROBE 0x04
#define CAP_KPROBE 0x08
#define CAP_EXEC 0x10
int tracefs_capabilities(void);
int tracefs_locate_libc(void);
extern char g_trace_base[PATH_MAX];
extern int g_trace_using_instance;
extern char g_uprobe_libc[PATH_MAX];

#endif /* PRIVD_H */
