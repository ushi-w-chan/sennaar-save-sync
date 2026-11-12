package com.ushi.sennaar

import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal const val GAME = "com.playdigious.chantsofsennaar.epic"
internal const val MAX_BYTES = 192 * 1024
internal const val MAX_DOCUMENT = 320 * 1024
internal val SAVE_NAME = Regex("(journal|places)_[0-9]+(_backup)?\\.cos")
internal val SNAPSHOT_ID = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

internal fun sha256(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal data class Snapshot(
    val id: String,
    val timestamp: Instant,
    val device: String,
    val files: Map<String, ByteArray>,
) {
    val fingerprint: String
        get() = files.toSortedMap().entries.joinToString("\n") { (name, bytes) ->
            "$name:${sha256(bytes)}"
        }

    fun encode(): String {
        val entries = JSONObject()
        files.toSortedMap().forEach { (name, bytes) ->
            entries.put(name, JSONObject()
                .put("sha256", sha256(bytes))
                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        return JSONObject().put("version", 1).put("package", GAME)
            .put("id", id).put("timestamp", timestamp.toString())
            .put("device", device).put("files", entries).toString()
    }

    companion object {
        fun create(device: String, files: Map<String, ByteArray>) =
            Snapshot(UUID.randomUUID().toString(), Instant.now(), device.take(100), files)

        fun decode(document: String): Snapshot {
            require(document.length <= MAX_DOCUMENT) { "快照太大" }
            val root = JSONObject(document)
            require(root.getInt("version") == 1 && root.getString("package") == GAME) { "快照格式不匹配" }
            val id = root.getString("id")
            require(SNAPSHOT_ID.matches(id)) { "无效的快照 ID" }
            val entries = root.getJSONObject("files")
            require(entries.length() <= 32) { "存档文件过多" }
            var total = 0
            val files = entries.keys().asSequence().associateWith { name ->
                require(SAVE_NAME.matches(name)) { "无效的存档文件名" }
                val entry = entries.getJSONObject(name)
                val bytes = Base64.decode(entry.getString("data"), Base64.NO_WRAP)
                total += bytes.size
                require(total <= MAX_BYTES && sha256(bytes) == entry.getString("sha256")) { "存档校验失败" }
                bytes
            }
            return Snapshot(id, Instant.parse(root.getString("timestamp")), root.getString("device").take(100), files)
        }
    }
}

internal fun java.io.InputStream.readUpTo(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (result.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - result.size()))
        if (count == -1) break
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}
