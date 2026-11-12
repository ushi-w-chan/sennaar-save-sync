package com.ushi.sennaar

import org.json.JSONObject
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection

internal data class CloudItem(val id: String, val timestamp: Instant, val device: String, val slot: String?)

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
                )
            }
            cursor = if (page.isNull("cursor")) null else page.getString("cursor")
            check(cursor == null || seen.add(cursor)) { "云端列表分页异常" }
        } while (cursor != null)
        return items.sortedByDescending { it.timestamp }
    }

    fun upload(snapshot: Snapshot) {
        try {
            request("PUT", "/snapshots/${snapshot.id}", snapshot.encode())
        } catch (failure: CloudError) {
            if (failure.code != 412 || download(snapshot.id).encode() != snapshot.encode()) throw failure
        }
    }

    fun download(id: String): Snapshot {
        require(SNAPSHOT_ID.matches(id))
        return Snapshot.decode(request("GET", "/snapshots/$id")).also {
            check(it.id == id && it.files.isNotEmpty()) { "云端快照与所选记录不匹配" }
        }
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = URL(base.toString().trimEnd('/') + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
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
            if (code !in 200..299) throw CloudError(code)
            if (method == "PUT") return ""
            val bytes = connection.inputStream.use { it.readUpTo(MAX_DOCUMENT + 1) }
            require(bytes.size <= MAX_DOCUMENT) { "云端响应太大" }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private class CloudError(val code: Int) : Exception("云端请求失败（HTTP $code）")
}
