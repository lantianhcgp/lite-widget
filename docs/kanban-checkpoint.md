# 看板开发断点 · 2026-11-15 · 已交付

基线：origin/experiment/minus-one-widget @ 40fb8d31114b18fe6aed2d9f43c112e5b70146f7（exp5）。
独立分支feat/kanban-20261115；未合并、未修改master/experiment；APK源码21cf599c99168fc5a2446cdff18f239372b8edef。

## 交付
- 包名com.litewidget.app，版本0.3.9-kanban/code27；APK 1038442 bytes。
- SHA256：65dd95a6efba787097e3aa349f83bb1b34132db71e33d3ad99886c5e1bfc1dce。
- 手机文件：/storage/emulated/0/Documents/lite-widget-0.3.9-kanban.apk。
- 最新测试图：/storage/emulated/0/Documents/lite-widget-看板-最新测试图.png（真实Canvas测试fixture，不是真机截图）。
- CI：https://github.com/lantianhcgp/lite-widget/actions/runs/43697998446，attempt1 success，APK artifact14155461115，测试artifact14155147392。
- 本地脚本已安装~/.hermes/scripts/kanban_build.py，与源码同字节；实际用法输出见qa/kanban/actual-cli-sanitized.json及Documents进度文件。
- 本收尾提交只追加文档/脱敏验收证据，不改变APK源码，使用[skip ci]避免重建相同代码。

## 验收证据
- 本地预检exit0、JVM17/17、Python39/39。
- 云端Android22项，0失败/错误/跳过；真实MCPServer socket读写回读、snapshot/refresh、坏契约拒写通过。
- 规范+MIUI尺寸9图像素门，本地复验与CI报告逐值一致；完整16图已按组件放大目检，另有8条进度像素长度断言。
- aapt2包身份/版本、apksigner v2验签、ZIP CRC、包内看板模板及schema与源码逐字节一致。
- 独立SPEC R2/QUALITY R3通过，前两轮失败和修复RED历史保留。证据：qa/kanban/{ci-run,ci-junit-summary,pixel-checks,visual-review,progress-row-pixels,delivery,review-r1,review-r2,review-r3}.json。
- GitHub artifact ZIP digest已与本地下载一致；真实任务/映射/token不入仓（API当前private=false，未改可见性）。

## 已实现
- 原生8槽parcel.N.*+visibleIf，四静态状态色点，不增加运行期循环或颜色绑定。
- 安全2x2/4x2/2x4/4x4；4x4/2x4全8项，4x2四项、2x2两项且标明剩余数量。
- 固定App私有数据widgets/task-kanban/看板.json，MCP fs_write契约校验+原子替换+fs_read精确回读；DataRepo每次渲染重读，WiFi缓存独立。
- 未明确进度显示“—”，不按运行时长/测试数/PID数推断；Markdown只采当前段，历史/后续段不回放。
- 无新增定时链/常驻服务。原Manifest/Prefs/WidgetProvider/WidgetUpdater/AlarmScheduler/RefreshReceiver/BootReceiver七文件逐字节冻结。

## 下一步与边界
代码交付完成，用户自行安装、手动--push和“装到桌面”后拖入负一屏（详见docs/kanban.md）。未装机/未拉起App，云端Canvas不是MIUI真机验收。旧包签名覆盖兼容性尚未核实，若冲突请勿先卸载或清数据。
原exp5曝光60秒节流、既有Handler/Alarm/default15min均原样保留；滑入只读App已写入的看板，不重新采集Termux任务，widget_reload仅文档可选加速。
禁做：merge/push master/experiment、装设备包、动表端/elcton/留白/haps、添加自动刷新或常驻进程。
