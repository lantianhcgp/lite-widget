package com.litewidget.app

import com.litewidget.app.core.data.KanbanData
import com.litewidget.app.mcp.McpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

/** 云端真实 server/socket/protocol；不调用手机设备，所有 socket 在 finally 收尾。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = App::class)
class KanbanMcpTest {
    @Test fun fixedBoardPathSupportsWriteReadSnapshotAndRejectsInvalidPayload() {
        val app = RuntimeEnvironment.getApplication() as App
        val server = McpServer(app)
        val credential = "test-only-not-a-real-credential"
        assertTrue(server.start(0, credential))
        fun call(name: String, args: JSONObject = JSONObject()): JSONObject {
            val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                .put("params", JSONObject().put("name", name).put("arguments", args))
            val connection = URL("http://127.0.0.1:${server.boundPort}/mcp").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Auth" + "orization", "Bear" + "er " + credential)
                connection.setFixedLengthStreamingMode(request.toString().toByteArray(Charsets.UTF_8).size)
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                assertEquals(200, connection.responseCode)
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result")
            } finally {
                connection.disconnect()
            }
        }
        fun content(result: JSONObject): String = result.getJSONArray("content").getJSONObject(0).getString("text")
        try {
            val task = JSONObject().put("name", "协议验证").put("line", "MCP")
                .put("status", "running").put("progress", 42).put("note", "测试夹具")
            val board = JSONObject().put("updated_at", "2026-11-15T08:00:00Z").put("tasks", JSONArray().put(task))
            val args = JSONObject().put("path", KanbanData.RELATIVE_PATH).put("content", board.toString())
            val write = call("fs_write", args)
            assertFalse(write.optBoolean("isError"))
            assertEquals(board.toString(), content(call("fs_read", JSONObject().put("path", KanbanData.RELATIVE_PATH))))
            val snapshot = JSONObject(content(call("data_snapshot")))
            assertEquals(42, snapshot.getInt("parcel.0.progress"))
            assertFalse(call("data_refresh").optBoolean("isError"))
            val invalid = JSONObject(board.toString())
            invalid.getJSONArray("tasks").getJSONObject(0).put("progress", 101)
            assertTrue(call("fs_write", JSONObject().put("path", KanbanData.RELATIVE_PATH).put("content", invalid.toString())).getBoolean("isError"))
            assertEquals(board.toString(), content(call("fs_read", JSONObject().put("path", KanbanData.RELATIVE_PATH))))
            assertTrue(call("fs_write", JSONObject().put("path", "data/board.json").put("content", board.toString())).getBoolean("isError"))
            val schemas = JSONObject(content(call("schema_get")))
            assertTrue(schemas.has("manifest"))
            assertTrue(schemas.has("widget"))
            assertEquals(8, schemas.getJSONObject("kanban").getJSONObject("properties").getJSONObject("tasks").getInt("maxItems"))
            assertEquals(BuildConfig.VERSION_NAME, McpServer.VERSION)
            assertEquals(BuildConfig.VERSION_NAME, BuildConfigCompat.VERSION)
        } finally {
            server.stop()
            assertFalse(server.running)
            assertEquals(-1, server.boundPort)
        }
    }
}
