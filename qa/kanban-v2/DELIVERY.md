# 0.3.10 / code 28 交付核验

包名：com.litewidget.app
APK：/storage/emulated/0/Documents/lite-widget-0.3.10-kanban.apk
字节数：1071062
SHA256：71225da7f4daebf59d421bf15ec4f8376d1cf357f377b61f684e05341751e5b5
签名证书 SHA256：9f4fbf1cb2ad703eb1b1035115f31658c75f6de93853add8076a8e7339fda105
已验证与 lite-widget-0.3.9-kanban-ks3.apk 证书完全相同。未卸载、未清数据、未安装；不把签名一致冒充已经成功覆盖安装。

## 同源与门禁

APK 的精确源码提交：a2bf4a4eff42fd9c6f5ac794f7283996c60581ea
CI： https://github.com/lantianhcgp/lite-widget/actions/runs/43710660862
run_attempt：1，conclusion=success。
APK artifact id：14159884737；测试/图 artifact id：14160482150。
API readback -> ZIP CRC/digest -> APK ZIP -> manifest 二进制 XML -> assets 全结构比对 -> JUnit XML全方法比对 -> Canvas像素报告逐值重算 -> apksigner 验签，均通过。delivery.json 是实际校验结果，不是示例。

- Python 55/55（原39项+新增16项），generator --check 通过。
- 主机 JVM 22/22（数据、规格、展示）。
- CI JVM 27/27：数据12、展示5、规格5、原生Canvas4、真实MCP socket1；零失败、零错误、零跳过。ci-junit-results/ 保留原始 XML。
- CI native Canvas：4规范尺寸+5窄宿主，共9图像素门通过；ci-native-canvas/ 同时保留空/部分任务原始图，共16张。本地重算报告与 CI pixel-checks.json 全字段全值相同。
- precheck 语法/冲突通过；真正 Android SDK 编译由 CI 通过。
- SPEC 唯一阻塞修复后复审通过，QUALITY=APPROVED。
- 所有基线测试整文件逐字节保留，未删/弱化。
- 聚合器 repo与 ~/.hermes/scripts/ 两份逐字节同步，见 README.md 的 SHA256；本轮没有推真实数据，没有定时刷新。

## 改动

1. 每卡四态中文胶囊：运行中（蓝）、受阻（琥珀）、等待（灰）、事故（红）；4px 左侧色轨、渐变底、层次化信息。
2. 未知进度：静态分段条+“进行中”；已知进度仍按真实数值填充。新增 progressLabel 不把0.01/99.99误报0/100，旧percent兼容。
3. 头部标题、四色计数、MM-DD HH:mm 更新时间；少量任务空卡行折叠。
4. 可选 elapsed 分钟来自 keeper start/last_seen；0m/1h23m格式，缺失不编造；schema向后兼容。
5. 四安全尺寸不变：4x4/2x4八项，4x2四项，2x2两项；禁止4x1。无动画、无新权限、不改引擎、不改手动 --push/loopback 边界。

## 图像与装机边界

手机现有0.3.9上隔离原生Canvas夹具（含elapsed）见 device-canvas-fixture/。四尺寸拼图只排布原始PNG，没有合成替代组件；最后目检发现的重叠已修并复看。/storage/emulated/0/Documents/lite-widget-看板视觉升级-自检.png 是完整无重叠版本。所有任务内容都是测试夹具，不是真实看板任务。

新0.3.10自身CI原生Canvas图见 ci-native-canvas/（这里无elapsed字段，确认缺失保持空）。已逐卡目检主4x4、窄2x4、2x2、负一屏4x2和三项任务图，状态可见；不把CI或预览等同于桌面装机验收。

装机归发起方。覆盖安装不会自动覆盖已有 task-kanban 模板（此为保留用户数据的旧行为，原测试仍通过）。装后发起方需：
1. MCP 备份原 widgets/task-kanban/manifest.json 与 widget.json；不删旧数据。
2. 显式将本提交 assets/templates/task-kanban/ 两份 JSON 写到同一路径。widget.json 用紧凑JSON约182539字节，小于单次256KiB；写完 fs_read 与原结构全等比对。
3. 手动运行 scripts/kanban_build.py --push（私有映射和token沿用本机，凭据不入仓），读回真实看板。
4. widget_reload / render，逐卡放大自检后截图给用户。无额外定时器、无自动抓前台。

本证据提交仅更新图像/报告，产品代码、测试、schema、模板、构建配置保持与上述CI精确源码提交一致，不借文档提交替换已核验APK。
