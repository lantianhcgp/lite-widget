package com.litewidget.app.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.LruCache
import java.io.File

/**
 * 按组件目录加载资源：assets/xxx.png、assets/font/xxx.ttf
 * 每个 widget 目录一份，带内存缓存。
 */
class WidgetAssets(private val widgetDir: File) {

    private val bmpCache = LruCache<String, Bitmap>(32) // 最多缓存 32 张
    private val tfCache = HashMap<String, Typeface?>()

    /** path 形如 "assets/bg.png"，相对组件目录 */
    fun bitmap(path: String): Bitmap? {
        if (path.isEmpty()) return null
        bmpCache.get(path)?.let { return it }
        val rel = path.removePrefix("assets/")
        val f = File(File(widgetDir, "assets"), rel)
        if (!f.isFile) return null
        return try {
            val bm = decodeScaled(f) ?: return null
            bmpCache.put(path, bm)
            bm
        } catch (t: Throwable) {
            null
        }
    }

    fun typeface(path: String): Typeface? {
        tfCache[path]?.let { return it }
        val tf = try {
            if (path == "default" || path.isEmpty()) Typeface.DEFAULT
            else if (path == "sans") Typeface.SANS_SERIF
            else if (path == "serif") Typeface.SERIF
            else if (path == "mono") Typeface.MONOSPACE
            else {
                val rel = path.removePrefix("assets/")
                val f = File(File(widgetDir, "assets"), rel)
                if (f.isFile) Typeface.createFromFile(f) else Typeface.DEFAULT
            }
        } catch (t: Throwable) {
            Typeface.DEFAULT
        }
        tfCache[path] = tf
        return tf
    }

    /** 大图降采样，避免 OOM */
    private fun decodeScaled(f: File): Bitmap? {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opt)
        var sample = 1
        val maxDim = 1024
        while (opt.outWidth / sample > maxDim || opt.outHeight / sample > maxDim) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, o2)
    }

    fun clear() {
        bmpCache.evictAll()
        tfCache.clear()
    }
}

/** App 级工厂：按 widget id 取资源句柄 */
class AssetLoader(private val filesDir: File) {
    private val map = HashMap<String, WidgetAssets>()

    @Synchronized
    fun forWidget(id: String): WidgetAssets =
        map.getOrPut(id) { WidgetAssets(File(File(filesDir, "widgets"), id)) }

    @Synchronized
    fun invalidate(id: String) {
        map.remove(id)?.clear()
    }
}
