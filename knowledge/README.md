# knowledge

本项目自带的隐私审计知识数据，供 `scripts/gen-datasets.py` 在本地生成
`app/src/main/assets/datasets/*.json`。包含：

- `kb-core.tsv` / `kb-miui.tsv`：系统包中文名、分类、处置建议
- `report.json`：应用的端点、dataTypes、评分、风险
- `domains-trackers.txt` / `domains-functional.txt` / `domains-other.txt`：域名分类
- `DEBLOAT-EXECUTED.md`：已执行清理清单

所有文件随项目交付，构建/运行不读取外部仓库，也不联网。
