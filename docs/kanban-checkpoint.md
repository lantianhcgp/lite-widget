# 看板开发断点 · 2026-11-15

基线：origin/experiment/minus-one-widget @ 40fb8d31114b18fe6aed2d9f43c112e5b70146f7。
仅工作于 feat/kanban-20261115，目标版本 0.3.9-kanban/code27。原 master 和实验分支不修改；设备不安装、不拉起。

当前：本地门禁与独立复查通过，待精确提交 CI 出包。
- Kotlin JVM 17/17、Python 39/39；生成器资产一致性检查通过，precheck exit0。
- 独立SPEC R2已通过；独立QUALITY R3对最终Markdown边界15探针通过。修复过程RED保留于qa/kanban/review-r1.json、review-r2.json和fix-r1-test-history.json，最终判定见review-r3.json。
- 新增代码静态扫描无真实凭据/注入/危险反序列化；真实任务、源映射和凭据不入仓（API当前private=false，未改可见性）。
- 四安全尺寸显式槽位：4x4/2x4各8槽，4x2 4槽，2x2 2槽；缩略版标明更多任务，不提供4x1。
- 固定App数据路径widgets/task-kanban/看板.json，DataRepo每次渲染重读；WiFi快照独立，原权限范围不变。
- 原Manifest/Prefs/WidgetProvider/WidgetUpdater/AlarmScheduler/RefreshReceiver/BootReceiver共7文件逐字节冻结。
- 脚本已同字节安装~/.hermes/scripts/kanban_build.py；最新实际keeper生成2条running任务，均未明确量化。重复执行字节和mtime不变，脱敏证据qa/kanban/actual-cli-sanitized.json；未调用设备MCP。

下一步：提交并push独立分支→精确SHA的CI构建/原生Canvas/协议测试/像素验证→图片逐组件目检→APK身份/签名/SHA256→更新本断点与Documents进度。

禁做：merge/push master/experiment、不装任何设备包、不动表端/elcton/留白/haps、不新增自动刷新链/常驻服务。
