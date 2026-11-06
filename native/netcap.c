/* netcap.c — AF_PACKET 抓包：DNS / TLS SNI / HTTP Host / TCP SYN */
#include "privd.h"

#include <arpa/inet.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <netinet/in.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>
#include <time.h>

#include <linux/if_ether.h>
#include <linux/if_packet.h>

#define CAP_BUF 65536
#define MAX_SOCKS PRIVD_MAX_IFACES

char g_iface_list[PRIVD_MAX_IFACES][16];
int g_iface_count = 0;

typedef struct {
    int fd;
    char iface[16];
    unsigned int ifindex;
} cap_sock;

static cap_sock g_socks[MAX_SOCKS];
static int g_nsocks = 0;
static pthread_t g_thread;
static volatile int g_cap_run = 0;
static char g_iface_filter[32];

/* ---------------- 接口枚举 ---------------- */

int netcap_enum(const char *iface_filter) {
    g_iface_count = 0;
    g_iface_filter[0] = '\0';
    if (iface_filter) snprintf(g_iface_filter, sizeof(g_iface_filter), "%s", iface_filter);
    struct if_nameindex *ifs = if_nameindex();
    if (!ifs) return -1;
    for (struct if_nameindex *it = ifs; it->if_index != 0; it++) {
        const char *name = it->if_name;
        if (!name || !strcmp(name, "lo")) continue;
        if (g_iface_filter[0] && strcmp(name, g_iface_filter)) continue;
        int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd < 0) continue;
        struct ifreq ifr;
        memset(&ifr, 0, sizeof(ifr));
        snprintf(ifr.ifr_name, sizeof(ifr.ifr_name), "%s", name);
        int up = 0;
        if (ioctl(fd, SIOCGIFFLAGS, &ifr) == 0) up = (ifr.ifr_flags & IFF_UP) != 0;
        close(fd);
        if (!up) continue;
        if (g_iface_count >= PRIVD_MAX_IFACES) break;
        snprintf(g_iface_list[g_iface_count], sizeof(g_iface_list[0]), "%s", name);
        g_iface_count++;
    }
    if_freenameindex(ifs);
    return g_iface_count;
}

/* ---------------- 格式化 helper ---------------- */

static void fmt_ep(int af, const uint8_t *addr, int port, char *out, size_t outsz) {
    char ip[INET6_ADDRSTRLEN] = {0};
    if (af == AF_INET) {
        inet_ntop(AF_INET, addr, ip, sizeof(ip));
        snprintf(out, outsz, "%s:%d", ip, port);
    } else {
        inet_ntop(AF_INET6, addr, ip, sizeof(ip));
        snprintf(out, outsz, "[%s]:%d", ip, port);
    }
}

static const char *qtype_name(int qtype) {
    switch (qtype) {
        case 1: return "A";
        case 2: return "NS";
        case 5: return "CNAME";
        case 6: return "SOA";
        case 12: return "PTR";
        case 15: return "MX";
        case 16: return "TXT";
        case 28: return "AAAA";
        case 33: return "SRV";
        case 64: return "SVCB";
        case 65: return "HTTPS";
        default: return "OTHER";
    }
}

static int tls_port(int port) {
    switch (port) {
        case 443:
        case 8443:
        case 853:
        case 993:
        case 995:
        case 465:
        case 9443:
            return 1;
        default:
            return 0;
    }
}

/* ---------------- 事件输出 ---------------- */

static void emit_dns_q(const char *iface, const char *proto, const char *src, const char *dst,
                       const char *name, int qtype) {
    if (!name || !*name) return;
    char esc[1024];
    char if_esc[64], p_esc[16];
    char buf[PRIVD_BUFSZ];
    json_escape(name, esc, sizeof(esc));
    json_escape(iface, if_esc, sizeof(if_esc));
    json_escape(proto, p_esc, sizeof(p_esc));
    int sport = 0;
    const char *colon = strrchr(src, ':');
    if (colon) sport = atoi(colon + 1);
    int uid = uidcache_lookup_fresh(sport);
    // Different applications/UDP sockets asking the same resolver must not erase each other.
    char flow[160];
    snprintf(flow, sizeof(flow), "%s>%s", src, dst);
    if (!dedupe_allow("dns_q", name, flow, uid)) return;
    if (uid >= 0) {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"dns_q\",\"ts\":%lld,\"iface\":\"%s\",\"proto\":\"%s\","
                 "\"src\":\"%s\",\"dst\":\"%s\",\"name\":\"%s\",\"qtype\":\"%s\",\"uid\":%d}",
                 now_ms(), if_esc, p_esc, src, dst, esc, qtype_name(qtype), uid);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"dns_q\",\"ts\":%lld,\"iface\":\"%s\",\"proto\":\"%s\","
                 "\"src\":\"%s\",\"dst\":\"%s\",\"name\":\"%s\",\"qtype\":\"%s\"}",
                 now_ms(), if_esc, p_esc, src, dst, esc, qtype_name(qtype));
    }
    emit_json(buf);
}

