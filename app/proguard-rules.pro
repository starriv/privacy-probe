# 本项目不使用反射 / 序列化：JSON 全部为手写 org.json 解析，无 Class.forName/getDeclared*，
# Manifest 组件（Activity/Service/Receiver/Application）由 AAPT 自动生成 keep 规则。
# 因此不保留全局 keep 规则，允许 R8 裁剪与混淆 app 包。
#
# 只保留枚举成员名：EventSource.name 写入数据库 source 列并出现在 CSV/JSONL 导出中，
# ProbeId.name 用作 SharedPreferences 键；若被混淆会改变已存数据与设置键的语义。
-keepclassmembers enum dev.popsicle.privprobe.** { *; }
