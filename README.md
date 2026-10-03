# Lite Widget

轻量版 KWGT：**文件驱动的 Android 桌面小组件**。Kotlin + 纯 XML + Canvas 自绘，零第三方依赖，APK 约 1MB。组件是文件夹里的 `widget.json`，改文件就能改外观——AI 可通过内置 MCP 服务直接替你设计组件。

---

## 新手上路（从零走一遍）

### 第 1 步：安装
1. 到 [Releases](../../releases) 下载最新 `lite-widget-v*.apk` → 安装（允许未知来源）
2. 打开 App，首页是版本号和几个卡片

### 第 2 步：往桌面添加小组件
1. 桌面**长按空白处** → **小部件 / 小组件**
2. 找到 **Lite Widget**，5 种尺寸：`4x1 横条`、`4x2 标准`、`2x2 方形`、`2x4 竖长`、`4x4 大方`
3. 长按拖到桌面 → 立刻显示默认组件（自带「流量仪表盘」和「液态玻璃」）

> 同一模板放任意尺寸实例都行，每个尺寸有独立布局，自动换排版。

### 第 3 步：挑模板
1. 首页**点组件卡片** → **全尺寸预览画廊**（深色底、按真实相对比例，2x2 只有 4x2 一半宽）
2. 点 **「应用」** → 桌面立即生效

内置模板：
- **液态玻璃**：亮色磨砂玻璃，白光洗 + 深墨字 + 可选真壁纸模糊层
- **Material Expressive**：Google M3 表达风格，紫调色块 + 平涂大圆角 + 粗进度条
- **流量仪表盘**：渐变 + 圆弧仪表炫色风

### 第 4 步：填数据（变量管理）
1. 首页 → **变量管理** → 填「后台地址」（如 `http://pddwifi.gzkpiot.com`）和「充值号」
2. **保存** → **刷新数据**
3. ⚠️ 这些值只存本机：不进组件包、不导出、不通过 MCP 暴露；不填也能用（占位数据）

### 第 5 步：桌面管理（按桌面实例调）
首页 → **桌面管理**，每个桌面实例可以：
- **更换模板**：列表标注**与实例对应的尺寸**（4x1 实例就标 4x1 有无专属布局）
- **刷新频率**：自己输数值 + 选单位 **分钟 / 小时 / 天**（如 `2 小时`、`1 天`），也可"不自动刷新"
- **恢复默认**：回到全局激活模板

> 频率按**桌面实例**记：桌面两个组件可以一个 5 分钟刷、一个 1 天刷。

### 第 6 步：壁纸磨砂（可选）
1. 首页 **效果** 卡片 → 打开 **壁纸磨砂**
2. 首次跳设置授 **「所有文件访问权限」**（只一次）→ 回来显示「生效中」
3. 玻璃卡底变成**真实壁纸的模糊层**；关掉退回纯白光洗

### 第 7 步：导入 / 导出
- **导出**：组件卡片「导出」→ `.lwgt` 文件（zip），可分享
- **导入**：首页底部「导入组件」→ 选 `.lwgt`
- ⚠️ 凭据（充值号等）不会被打进 `.lwgt`

### FAQ
**不自动刷新？** 桌面管理 → 该实例 → 刷新频率（按实例存的）。
**四角发黑？** 壁纸本身暗 + 旧版投影；当前版本卡片无投影，角外就是壁纸原色。
**变量改了没生效？** 变量管理「保存」→「刷新数据」；桌面没动就到桌面管理重新"更换模板"一次。
**MCP 是干嘛的？** 给 AI 的开发接口：开了之后 AI 能直接读写组件文件、渲染预览、查日志。普通用户可一直关着。

---

## 开发者：MCP 协议（AI 接入）

首页打开 **MCP 服务** 后，本地端点：

```
POST http://<手机IP>:8765/mcp
Authorization: Bearer <token>      # 首页可复制
Content-Type: application/json
```

- JSON-RPC 2.0：`method: "tools/call"` + `params.name` / `params.arguments`
- `GET /health` 免鉴权（只回版本号）

### 13 个工具
| 工具 | 参数 | 说明 |
|---|---|---|
| `fs_tree` | `path` | 列沙箱目录 |
| `fs_read` | `path` | 读组件文件（返回解析后的 JSON） |
| `fs_write` | `path, content` | 写组件文件（写完自动跑 schema） |
| `fs_delete` | `path` | 删文件 |
| `widget_list` | - | 列组件 |
| `widget_validate` | `id` | schema 校验（写完必调） |
| `widget_render` | `id, size?, width?, height?` | 渲染 PNG（base64）自检 |
| `widget_reload` | `id` | 应用到桌面 |
| `log_tail` / `log_clear` | `lines?, filter?` / - | App 日志 |
| `data_snapshot` / `data_refresh` | - | 数据快照 / 重拉 |
| `schema_get` | - | widget.json schema |

### 沙箱文件系统
```
widgets/<id>/
├── widget.json      # 组件定义
├── manifest.json    # id/名称/版本/尺寸
└── assets/          # 图片字体（assets/xxx 引用）
```
路径限定 `widgets/` 下，越权返回 `路径越权`。

## 开发者：widget.json 规格（摘要）

```jsonc
{
  "version": 1,
  "canvas": { "width": 360, "height": 180, "fit": "auto" },
  "data":   { "source": "pddwifi", "vars": { } },
  "root":   { },                  // 节点树 = 未匹配尺寸的回退布局
  "variants": { "4x1": {"root": {}}, "2x2": {}, "2x4": {}, "4x4": {} }
}
```

- **节点**：`frame`（direction/justify/align/gap/padding/children）、`text`（text 或 bind，size/weight/color/lineHeight/maxLines/align）、`image`、`progress`（value|bind、track/bar、roundCap）、`spacer`
- **style**：`radius`（数或 `[t,r,b,l]`，**padding 同为四元组**）、`background`（`solid`/`linear`/`radial`/`image`/`frost` 真壁纸磨砂）、`border`、`shadow`、`shape:"squircle"`、`rim`、`innerGlow`、`opacity`、`transform`
- **颜色**：`#RRGGBB` / `#AARRGGBB`（**8 位 alpha 在前**）
- **bind**：`{"field": "flow.used", "format": {"unit":"auto","decimals":1}}`；字段 `flow.* / device.* / package.* / account.* / vars.*`；format：`unit/decimals/prefix/suffix/multiplier/date`
- 完整 schema：`app/src/main/assets/schema.json` 或 `schema_get`

### 构建
```bash
# CI：push master 触发 GitHub Actions（约 6 分钟）
bash scripts/precheck.sh   # 本地语法预检
```

## 隐私
敏感变量仅本机 SharedPreferences：不进组件包、不导出、不进日志、不通过 MCP 返回。`.lwgt` = `widget.json` + `manifest.json` + `assets/`。