static void emit_dns_a(const char *name, const char *addr, int ttl) {
    if (!name || !*name || !addr || !*addr) return;
    if (!dedupe_allow("dns_a", name, addr, 0)) return;
    char e1[512], e2[128];
    char buf[PRIVD_BUFSZ];
    json_escape(name, e1, sizeof(e1));
    json_escape(addr, e2, sizeof(e2));
    snprintf(buf, sizeof(buf),
             "{\"v\":1,\"t\":\"dns_a\",\"ts\":%lld,\"name\":\"%s\",\"addr\":\"%s\",\"ttl\":%d}",
             now_ms(), e1, e2, ttl);
    emit_json(buf);
}

static void emit_sni(const char *iface, const char *dst, const char *name, int sport) {
    if (!name || !*name) return;
    if (!dedupe_allow("sni", name, dst, 0)) return;
    char esc[1024], if_esc[64];
    char buf[PRIVD_BUFSZ];
    json_escape(name, esc, sizeof(esc));
    json_escape(iface, if_esc, sizeof(if_esc));
    int uid = uidcache_lookup_fresh(sport);
    if (uid >= 0) {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"sni\",\"ts\":%lld,\"iface\":\"%s\",\"dst\":\"%s\","
                 "\"name\":\"%s\",\"uid\":%d}",
                 now_ms(), if_esc, dst, esc, uid);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"sni\",\"ts\":%lld,\"iface\":\"%s\",\"dst\":\"%s\",\"name\":\"%s\"}",
                 now_ms(), if_esc, dst, esc);
    }
    emit_json(buf);
}

static void emit_http(const char *iface, const char *dst, const char *host, int sport) {
    if (!host || !*host) return;
    if (!dedupe_allow("http", host, dst, 0)) return;
    char esc[1024], if_esc[64];
    char buf[PRIVD_BUFSZ];
    json_escape(host, esc, sizeof(esc));
    json_escape(iface, if_esc, sizeof(if_esc));
    int uid = uidcache_lookup_fresh(sport);
    if (uid >= 0) {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"http\",\"ts\":%lld,\"iface\":\"%s\",\"dst\":\"%s\","
                 "\"host\":\"%s\",\"uid\":%d}",
                 now_ms(), if_esc, dst, esc, uid);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"http\",\"ts\":%lld,\"iface\":\"%s\",\"dst\":\"%s\",\"host\":\"%s\"}",
                 now_ms(), if_esc, dst, esc);
    }
    emit_json(buf);
}

static void emit_conn(const char *iface, const char *dst, const char *proto, int sport) {
    if (!dedupe_allow("conn", "", dst, 0)) return;
    char if_esc[64], p_esc[16];
    char buf[PRIVD_BUFSZ];
    json_escape(iface, if_esc, sizeof(if_esc));
    json_escape(proto, p_esc, sizeof(p_esc));
    int uid = uidcache_lookup_fresh(sport);
    if (uid >= 0) {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"conn\",\"ts\":%lld,\"iface\":\"%s\",\"dst\":\"%s\","
                 "\"proto\":\"%s\",\"uid\":%d}",
                 now_ms(), if_esc, dst, p_esc, uid);
    } else {
        snprintf(buf, sizeof(buf),
                 "{\"v\":1,\"t\":\"conn\",\"ts\":%lld,\"iface\":\"%s\",\"dst\":\"%s\",\"proto\":\"%s\"}",
                 now_ms(), if_esc, dst, p_esc);
    }
    emit_json(buf);
}

/* ---------------- DNS 解析 ---------------- */

