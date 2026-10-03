package com.litewidget.app.core.store

import com.litewidget.app.core.AppLog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 组件包 (.lwgt = zip) 与沙箱目录的读写。
 * 沙箱根：filesDir/widgets/<id>/  结构见 lite-widget-schema.json
 */
class WidgetStore(filesDir: File) {

    val dir = File(filesDir, "widgets")

    data class Item(
        val id: String,
        val name: String,
        val version: String,
        val size: String,
        val author: String,
        val dir: File
    )

    fun list(): List<Item> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                val m = readJson(d.name, "manifest.json") ?: return@mapNotNull null
                Item(
                    id = d.name,
                    name = m.optString("name", d.name),
                    version = m.optString("version", "0.0.0"),
                    size = m.optString("size", "4x2"),
                    author = m.optString("author", ""),
                    dir = d
                )
            }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun exists(id: String): Boolean = File(dir, id).isDirectory

    private fun safeId(id: String): String = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /** rel 形如 "widget.json" / "assets/bg.png"，禁止越权 */
    private fun resolve(id: String, rel: String): File? {
        if (rel.contains("..") || rel.startsWith("/")) return null
        val base = File(File(dir, safeId(id)), rel)
        val canon = base.canonicalFile
        val root = File(dir, safeId(id)).canonicalFile
        if (!canon.path.startsWith(root.path)) return null
        return canon
    }

    fun readText(id: String, rel: String): String? = try {
        resolve(id, rel)?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    fun writeText(id: String, rel: String, content: String): Boolean {
        val f = resolve(id, rel) ?: return false
        return try {
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
            AppLog.i("write $id/$rel (${content.length} bytes)")
            true
        } catch (t: Throwable) {
            AppLog.e("write $id/$rel fail", t)
            false
        }
    }

    fun deleteFile(id: String, rel: String): Boolean {
        val f = resolve(id, rel) ?: return false
        return f.delete()
    }

    fun deleteWidget(id: String): Boolean {
        val f = File(dir, safeId(id))
        if (!f.isDirectory) return false
        f.deleteRecursively()
        AppLog.i("widget deleted: $id")
        return true
    }

    fun readManifest(id: String): JSONObject? = readJson(id, "manifest.json")
    fun readSpec(id: String): JSONObject? = readJson(id, "widget.json")

    private fun readJson(id: String, rel: String): JSONObject? {
        val s = readText(id, rel) ?: return null
        return try {
            JSONObject(s)
        } catch (t: Throwable) {
            AppLog.w("json parse fail $id/$rel: ${t.message}")
            null
        }
    }

    /** 校验一个组件目录，返回错误列表（空 = 通过） */
    fun validate(id: String): List<String> {
        val out = ArrayList<String>()
        val m = readManifest(id)
        if (m == null) out.add("manifest.json 缺失或无法解析")
        else out.addAll(com.litewidget.app.core.spec.SpecValidator.manifest(m).map { "manifest.json -> $it" })
        val s = readSpec(id)
        if (s == null) out.add("widget.json 缺失或无法解析")
        else out.addAll(com.litewidget.app.core.spec.SpecValidator.widget(s).map { "widget.json -> $it" })
        return out
    }

    // ---------------- 新建（内置模板） ----------------

    fun create(id: String, name: String): Boolean {
        val sid = safeId(id)
        if (exists(sid)) return false
        val manifest = JSONObject()
            .put("id", sid)
            .put("name", name)
            .put("author", "")
            .put("version", "1.0.0")
            .put("size", "4x2")
            .put("minAppVersion", "0.1.0")
            .put("schemaVersion", 1)
            .put("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
        File(dir, sid).mkdirs()
        writeText(sid, "manifest.json", manifest.toString(2))
        writeText(sid, "widget.json", Templates.flowCard())
        File(File(dir, sid), "assets").mkdirs()
        AppLog.i("widget created: $sid")
        return true
    }

    /** 内置模板播种：不存在才写，绝不覆盖用户已有组件（幂等） */
    fun ensureBuiltins() {
        try {
            if (!exists("lightwash")) {
                create("lightwash", "液态玻璃")
                writeText("lightwash", "widget.json", Templates.lightGlass())
                AppLog.i("builtin template seeded: lightwash")
            }
        } catch (t: Throwable) {
            AppLog.e("ensureBuiltins fail", t)
        }
    }

    // ---------------- 导入 / 导出 ----------------

    /** 导入 .lwgt，返回组件 id；失败抛异常带中文原因 */
    fun importZip(input: InputStream): String {
        val tmp = File.createTempFile("lwgt", ".tmp")
        try {
            input.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
            }
            val staging = File.createTempFile("stage", "")
            staging.delete()
            staging.mkdirs()

            // 先解到暂存区，找出 manifest 所在层（支持包内多套一层目录）
            ZipInputStream(tmp.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                var count = 0
                while (entry != null) {
                    val name = entry.name
                    if (name.contains("..") || name.startsWith("/")) {
                        throw IllegalStateException("非法路径: $name")
                    }
                    val out = File(staging, name)
                    if (entry.isDirectory) out.mkdirs()
                    else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                    count++
                    if (count > 500) throw IllegalStateException("条目过多")
                    entry = zin.nextEntry
                }
            }

            var root = staging
            if (!File(staging, "manifest.json").isFile) {
                val subs = staging.listFiles { f -> f.isDirectory }
                if (subs != null && subs.size == 1 && File(subs[0], "manifest.json").isFile) {
                    root = subs[0]
                } else {
                    throw IllegalStateException("包内找不到 manifest.json（必须在根目录或唯一子目录）")
                }
            }

            val manifest = JSONObject(File(root, "manifest.json").readText(Charsets.UTF_8))
            val id = safeId(manifest.optString("id", ""))
            if (id.isEmpty()) throw IllegalStateException("manifest.id 为空")
            if (!File(root, "widget.json").isFile) throw IllegalStateException("包内缺少 widget.json")

            val target = File(dir, id)
            if (target.exists()) target.deleteRecursively()
            root.copyRecursively(target, overwrite = true)
            AppLog.i("imported widget: $id")
            return id
        } finally {
            tmp.delete()
        }
    }

    /** 导出为 .lwgt zip，写到 dest */
    fun exportZip(id: String, dest: OutputStream) {
        val src = File(dir, safeId(id))
        if (!src.isDirectory) throw IllegalStateException("组件不存在: $id")
        ZipOutputStream(dest.buffered()).use { zos ->
            src.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(src).path
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        AppLog.i("exported widget: $id")
    }

    fun exportToCache(id: String): File {
        val outDir = File(dir.parentFile, "exports") // filesDir/exports
        outDir.mkdirs()
        val out = File(outDir, "$id.lwgt")
        exportZip(id, out.outputStream())
        return out
    }

    fun toJson(items: List<Item>): String {
        val arr = org.json.JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("version", it.version)
                    .put("size", it.size)
                    .put("author", it.author)
            )
        }
        return arr.toString()
    }

    fun toByteArray(id: String): ByteArray {
        val bos = ByteArrayOutputStream()
        exportZip(id, bos)
        return bos.toByteArray()
    }
}
