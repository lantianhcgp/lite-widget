package com.litewidget.app.core.store

/**
 * 内置组件模板。新建组件时写入 widget.json。
 * 这份模板同时是渲染引擎的「样式样张」——渐变卡片、渐变大数字、进度条、条件显隐。
 */
object Templates {

    fun flowCard(): String = """
{
  "version": 1,
  "canvas": { "width": 360, "height": 180, "fit": "auto" },
  "vars": {
    "bg1": "#1B1B1F",
    "bg2": "#101014",
    "stroke": "#2A2A30",
    "fg": "#FFFFFF",
    "dim": "#9E9EA6",
    "accent1": "#4CAF50",
    "accent2": "#8BC34A"
  },
  "root": {
    "type": "frame",
    "direction": "vertical",
    "justify": "space-between",
    "width": "fill",
    "height": "fill",
    "padding": 16,
    "gap": 7,
    "style": {
      "radius": 20,
      "background": { "type": "linear", "colors": ["#1B1B1F", "#101014"], "angle": 135 },
      "border": { "color": "#2A2A30", "width": 1 },
      "shadow": { "color": "#80000000", "blur": 14, "x": 0, "y": 6 }
    },
    "children": [
      {
        "type": "frame",
        "direction": "horizontal",
        "width": "fill",
        "align": "center",
        "children": [
          { "type": "text", "text": "当前套餐", "size": 12, "color": "#9E9EA6" },
          { "type": "spacer", "size": "fill" },
          { "type": "text", "bind": { "field": "package.expire" }, "size": 11, "color": "#6A6A72" }
        ]
      },
      {
        "type": "text",
        "bind": { "field": "package.name" },
        "size": 15,
        "weight": 600,
        "color": "#FFFFFF",
        "maxLines": 1
      },
      {
        "type": "frame",
        "direction": "horizontal",
        "width": "fill",
        "align": "end",
        "children": [
          {
            "type": "text",
            "bind": { "field": "flow.used", "format": { "unit": "auto", "decimals": 1, "suffix": " 已用" } },
            "size": 32,
            "weight": 700,
            "letterSpacing": -0.02,
            "gradient": { "type": "linear", "colors": ["#FFFFFF", "#B8B8C4"], "angle": 90 }
          },
          { "type": "spacer", "size": "fill" },
          {
            "type": "text",
            "bind": { "field": "flow.remain", "format": { "unit": "auto", "decimals": 1, "suffix": " 剩余" } },
            "size": 12,
            "color": "#9E9EA6"
          }
        ]
      },
      {
        "type": "progress",
        "shape": "bar",
        "bind": { "field": "flow.percent", "format": { "unit": "%", "decimals": 1 } },
        "thickness": 8,
        "roundCap": true,
        "width": "fill",
        "track": { "type": "solid", "color": "#26262C" },
        "bar": { "type": "linear", "colors": ["#4CAF50", "#8BC34A"], "angle": 0 }
      },
      {
        "type": "frame",
        "direction": "horizontal",
        "width": "fill",
        "align": "center",
        "children": [
          { "type": "text", "bind": { "field": "device.battery", "format": { "suffix": "% 电量" } }, "size": 11, "color": "#9E9EA6" },
          { "type": "spacer", "size": "fill" },
          { "type": "text", "bind": { "field": "device.ssid" }, "size": 11, "color": "#6A6A72" },
          { "type": "spacer", "size": 6 },
          {
            "type": "text",
            "text": "已断网",
            "size": 11,
            "color": "#FF5252",
            "visibleIf": { "field": "device.status", "op": "eq", "value": 0 }
          }
        ]
      }
    ]
  }
}
""".trimIndent()

    fun emptyCard(id: String, name: String): String = """
{
  "version": 1,
  "canvas": { "width": 360, "height": 180, "fit": "auto" },
  "root": {
    "type": "frame",
    "width": "fill",
    "height": "fill",
    "padding": 16,
    "style": {
      "radius": 20,
      "background": { "type": "solid", "color": "#17171D" },
      "border": { "color": "#26262E", "width": 1 }
    },
    "children": [
      { "type": "text", "text": "$name", "size": 16, "weight": 600, "color": "#FFFFFF" },
      { "type": "spacer", "size": 8 },
      { "type": "text", "text": "id: $id — 通过 MCP 改 widget.json 来画它", "size": 12, "color": "#8A8A96" }
    ]
  }
}
""".trimIndent()
}