static int dns_name(const uint8_t *d, int len, int *pos, char *out, size_t outsz) {
    int p = *pos;
    size_t o = 0;
    int jumps = 0;
    int first_end = -1;
    out[0] = '\0';
    while (p >= 0 && p < len && jumps < 8) {
        uint8_t l = d[p];
        if (l == 0) {
            p++;
            if (first_end < 0) first_end = p;
            break;
        }
        if ((l & 0xC0) == 0xC0) {
            if (p + 1 >= len) return -1;
            int off = ((l & 0x3f) << 8) | d[p + 1];
            if (first_end < 0) first_end = p + 2;
            if (off >= len || off >= p) return -1; /* 只允许向前压缩，防环 */
            p = off;
            jumps++;
            continue;
        }
        if (l > 63 || p + 1 + l > len) return -1;
        if (o + l + 2 >= outsz) return -1;
        if (o) out[o++] = '.';
        for (int i = 0; i < l; i++) out[o++] = (char)tolower(d[p + 1 + i]);
        p += 1 + l;
    }
    out[o] = '\0';
    if (first_end < 0) first_end = p;
    *pos = first_end;
    return o > 0 ? 0 : -1;
}

typedef struct {
    const char *iface;
    int af;
    uint8_t src[16];
    uint8_t dst[16];
    int proto;
    int sport;
    int dport;
    const uint8_t *payload;
    int plen;
    int tcp_flags;
} pkt_t;

/* ---------------- DNS 应答校验（P1-7） ----------------
 * 未校验的 DNS 应答可以伪造 Kotlin 侧 ipCache（TTL 最长 24h）。这里要求应答的
 * id/qname/qtype 与最近的查询匹配（10s 窗口），并只接受 qname/CNAME 链上的地址记录。 */
#define DNS_PENDING_MAX 128
#define DNS_ALLOW_MAX 8
typedef struct {
    int used;
    uint16_t id;
    int qtype;
    long long ts;
    char name[256];
} dns_pending_t;
static dns_pending_t g_dns_pending[DNS_PENDING_MAX];

static void dns_pending_add(uint16_t id, const char *name, int qtype) {
    long long now = now_ms();
    int slot = -1;
    for (int i = 0; i < DNS_PENDING_MAX; i++) {
        if (!g_dns_pending[i].used || now - g_dns_pending[i].ts > 10000) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        slot = 0;
        for (int i = 1; i < DNS_PENDING_MAX; i++)
            if (g_dns_pending[i].ts < g_dns_pending[slot].ts) slot = i;
    }
    g_dns_pending[slot].used = 1;
    g_dns_pending[slot].id = id;
    g_dns_pending[slot].qtype = qtype;
    g_dns_pending[slot].ts = now;
    snprintf(g_dns_pending[slot].name, sizeof(g_dns_pending[slot].name), "%s", name);
}

/* 校验并消费一条 pending 查询；匹配返回 slot，否则 -1。 */
static int dns_pending_match(uint16_t id, const char *name, int qtype) {
    long long now = now_ms();
    for (int i = 0; i < DNS_PENDING_MAX; i++) {
        dns_pending_t *p = &g_dns_pending[i];
        if (!p->used) continue;
        if (now - p->ts > 10000) {
            p->used = 0;
            continue;
        }
        if (p->id == id && p->qtype == qtype && !strcasecmp(p->name, name)) {
            p->used = 0;
            return i;
        }
    }
    return -1;
}

static int name_in_allow(const char allow[DNS_ALLOW_MAX][256], int n, const char *name) {
    for (int i = 0; i < n; i++)
        if (!strcasecmp(allow[i], name)) return 1;
    return 0;
}

