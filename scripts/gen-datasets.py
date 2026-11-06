#!/usr/bin/env python3
"""从随项目自带的隐私审计知识生成 App 内置数据集（不依赖任何外部仓库）。

数据源（全部本地、不联网）：
  knowledge/kb-core.tsv              AOSP/框架包中文名、分类、处置建议
  knowledge/kb-miui.tsv              小米/HyperOS 包中文名、分类、处置建议
  knowledge/report.json              NOIR 报告：应用的端点 / dataTypes / 评分
  knowledge/domains-trackers.txt     追踪器 / 广告 / 崩溃上报域名
  knowledge/domains-functional.txt   功能域名（不建议拦）
  knowledge/domains-other.txt        未分类域名
  knowledge/DEBLOAT-EXECUTED.md      已执行清理清单（随项目带入）

输出到 app/src/main/assets/datasets/：
  package_intel.json, domain_intel.json, device_profile.json
"""
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
KNOW = os.path.join(ROOT, "knowledge")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "datasets")
os.makedirs(OUT, exist_ok=True)


def read_tsv(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            if not line.strip():
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < len(header):
                parts += [""] * (len(header) - len(parts))
            rows.append(dict(zip(header, parts)))
    return rows


def read_domains(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            line = re.sub(r"^[-*]\s*", "", line)
            if re.match(r"^[a-z0-9._-]+$", line, re.I):
                out.append(line.lower().rstrip("."))
    return out


def main():
    kb = {}
    for name in ("kb-core.tsv", "kb-miui.tsv"):
        for row in read_tsv(os.path.join(KNOW, name)):
            pkg = row.get("package", "").strip()
            if not pkg:
                continue
            kb[pkg] = {
                "label": row.get("label_zh", "").strip(),
                "category": row.get("category", "").strip(),
                "recommendation": row.get("recommendation", "").strip(),
                "note": (row.get("reason", "") or "").strip()[:240],
            }

    report = json.load(open(os.path.join(KNOW, "report.json"), encoding="utf-8"))
    apps = {a["package"]: a for a in report.get("apps", []) if a.get("package")}
    recs = {r["package"]: r for r in report.get("recommendations", []) if r.get("package")}

    packages = {}
    for pkg, app in apps.items():
        entry = {
            "system": bool(app.get("system")),
            "uid": app.get("uid"),
            "category": app.get("category") or None,
            "vendor": app.get("vendor") or None,
            "dataTypes": app.get("dataTypes") or [],
            "endpoints": sorted({e for v in (app.get("leaks") or {}).values() for e in v}),
            "score": app.get("score"),
        }
        if pkg in kb:
            entry["label"] = kb[pkg]["label"]
            entry["category"] = entry["category"] or kb[pkg]["category"]
        rec = recs.get(pkg)
        if rec:
            entry["risk"] = rec.get("risk")
            entry["action"] = rec.get("action")
            entry["reason"] = rec.get("reason")
        packages[pkg] = entry

    trackers = read_domains(os.path.join(KNOW, "domains-trackers.txt"))
    functional = read_domains(os.path.join(KNOW, "domains-functional.txt"))
    other = read_domains(os.path.join(KNOW, "domains-other.txt"))
    kinds = {}
    for d in trackers:
        kinds[d] = "tracker"
    for d in functional:
        kinds[d] = "functional"
    for d in other:
        kinds.setdefault(d, "unknown")
    # 固定域名 → 包列表顺序：set 迭代顺序随进程哈希种子变化，直接 append 会让产出的 JSON 不确定。
    domain_packages = {}
    for pkg in sorted(apps):
        app = apps[pkg]
        for domain in sorted({e for v in (app.get("leaks") or {}).values() for e in v}):
            domain_packages.setdefault(domain, []).append(pkg)

    debloat = {}
    debloat_md = os.path.join(KNOW, "DEBLOAT-EXECUTED.md")
    if os.path.exists(debloat_md):
        text = open(debloat_md, encoding="utf-8").read()
        for pkg in sorted(set(re.findall(r"\b(?:com|android|miui)\.[a-zA-Z0-9_.]+", text))):
            debloat[pkg] = True

    device_profile = {
        "model": "2509FPN0BC",
        "marketingName": "Xiaomi 17 Pro Max",
        "os": "HyperOS 4 (OS4.0.5.0.XPBCNXM)",
        "android": "Android 17 / SDK 37",
        "kernel": "6.12.69-android16-6-g43b5ab7e247d-abogki558343811-4k",
        "root": "KernelSU 越狱模式（late-load LKM），su domain u:r:ksu:s0，SELinux Enforcing，全 capabilities",
        "audit": {
            "scannedPackages": len(report.get("apps", [])),
            "trackerDomains": len(trackers),
            "repoKnowledgePackages": len(kb),
        },
        "dataSource": "knowledge/: kb-*.tsv + report.json + domains-*.txt + DEBLOAT-EXECUTED.md",
    }

    def dump(name, obj):
        path = os.path.join(OUT, name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
        print(f"wrote {path} ({os.path.getsize(path)} bytes)")

    dump("package_intel.json", {"packages": packages, "debloat": debloat})
    dump("domain_intel.json", {"kinds": kinds, "packages": domain_packages})
    dump("device_profile.json", device_profile)


if __name__ == "__main__":
    sys.exit(main())
