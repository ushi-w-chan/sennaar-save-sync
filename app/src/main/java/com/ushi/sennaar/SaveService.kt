package com.ushi.sennaar

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

class SaveService : ISaveService.Stub() {
    private val directory = File("/sdcard/Android/data/$GAME/files/Local")

    @Synchronized
    override fun capture(device: String): String {
        val snapshot = Snapshot.create(device, readFiles())
        check(snapshot.fingerprint == Snapshot.create(device, readFiles()).fingerprint) {
            "存档仍在变化，请退出游戏后重试"
        }
        return snapshot.encode()
    }

    @Synchronized
    override fun restore(document: String, expectedFingerprint: String) {
        val target = Snapshot.decode(document)
        stopGame()
        val previous = Snapshot.create("", readFiles())
        check(previous.fingerprint == expectedFingerprint) { "存档已变化，请重新发起恢复" }
        try {
            replaceFiles(target.files)
            check(Snapshot.create("", readFiles()).fingerprint == target.fingerprint) { "写回校验失败" }
        } catch (failure: Exception) {
            try {
                replaceFiles(previous.files)
                check(Snapshot.create("", readFiles()).fingerprint == previous.fingerprint)
            } catch (_: Exception) {
                throw IllegalStateException("恢复和自动回滚均失败，请使用本地回滚快照重试")
            }
            throw IllegalStateException("恢复失败，已回滚：${failure.message}")
        }
    }

    override fun destroy() = exitProcess(0)

    private fun stopGame() {
        val process = ProcessBuilder("/system/bin/am", "force-stop", GAME).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0 && output.isBlank()) { "无法停止游戏" }
    }

    private fun readFiles(): Map<String, ByteArray> {
        if (!directory.exists()) {
            check(directory.parentFile?.list() != null) { "无法访问游戏目录，请确认已安装并启动过 Epic 版" }
            return emptyMap()
        }
        val files = checkNotNull(directory.listFiles()) { "无法读取游戏存档目录" }
            .filter { SAVE_NAME.matches(it.name) }
        check(files.size <= 32) { "存档文件过多" }
        var remaining = MAX_BYTES
        return files.associate { file ->
            val bytes = file.inputStream().use { it.readUpTo(remaining + 1) }
            check(bytes.size <= remaining) { "存档超出大小上限" }
            remaining -= bytes.size
            file.name to bytes
        }
    }

    private fun replaceFiles(files: Map<String, ByteArray>) {
        check(directory.isDirectory || directory.mkdir()) { "无法创建存档目录" }
        val staged = mutableMapOf<String, File>()
        try {
            files.forEach { (name, bytes) ->
                val temporary = File.createTempFile(".sennaar-", ".tmp", directory)
                staged[name] = temporary
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                check(temporary.setReadable(true, false) && temporary.setWritable(true, false)) {
                    "无法设置存档读写权限"
                }
            }
            staged.forEach { (name, file) ->
                Files.move(file.toPath(), File(directory, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            checkNotNull(directory.listFiles()).filter {
                SAVE_NAME.matches(it.name) && it.name !in files
            }.forEach { Files.delete(it.toPath()) }
        } finally {
            staged.values.forEach { it.delete() }
        }
    }
}