static void dns_handle(const pkt_t *pkt, int is_tcp) {
    const uint8_t *d = pkt->payload;
    int len = pkt->plen;
    if (is_tcp) {
        if (len < 2) return;
        int mlen = (d[0] << 8) | d[1];
        if (mlen < 12) return;
        d += 2;
        len -= 2;
        if (mlen < len) len = mlen;
    }
    if (len < 12) return;
    int flags = (d[2] << 8) | d[3];
    int qd = (d[4] << 8) | d[5];
    int an = (d[6] << 8) | d[7];
    int qr = (flags >> 15) & 1;
    int pos = 12;
    char name[256];
    if (dns_name(d, len, &pos, name, sizeof(name)) < 0) return;
    if (!name[0]) return;
    int qtype = 0;
    if (pos + 4 <= len) qtype = (d[pos] << 8) | d[pos + 1];

    char src[80], dst[80];
    fmt_ep(pkt->af, pkt->src, pkt->sport, src, sizeof(src));
    fmt_ep(pkt->af, pkt->dst, pkt->dport, dst, sizeof(dst));
    uint16_t dns_id = (uint16_t)((d[0] << 8) | d[1]);
    if (!qr) {
        if (qd > 0) {
            dns_pending_add(dns_id, name, qtype);
            emit_dns_q(pkt->iface, is_tcp ? "tcp" : "udp", src, dst, name, qtype);
        }
        return;
    }
    /* 应答：必须匹配最近 10s 内发出的同 id / qname / qtype 查询，否则丢弃（防投毒） */
    int pslot = dns_pending_match(dns_id, name, qtype);
    if (pslot < 0) return;
    char qname[256];
    snprintf(qname, sizeof(qname), "%s", g_dns_pending[pslot].name);
    static char allow[DNS_ALLOW_MAX][256];
    int nallow = 0;
    snprintf(allow[nallow++], sizeof(allow[0]), "%s", qname);
    /* 跳过 question 区的 qtype+qclass，答案从这之后开始 */
    pos = 12;
    for (int q = 0; q < qd; q++) {
        char qn[256];
        if (dns_name(d, len, &pos, qn, sizeof(qn)) < 0) return;
        if (pos + 4 > len) return;
        pos += 4;
    }
    int idx = 0;
    for (; idx < an && pos < len; idx++) {
        char aname[256];
        if (dns_name(d, len, &pos, aname, sizeof(aname)) < 0) break;
        if (pos + 10 > len) break;
        int type = (d[pos] << 8) | d[pos + 1];
        int ttl = ((int)d[pos + 4] << 24) | ((int)d[pos + 5] << 16) | ((int)d[pos + 6] << 8) |
                  (int)d[pos + 7];
        int rdlen = (d[pos + 8] << 8) | d[pos + 9];
        pos += 10;
        if (rdlen < 0 || pos + rdlen > len) break;
        int aname_ok = name_in_allow(allow, nallow, aname);
        if (type == 5 && aname_ok && nallow < DNS_ALLOW_MAX) {
            /* CNAME：把 canonical name 加入允许列表（后续 A/AAAA 记录可能用它） */
            int cp = pos;
            char cname[256];
            if (dns_name(d, len, &cp, cname, sizeof(cname)) == 0 && cname[0])
                snprintf(allow[nallow++], sizeof(allow[0]), "%s", cname);
        } else if (aname_ok && type == 1 && rdlen == 4) {
            char addr[INET_ADDRSTRLEN];
            inet_ntop(AF_INET, d + pos, addr, sizeof(addr));
            emit_dns_a(qname, addr, ttl);
        } else if (aname_ok && type == 28 && rdlen == 16) {
            char addr[INET6_ADDRSTRLEN];
            inet_ntop(AF_INET6, d + pos, addr, sizeof(addr));
            emit_dns_a(qname, addr, ttl);
        }
        pos += rdlen;
    }
}

/* ---------------- TLS SNI ---------------- */

