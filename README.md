# Lite Widget

轻量版 KWGT：**文件驱动**的 Android 桌面小组件，内置**局域网 MCP 服务**——让 AI 直接读写组件文件、渲染预览、推送到桌面，闭环开发小组件。

- 渲染：Canvas 自绘引擎，零第三方依赖（Kotlin + 纯 XML），APK ~1MB
- 尺寸：2x2 / 4x1 / 4x2 / 2x4 / 4x4 五个入口，**每种尺寸一套独立设计**（`variants`），尺寸越大信息越多
- 数据：组件在 `widget.json` 里**声明所需变量**，App「变量管理」页自动出表单；凭据只存本机
- 管理：App 内「桌面管理」列出已添加的组件实例，逐个选择模板；每个组件可设独立的自动刷新频率
- 分发：组件 = 自包含压缩包 `.lwgt`（manifest + widget.json + assets），可导入导出互传

## 构建

Push 到 `master` 触发 GitHub Actions（`.github/workflows`），产物为 debug APK artifact。
本地：Android Studio 打开，`./gradlew assembleDebug`。

## widget.json 规格

```jsonc
{
  "version": 1,
  "canvas": { "width": 360, "height": 180, "fit": "auto" },   // 设计坐标系；auto=按组件实际 dp 重排
  "vars":  { "fg": "#FFFFFF" },                                // 设计常量，text 可引用
  "data": {                                                    // 声明外部变量（可选）
    "source": "pddwifi",
    "vars": {
      "baseUrl": { "label": "后台地址", "type": "url",     "required": true, "hint": "..." },
      "devNo":   { "label": "充值号",   "type": "string", "required": true, "secret": true }
    }
  },
  "root": { "type": "frame", "...": "默认(4x2)设计，兼作无变体时的回退" },
  "variants": {                                                // 按尺寸的独立设计（可选）
    "4x1": { "root": { "...": "横条：核心数字+进度+电量" } },
    "2x2": { "root": { "...": "方块：大数字+进度" } },
    "2x4": { "root": { "...": "竖长：套餐+流量+余额/热点" } },
    "4x4": { "root": { "...": "大方：全量信息（含余额/运行时长/SIM/上报时间）" } }
  }
}
```

- 节点类型：`frame`（vertical/horizontal/absolute 布局）/ `text`（bind 数据字段）/ `image` / `progress`（bar|arc）/ `spacer`
- 渲染时按组件实际 dp 用**对数距离**归到最接近的规范尺寸（`4x1=360x90, 4x2=360x180, 2x2=180x180, 4x4=360x360, 2x4=180x360`），取 `variants.<size>`；没有则回退 `root`
- 数据字段命名空间：`flow.*` / `package.*` / `device.*` / `account.*`（见 MCP `data_snapshot`）
- 完整 schema：`app/src/main/assets/schema.json`（MCP `schema_get` 直接取），**additionalProperties=false，写错字段名会报错**

## MCP：让 AI 开发小组件

### 连接

| 项 | 值 |
|---|---|
| 端点 | `http://<手机IP>:8765/mcp`（App 首页显示局域网地址；本机 `http://127.0.0.1:8765/mcp`） |
| 鉴权 | `Authorization: Bearer <token>`（App 首页显示，点按复制；或 `?token=` 查询参数） |
| 探活 | `GET /health` → `{"ok":true,"app":"lite-widget","version":"..."}`（匿名） |
| 协议 | JSON-RPC 2.0（`initialize` / `tools/list` / `tools/call` / `ping`） |

前提：App 首页「MCP 服务」开关打开（前台服务常驻）。

```bash
curl -s -X POST http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <token>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"widget_list","arguments":{}}}'
```

### 工具（13 个）

| 工具 | 参数 | 说明 |
|---|---|---|
| `fs_tree` | `path?` | 列沙箱目录（默认 widgets） |
| `fs_read` | `path` | 读文件，**返回文件原文**（widget.json 即 JSON 文本） |
| `fs_write` | `path`, `content` | 覆盖写；widget.json/manifest.json 会**自动跑 schema 校验**并把问题带在返回里 |
| `fs_delete` | `path` | 删文件/目录 |
| `widget_list` | | 列出所有组件 |
| `widget_validate` | `id` | 对存储的组件跑 schema 校验 |
| `widget_render` | `id`, `size?`(2x2/4x1/4x2/2x4/4x4), `width?`, `height?` | 渲染预览图。返回文本：`渲染成功 ...\nimage/png;base64:\n<base64>`，取 `base64:` 之后解码 |
| `widget_reload` | `id?` | 应用到桌面（不填用当前激活组件），所有实例立即重绘 |
| `log_tail` | `lines?`, `filter?` | App 日志（渲染报错都在这） |
| `log_clear` | | 清空日志 |
| `data_snapshot` | | 当前数据快照（21 个字段） |
| `data_refresh` | | 重新拉后台数据（需要变量已填） |
| `schema_get` | | 返回 widget.json / manifest.json 的 JSON Schema |

### 标准工作流

```
fs_read(widget.json) → 修改 → fs_write（返回里直接带 schema 校验结果）
→ widget_render(id, size="4x4") → 解码 base64 看图、自查样式
→ 再改、再渲染（迭代）
→ widget_reload(id) → 桌面生效
→ log_tail(filter="WidgetUpdater") 确认无渲染异常
```

### 沙箱文件系统（App filesDir）

```
widgets/<id>/
  manifest.json     # id/name/version/size...
  widget.json       # 设计（上面的规格）
  assets/           # 图片、字体
data/snapshot.json  # 后台数据快照（MCP 可读，data_snapshot 也走这里）
logs/app.log        # 应用日志（滚动 512KB）
exports/            # 导出的 .lwgt
schema.json         # 内置 schema
```

读权限：`widgets/ logs/ data/ exports/`；写权限：`widgets/ logs/`。路径越权（`..`、绝对路径）直接拒绝。

### 安全边界

- `data.vars` 声明的变量**值**存在 App SharedPreferences（`var_<name>`），**不写进组件包、不进日志、不通过 MCP 暴露**——MCP 只能看到声明本身
- `.lwgt` 导出包内同样不含任何凭据

## 组件包 .lwgt

zip：`manifest.json` + `widget.json` + `assets/`（可多套一层目录）。导入在 App 首页，导出在组件卡片上。

## License

MIT
