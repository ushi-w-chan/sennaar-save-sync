package com.ushi.sennaar

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val model: SaveModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SaveScreen(model) } }
    }
}

internal data class LocalItem(val id: String, val timestamp: Instant, val device: String, val rollback: Boolean)

class SaveModel(app: Application) : AndroidViewModel(app) {
    private val store = File(app.filesDir, "snapshots").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("cloud", 0)
    private val device = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(app, SaveService::class.java))
        .daemon(false).processNameSuffix("save").version(1)
    private var service by mutableStateOf<ISaveService?>(null)
    private var binding = false
    var access by mutableStateOf("等待 Shizuku")
        private set
    val ready get() = service != null
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var endpoint by mutableStateOf(preferences.getString("endpoint", "").orEmpty())
    var token by mutableStateOf("")
    internal var local by mutableStateOf<List<LocalItem>>(emptyList())
        private set
    internal var cloud by mutableStateOf<List<CloudItem>>(emptyList())
        private set
    internal var pendingRestore by mutableStateOf<LocalItem?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ISaveService.Stub.asInterface(binder)
            access = "Shizuku 已连接"
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            access = "存档服务已断开，可重新连接"
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { connect(false) }
    private val died = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        access = "请启动 Shizuku"
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request == 1 && result == PackageManager.PERMISSION_GRANTED) connect(false)
        else if (request == 1) access = "Shizuku 授权未通过"
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(permission)
        perform("") { refreshLocal() }
    }

    fun connect(ask: Boolean = true) {
        try {
            when {
                !Shizuku.pingBinder() -> access = "请安装并启动 Shizuku"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    access = "需要授权 Shizuku"
                    if (ask) Shizuku.requestPermission(1)
                }
                ready -> access = "Shizuku 已连接"
                !binding -> {
                    access = "正在连接存档服务"
                    Shizuku.bindUserService(serviceArgs, connection)
                    binding = true
                }
            }
        } catch (failure: Exception) {
            binding = false
            access = "连接失败：${failure.message}"
        }
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(died)
        Shizuku.removeRequestPermissionResultListener(permission)
        if (binding) runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
    }

    fun backup() {
        val remote = service ?: return
        perform("本地备份已保存") {
            val snapshot = withContext(Dispatchers.IO) { Snapshot.decode(remote.capture(device)) }
            check(snapshot.files.isNotEmpty()) { "游戏目录中没有可备份的存档" }
            withContext(Dispatchers.IO) { save(snapshot) }
            refreshLocal()
        }
    }

    internal fun restore(item: LocalItem) {
        pendingRestore = null
        val remote = service ?: return
        perform("存档已恢复并通过校验，可以打开游戏") {
            try {
                withContext(Dispatchers.IO) {
                    val target = load(item.id, item.rollback)
                    val previous = Snapshot.decode(remote.capture(device))
                    save(previous, rollback = true)
                    remote.restore(target.encode(), previous.fingerprint)
                }
            } finally {
                refreshLocal()
            }
        }
    }

    fun clearCloud() { cloud = emptyList() }

    fun refreshCloud() = withCloud("云端列表已刷新") { client ->
        cloud = withContext(Dispatchers.IO) { client.list() }
    }

    internal fun upload(item: LocalItem) = withCloud("快照已上传") { client ->
        withContext(Dispatchers.IO) { client.upload(load(item.id, item.rollback)) }
    }

    internal fun download(item: CloudItem) = withCloud("快照已下载，可从本地列表恢复") { client ->
        withContext(Dispatchers.IO) { save(client.download(item.id)) }
        refreshLocal()
    }

    private fun withCloud(success: String, action: suspend (Cloud) -> Unit) {
        val address = endpoint.trim()
        val secret = token.trim()
        perform(success) {
            val client = Cloud(address, secret)
            action(client)
            preferences.edit().putString("endpoint", address).apply()
        }
    }

    private fun perform(success: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        viewModelScope.launch {
            try {
                action()
                message = success
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun refreshLocal() {
        local = withContext(Dispatchers.IO) {
            store.listFiles().orEmpty().mapNotNull { file ->
                if (file.extension != "json") return@mapNotNull null
                val rollback = file.name.startsWith("rollback-")
                val id = file.nameWithoutExtension.removePrefix("rollback-")
                if (!SNAPSHOT_ID.matches(id)) return@mapNotNull null
                runCatching { load(id, rollback) }.getOrNull()?.let {
                    LocalItem(it.id, it.timestamp, it.device, rollback)
                }
            }.sortedByDescending { it.timestamp }
        }
    }

    private fun snapshotFile(id: String, rollback: Boolean): AtomicFile {
        require(SNAPSHOT_ID.matches(id))
        val name = if (rollback) "rollback-$id" else id
        return AtomicFile(File(store, "$name.json"))
    }

    private fun load(id: String, rollback: Boolean) =
        Snapshot.decode(snapshotFile(id, rollback).readFully().toString(Charsets.UTF_8)).also {
            check(it.id == id) { "本地快照与所选记录不匹配" }
        }

    private fun save(snapshot: Snapshot, rollback: Boolean = false) {
        val file = snapshotFile(snapshot.id, rollback)
        val bytes = snapshot.encode().toByteArray()
        if (file.baseFile.exists()) {
            check(file.readFully().contentEquals(bytes)) { "本地已有不同内容的同名快照" }
            return
        }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        check(file.readFully().contentEquals(bytes)) { "本地快照写入校验失败" }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

@Composable
private fun SaveScreen(model: SaveModel) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("圣歌巴别塔存档", style = MaterialTheme.typography.headlineMedium)
            Text("Epic 安卓版", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text(model.access)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.connect() }, enabled = !model.busy) { Text("连接 Shizuku") }
                Button(onClick = model::backup, enabled = model.ready && !model.busy) { Text("备份当前存档") }
            }
            if (model.busy) Text("处理中…")
            if (model.message.isNotBlank()) Text(model.message)
        }
        item {
            HorizontalDivider()
            Text("本地快照", style = MaterialTheme.typography.titleLarge)
            if (model.local.isEmpty()) Text("暂无备份")
        }
        items(model.local, key = { "local-${it.id}" }) { item ->
            Column {
                Text("${dateFormat.format(item.timestamp)} · ${item.device}")
                if (item.rollback) Text("恢复前的回滚快照")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.pendingRestore = item }, enabled = model.ready && !model.busy) {
                        Text("恢复")
                    }
                    if (!item.rollback) {
                        TextButton(onClick = { model.upload(item) }, enabled = !model.busy && model.token.isNotBlank()) {
                            Text("上传")
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("R2 云端快照", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(model.endpoint, { model.endpoint = it; model.clearCloud() },
                label = { Text("Worker HTTPS 地址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !model.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(model.token, { model.token = it; model.clearCloud() },
                label = { Text("同步令牌") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = !model.busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(onClick = model::refreshCloud, enabled = !model.busy && model.token.isNotBlank()) {
                Text("刷新云端列表")
            }
        }
        items(model.cloud, key = { "cloud-${it.id}" }) { item ->
            Column {
                Text("${dateFormat.format(item.timestamp)} · ${item.device}")
                OutlinedButton(onClick = { model.download(item) }, enabled = !model.busy) { Text("下载到本地") }
            }
        }
    }
    model.pendingRestore?.let { item ->
        AlertDialog(
            onDismissRequest = { model.pendingRestore = null },
            title = { Text("恢复此快照？") },
            text = { Text("请先退出游戏。恢复会替换游戏当前的存档文件，并在本机保留恢复前的快照。") },
            confirmButton = { TextButton(onClick = { model.restore(item) }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { model.pendingRestore = null }) { Text("取消") } },
        )
    }
}