static int tls_sni(const uint8_t *p, int len, char *out, size_t outsz) {
    if (len < 6) return -1;
    if (p[0] != 0x16 || p[1] != 0x03) return -1;
    int rec_len = (p[3] << 8) | p[4];
    if (rec_len > len - 5) rec_len = len - 5;
    const uint8_t *h = p + 5;
    int hlen = rec_len;
    if (hlen < 4 || h[0] != 0x01) return -1;
    int hs_len = (h[1] << 16) | (h[2] << 8) | h[3];
    if (hs_len > hlen - 4) hs_len = hlen - 4;
    const uint8_t *b = h + 4;
    int blen = hs_len;
    if (blen < 35) return -1;
    int pos = 34;
    int sid = b[pos];
    pos += 1 + sid;
    if (pos + 2 > blen) return -1;
    int cs = (b[pos] << 8) | b[pos + 1];
    pos += 2 + cs;
    if (pos + 1 > blen) return -1;
    int comp = b[pos];
    pos += 1 + comp;
    if (pos + 2 > blen) return -1;
    int ext_total = (b[pos] << 8) | b[pos + 1];
    pos += 2;
    int end = pos + ext_total;
    if (end > blen) end = blen;
    while (pos + 4 <= end) {
        int etype = (b[pos] << 8) | b[pos + 1];
        int elen = (b[pos + 2] << 8) | b[pos + 3];
        pos += 4;
        if (pos + elen > end) return -1;
        if (etype == 0) {
            int q = pos;
            if (q + 2 > pos + elen) return -1;
            int list_len = (b[q] << 8) | b[q + 1];
            q += 2;
            int lend = q + list_len;
            if (lend > pos + elen) lend = pos + elen;
            while (q + 3 <= lend) {
                int ntype = b[q];
                int nlen = (b[q + 1] << 8) | b[q + 2];
                q += 3;
                if (q + nlen > lend) return -1;
                if (ntype == 0 && nlen > 0 && (size_t)nlen < outsz) {
                    for (int i = 0; i < nlen; i++) out[i] = (char)tolower(b[q + i]);
                    out[nlen] = '\0';
                    for (int i = 0; i < nlen; i++) {
                        if ((unsigned char)out[i] < 0x21 || out[i] == '"' || out[i] == '\\')
                            return -1;
                    }
                    return 0;
                }
                q += nlen;
            }
            return -1;
        }
        pos += elen;
    }
    return -1;
}

/* ---------------- HTTP Host ---------------- */

static int http_host(const uint8_t *p, int len, char *out, size_t outsz) {
    if (len < 8) return -1;
    static const char *methods[] = {"GET ",  "POST ", "PUT ",   "DELETE ", "HEAD ",
                                    "CONNECT ", "OPTIONS ", "PATCH ", "TRACE "};
    int is_req = 0;
    for (size_t i = 0; i < sizeof(methods) / sizeof(methods[0]); i++) {
        size_t ml = strlen(methods[i]);
        if (len > (int)ml && !memcmp(p, methods[i], ml)) {
            is_req = 1;
            break;
        }
    }
    if (!is_req) return -1;
    int limit = len > 2048 ? 2048 : len;
    int start = -1;
    if (limit >= 5 && !strncasecmp((const char *)p, "Host:", 5)) start = 5;
    if (start < 0) {
        for (int i = 0; i + 6 < limit; i++) {
            if (p[i] == '\n' && !strncasecmp((const char *)p + i + 1, "Host:", 5)) {
                start = i + 6;
                break;
            }
        }
    }
    if (start < 0) return -1;
    int e = start;
    while (e < limit && p[e] != '\r' && p[e] != '\n') e++;
    while (start < e && (p[start] == ' ' || p[start] == '\t')) start++;
    while (e > start && (p[e - 1] == ' ' || p[e - 1] == '\t')) e--;
    if (e <= start || (size_t)(e - start) >= outsz) return -1;
    int hlen = e - start;
    for (int i = 0; i < hlen; i++) out[i] = (char)tolower(p[start + i]);
    out[hlen] = '\0';
    /* 去掉端口（保留 IPv6 字面量） */
    char *colon = strrchr(out, ':');
    if (colon && !strchr(out, ']')) *colon = '\0';
    for (int i = 0; out[i]; i++) {
        if ((unsigned char)out[i] < 0x21 || out[i] == '"' || out[i] == '\\') return -1;
    }
    return out[0] ? 0 : -1;
}

/* ---------------- IP / L4 分发 ---------------- */

static void handle_tcp(const pkt_t *pkt) {
    if ((pkt->tcp_flags & 0x02) && !(pkt->tcp_flags & 0x10)) {
        char dst[80];
        fmt_ep(pkt->af, pkt->dst, pkt->dport, dst, sizeof(dst));
        if (pkt->dport != g_dns_port) emit_conn(pkt->iface, dst, "tcp", pkt->sport);
    }
    if (!pkt->payload || pkt->plen <= 0) return;
    if (pkt->dport == g_dns_port || pkt->sport == g_dns_port) {
        dns_handle(pkt, 1);
        return;
    }
    if (pkt->dport == 80) {
        char host[256];
        if (http_host(pkt->payload, pkt->plen, host, sizeof(host)) == 0) {
            char dst[80];
            fmt_ep(pkt->af, pkt->dst, pkt->dport, dst, sizeof(dst));
            emit_http(pkt->iface, dst, host, pkt->sport);
        }
        return;
    }
    if (tls_port(pkt->dport)) {
        char name[256];
        if (tls_sni(pkt->payload, pkt->plen, name, sizeof(name)) == 0) {
            char dst[80];
            fmt_ep(pkt->af, pkt->dst, pkt->dport, dst, sizeof(dst));
            emit_sni(pkt->iface, dst, name, pkt->sport);
        }
        return;
    }
}

