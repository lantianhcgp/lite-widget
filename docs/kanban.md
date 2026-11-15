# 任务进度看板

## 范围与模板

版本：0.3.9-kanban（code 27），包名 com.litewidget.app。基于实验分支 exp5/40fb8d3，不基于 master，未合并。

内置模板 task-kanban（任务进度看板），原生 Canvas 渲染，不用 WebView。
- 4x4 主推：全部 8 个槽位，任务名、四色状态点、进度条、百分比、任务线/备注。
- 2x4：8 槽紧凑竖排。
- 4x2：4 槽横排；2x2：2 槽。还有任务时明确提示数量与切换 4x4。
- 只声明 2x2 / 4x2 / 2x4 / 4x4；不提供负一屏不安全的 4x1。
- 生成器 scripts/generate_kanban_template.py 在构建前展开显式 parcel.N.*，运行期不循环、不绑定颜色；四组静态色点通过 visibleIf 切换。
- assets/templates/task-kanban/{manifest,widget}.json 为唯一模板真源；首次启动播种，升级不覆盖用户已有组件和看板数据。

## 数据契约

独立 JSON Schema：app/src/main/assets/kanban-schema.json；MCP schema_get 返回原 manifest/widget 加 kanban 段。

```json
{
  "updated_at": "2026-11-15T08:00:00Z",
  "tasks": [
    {"name": "示例任务", "line": "示例任务线", "status": "running", "progress": 37, "note": "明确的阶段进度"}
  ]
}
```

上面为格式示例，不是实际运行状态。updated_at 使用两端统一的 RFC3339 日历子集（YYYY-MM-DDTHH:MM:SS[.1..6位小数]Z/±HH:MM，年不能为0000，偏移不超过18:00；拒绝空格日期、周日期、秒偏移）；tasks 0..8 项；name/line 非空且最多64（App UTF-16长度），note 最多240；progress 是0..100有限数值，拒绝字符串/布尔/NaN/Infinity；不允许额外字段。原始 UTF-8 文件读写上限均256KiB，超限或坏数据不会覆盖最后有效看板。

状态：running=绿进行；blocked=黄受阻；waiting=灰等待；incident=红事故。排序优先事故→受阻→进行→等待。

未知百分比：progress=0，note 必须以 [进度未量化] 开头，组件 percent 显示“—”；明确0%仍正常显示0%。不根据耗时、测试通过数或 PID 数猜百分比。

绑定：parcel.count/updated_at/updated_ms/summary；parcel.N.name/line/status/progress/note/percent/detail/statusLabel（N=0..7），不存在的任务不生成 name，visibleIf 自动隐藏。空列表显示“暂无任务”，不拿示例填充。

## 路径与读取链

Termux 聚合输出默认：/storage/emulated/0/Documents/看板.json。
App 实际读取（私有沙箱）：filesDir/widgets/task-kanban/看板.json，对应常见 /data/user/0/com.litewidget.app/files/widgets/task-kanban/看板.json。Termux 不能直接写这个私有路径，必须经已有 App MCP。

手动聚合→MCP fs_write（相对path=widgets/task-kanban/看板.json）→fs_read精确回读确认→App DataRepo 每次渲染读文件→现有曝光刷新重绘。fs_write 只对固定看板路径增加契约校验和原子写，不扩 WRITABLE、不暴露其他数据源凭据。

即使没有填写 WiFi 后台，data_snapshot/data_refresh 也可读有效本地看板；WiFi缓存与本地看板分开，读取看板不会改 WiFi snapshot。MCP 只表示数据写入/读取成功，不能冒称桌面已曝光或真机界面已验。

## 零LLM聚合

源码 scripts/kanban_build.py，交付时安装同字节副本 ~/.hermes/scripts/kanban_build.py。Python标准库，无额外安装、无守护进程。

默认读取 ~/.hermes/scripts/task_keeper_state.json；只读，绝不执行 keeper.cmd。默认映射 ~/.hermes/scripts/kanban_sources.json（也可用 --sources 显式指定），把 keeper.tag 与当前进度源明确关联。源可为单任务 JSON、tasks 数组或当前进度 Markdown。按 tag/query-file 折叠同一任务的 bash/python PID 包装；gone 不代表完成，仅表示 keeper 已不见该进程。

映射示例（自行替换实际路径，不上传真实映射）：
```json
[{"tag": "简报-示例任务", "path": "/path/to/current-progress.md", "name": "示例任务", "line": "示例线"}]
```

用法：
```text
python ~/.hermes/scripts/kanban_build.py --sources ~/.hermes/scripts/kanban_sources.json
python ~/.hermes/scripts/kanban_build.py --sources ~/.hermes/scripts/kanban_sources.json --push --token-file ~/.hermes/secrets/lite_widget_mcp_token
```

--push 是手动数据写入，不是定时推送。token 文件由用户从 App 首页复制保存到私有目录并设600，或使用脚本支持的环境变量；不要把 token 放命令行、源码或共享目录。端点固定本机127.0.0.1:8765/mcp，不向远端发凭据。服务未开时输出短失败说明，不启动App。

幂等：任务内容没变时看板字节与updated_at保持不变。读不到某条已配置任务源则跳过该线；keeper整体损坏/不可读时保留已有有效看板，不把任务伪判完成。进度Markdown仅解析当前明确字段，不把历史受阻/旧测试记录当当前状态；后续非当前段、同级/更高标题是硬边界，不继续采集“过往记录”等段的百分比。复杂文档建议另给单任务JSON进度源。

## 刷新（无新增自动链）

沿用 exp5 的滑入曝光机制：meta miuiWidgetRefresh=exposure、宿主显式 miui.appwidget.action.APPWIDGET_UPDATE 广播、原60000ms最小间隔。滑入后由宿主决定是否发曝光广播；一分钟内连续滑入不保证每次重绘。已有 Handler/AlarmScheduler/默认15分钟调度全部原样保留，不回退、不新增。

曝光只重新读取 App 中已写入的看板，不能替 Termux 重新采集进度。需要新数据时手动运行一次聚合--push；本棒不安装cron/Alarm/曝光额外链路。

可选加速：数据写入回读后可手动 MCP widget_reload {"id":"task-kanban"}；不提供定时reload链。

## 用户装到负一屏

本棒不操作设备。用户自行安装交付APK（不卸载/不清数据），打开Lite Widget：
1. 按现有分支权限引导允许“创建桌面快捷方式”。
2. 首页确认已出现“任务进度看板”，打开现有MCP开关以便手动push。
3. 首页“装到桌面”→选择任务进度看板→4x4（或其他3安全尺寸）。走分支已有 INSTALL_WIDGET 广播；未生效自动退原生pin。
4. 长按桌面组件拖进负一屏；回到主屏再滑入，观察曝光后新数据。
5. 缩略版显示“另N项”时切换4x4查看全部。

## 验收边界

本地：Python聚合/CLI/协议测试、JVM契约/schema、precheck。
CI：精确提交的assembleDebug/testDebugUnitTest；Android Canvas/native graphics输出PNG；真实McpServer的HTTP/socket测试。云端宿主尺寸模拟不是MIUI真机验收，且不安装用户手机。

工作证据和断点在本分支 docs/kanban-checkpoint.md、qa/kanban、CI测试artifact；真实看板、各线映射与token不入公开仓。交付报告以实际CI/APK版本和SHA256为准。
