package com.litewidget.app.widget

/**
 * 多尺寸入口：每种规格一个 receiver（各自独立的 appwidget-provider xml），
 * 渲染逻辑完全复用 WidgetProvider，尺寸差异由 Renderer 的 auto 模式自适应。
 * 规格命名 = 桌面格子 宽×高（minWidth = n*70-30，Android 标准公式）。
 */
class WidgetProvider2x2 : WidgetProvider()
class WidgetProvider4x1 : WidgetProvider()
class WidgetProvider4x2 : WidgetProvider()
class WidgetProvider2x4 : WidgetProvider()
class WidgetProvider4x4 : WidgetProvider()