static void handle_udp(const pkt_t *pkt) {
    if (!pkt->payload || pkt->plen <= 0) return;
    if (pkt->dport == g_dns_port || pkt->sport == g_dns_port) dns_handle(pkt, 0);
}

static void handle_ip(const uint8_t *buf, int len, const char *iface) {
    if (len < 1) return;
    int ver = buf[0] >> 4;
    pkt_t pkt;
    memset(&pkt, 0, sizeof(pkt));
    pkt.iface = iface;
    const uint8_t *l4;
    int l4len;
    if (ver == 4) {
        if (len < 20) return;
        int ihl = (buf[0] & 0x0f) * 4;
        if (ihl < 20 || len < ihl) return;
        int total = (buf[2] << 8) | buf[3];
        if (total > len) total = len;
        if (total < ihl) return;
        int frag = (buf[6] << 8) | buf[7];
        if (frag & 0x1fff) return; /* 非首片，跳过 */
        pkt.af = AF_INET;
        pkt.proto = buf[9];
        memcpy(pkt.src, buf + 12, 4);
        memcpy(pkt.dst, buf + 16, 4);
        l4 = buf + ihl;
        l4len = total - ihl;
    } else if (ver == 6) {
        if (len < 40) return;
        int next = buf[6];
        int off = 40;
        while (next == 0 || next == 43 || next == 60) {
            if (off + 2 > len) return;
            int hl = (buf[off + 1] + 1) * 8;
            if (hl <= 0 || off + hl > len) return;
            next = buf[off];
            off += hl;
        }
        if (next == 44) return; /* fragment */
        int plen = (buf[4] << 8) | buf[5];
        int end = 40 + plen;
        if (end > len) end = len;
        pkt.af = AF_INET6;
        pkt.proto = next;
        memcpy(pkt.src, buf + 8, 16);
        memcpy(pkt.dst, buf + 24, 16);
        l4 = buf + off;
        l4len = end - off;
    } else {
        return;
    }
    if (pkt.proto == 6) {
        if (l4len < 20) return;
        int doff = (l4[12] >> 4) * 4;
        if (doff < 20 || doff > l4len) return;
        pkt.sport = (l4[0] << 8) | l4[1];
        pkt.dport = (l4[2] << 8) | l4[3];
        pkt.tcp_flags = l4[13];
        pkt.payload = l4 + doff;
        pkt.plen = l4len - doff;
        handle_tcp(&pkt);
    } else if (pkt.proto == 17) {
        if (l4len < 8) return;
        int ulen = (l4[4] << 8) | l4[5];
        if (ulen < 8) return;
        if (ulen > l4len) ulen = l4len;
        pkt.sport = (l4[0] << 8) | l4[1];
        pkt.dport = (l4[2] << 8) | l4[3];
        pkt.payload = l4 + 8;
        pkt.plen = ulen - 8;
        handle_udp(&pkt);
    }
}

static void handle_frame(const uint8_t *buf, int len, const char *iface) {
    if (len < 14) return;
    int off = 14;
    uint16_t et = (uint16_t)((buf[12] << 8) | buf[13]);
    while (et == 0x8100 || et == 0x88a8 || et == 0x9100) {
        if (len < off + 4) return;
        et = (uint16_t)((buf[off + 2] << 8) | buf[off + 3]);
        off += 4;
    }
    if (et == 0x0800 || et == 0x86dd) handle_ip(buf + off, len - off, iface);
}

/* Reconcile in the capture thread so descriptors never race with select/recv.
 * Polling every two seconds also retries transient socket/bind failures. */
