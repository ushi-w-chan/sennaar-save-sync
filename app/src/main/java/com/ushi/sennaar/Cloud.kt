package com.ushi.sennaar

import org.json.JSONObject
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

private val CONTENT_HASH = Regex("[0-9a-f]{64}")

internal data class CloudItem(
    val id: String,
    val timestamp: Instant,
    val device: String,
    val slot: String?,
    val contentHash: String?,
)

internal class Cloud(endpoint: String, private val token: String) {
    private val base = URL(endpoint.trim().trimEnd('/'))

    init {
        require(base.protocol == "https" && base.host.isNotBlank() &&
            base.userInfo == null && base.query == null && base.ref == null) { "请输入 HTTPS Worker 地址" }
        require(token.isNotBlank() && token.none { it.isWhitespace() }) { "请输入同步令牌" }
    }

    fun list(): List<CloudItem> {
        val items = mutableListOf<CloudItem>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val query = cursor?.let { "?cursor=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val page = JSONObject(request("GET", "/snapshots$query"))
            val entries = page.getJSONArray("items")
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                require(SNAPSHOT_ID.matches(id))
                items += CloudItem(
                    id, Instant.parse(entry.getString("timestamp")), entry.getString("device"),
                    entry.opt("slot") as? String,
                    (entry.opt("contentHash") as? String)?.takeIf(CONTENT_HASH::matches),
                )
            }
            cursor = if (page.isNull("cursor")) null else page.getString("cursor")
            check(cursor == null || seen.add(cursor)) { "云端列表分页异常" }
        } while (cursor != null)
        return items.sortedByDescending { it.timestamp }
    }

    fun upload(snapshot: Snapshot) {
        try {
            request("PUT", "/snapshots/${snapshot.id}", snapshot.encode(), expectedCode = 201)
        } catch (failure: CloudError) {
            if (failure.code != 412 || download(snapshot.id).encode() != snapshot.encode()) throw failure
        }
    }

    fun delete(id: String) {
        require(SNAPSHOT_ID.matches(id))
        try {
            request("DELETE", "/snapshots/$id", expectedCode = 204)
        } catch (failure: CloudError) {
            if (failure.code == 405) error("当前 Worker 不支持删除快照，请部署新版 Worker")
            throw failure
        }
    }

    fun download(id: String): Snapshot {
        require(SNAPSHOT_ID.matches(id))
        return Snapshot.decode(request("GET", "/snapshots/$id")).also {
            check(it.id == id && it.files.isNotEmpty()) { "云端快照与所选记录不匹配" }
        }
    }

    private fun request(method: String, path: String, body: String? = null, expectedCode: Int = 200): String {
        val connection = URL(base.toString().trimEnd('/') + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "SennaarSaveSync/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toByteArray()
                require(bytes.size <= MAX_DOCUMENT)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            check(code !in 200..299 || code == expectedCode) {
                "Worker 返回了意外的状态（HTTP $code），请检查 Worker 地址及版本"
            }
            if (code != expectedCode) throw CloudError(code)
            if (method != "GET") return ""
            val bytes = connection.inputStream.use { it.readUpTo(MAX_DOCUMENT + 1) }
            require(bytes.size <= MAX_DOCUMENT) { "云端响应太大" }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private class CloudError(val code: Int) : Exception("云端请求失败（HTTP $code）")
}