static void refresh_interfaces(void) {
    char filter[sizeof(g_iface_filter)];
    snprintf(filter, sizeof(filter), "%s", g_iface_filter);
    netcap_enum(filter[0] ? filter : NULL);
    for (int i = 0; i < g_nsocks;) {
        int found = 0;
        for (int j = 0; j < g_iface_count; j++) {
            if (!strcmp(g_socks[i].iface, g_iface_list[j]) &&
                g_socks[i].ifindex == if_nametoindex(g_iface_list[j])) found = 1;
        }
        if (found) { i++; continue; }
        close(g_socks[i].fd);
        g_socks[i] = g_socks[--g_nsocks];
    }
    for (int i = 0; i < g_iface_count && g_nsocks < MAX_SOCKS; i++) {
        unsigned int index = if_nametoindex(g_iface_list[i]);
        int found = 0;
        for (int j = 0; j < g_nsocks; j++) if (g_socks[j].ifindex == index) found = 1;
        if (found || !index) continue;
        int fd = socket(AF_PACKET, SOCK_RAW | SOCK_CLOEXEC | SOCK_NONBLOCK, htons(ETH_P_ALL));
        if (fd < 0) continue;
        if (fd >= FD_SETSIZE) { close(fd); continue; }
        int rcvbuf = 4 * 1024 * 1024;
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
        struct sockaddr_ll sll = {0};
        sll.sll_family = AF_PACKET;
        sll.sll_protocol = htons(ETH_P_ALL);
        sll.sll_ifindex = (int)index;
        if (bind(fd, (struct sockaddr *)&sll, sizeof(sll)) != 0) { close(fd); continue; }
        snprintf(g_socks[g_nsocks].iface, sizeof(g_socks[0].iface), "%s", g_iface_list[i]);
        g_socks[g_nsocks].ifindex = index;
        g_socks[g_nsocks++].fd = fd;
    }
    static int last_count = -1, last_expected = -1;
    if (last_count != g_nsocks || last_expected != g_iface_count) {
        char message[128];
        snprintf(message, sizeof(message), "网络接口 %d/%d 已绑定", g_nsocks, g_iface_count);
        emit_status("pcap", g_nsocks > 0 && g_nsocks == g_iface_count, message);
        last_count = g_nsocks;
        last_expected = g_iface_count;
    }
}

static void *cap_thread_fn(void *arg) {
    (void)arg;
    uint8_t *buf = malloc(CAP_BUF);
    if (!buf) { emit_status("pcap", 0, "抓包缓冲区分配失败"); return NULL; }
    long long next_scan = 0;
    while (!g_stop && g_cap_run) {
        struct timespec clock;
        clock_gettime(CLOCK_MONOTONIC, &clock);
        long long now = (long long)clock.tv_sec * 1000 + clock.tv_nsec / 1000000;
        if (now >= next_scan) { refresh_interfaces(); next_scan = now + 2000; }
        fd_set rfds;
        FD_ZERO(&rfds);
        int maxfd = -1;
        for (int i = 0; i < g_nsocks; i++) {
            FD_SET(g_socks[i].fd, &rfds);
            if (g_socks[i].fd > maxfd) maxfd = g_socks[i].fd;
        }
        struct timeval tv = {0, 200000};
        int n = select(maxfd + 1, &rfds, NULL, NULL, &tv);
        if (n <= 0) continue;
        for (int i = 0; i < g_nsocks && !g_stop && g_cap_run; i++) {
            if (!FD_ISSET(g_socks[i].fd, &rfds)) continue;
            // Bound draining: heavy traffic must not starve stop or interface discovery.
            for (int packets = 0; packets < 256 && !g_stop && g_cap_run; packets++) {
                ssize_t r = recv(g_socks[i].fd, buf, CAP_BUF, 0);
                if (r <= 0) break;
                handle_frame(buf, (int)r, g_socks[i].iface);
            }
        }
    }
    free(buf);
    return NULL;
}

int netcap_start(void) {
    if (g_cap_run) return 0;
    g_nsocks = 0;
    g_cap_run = 1;
    if (pthread_create(&g_thread, NULL, cap_thread_fn, NULL) != 0) {
        g_cap_run = 0;
        return -1;
    }
    return 0;
}

void netcap_stop(void) {
    if (!g_cap_run) return;
    g_cap_run = 0;
    pthread_join(g_thread, NULL);
    for (int i = 0; i < g_nsocks; i++) close(g_socks[i].fd);
    g_nsocks = 0;
}
