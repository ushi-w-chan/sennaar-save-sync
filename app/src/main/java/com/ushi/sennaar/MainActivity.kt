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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
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

internal data class LocalItem(
    val id: String,
    val timestamp: Instant,
    val device: String,
    val slot: String?,
    val rollback: Boolean,
    val contentHash: String,
)

class SaveModel(app: Application) : AndroidViewModel(app) {
    private val store = File(app.filesDir, "snapshots").apply { mkdirs() }
    private val preferences = app.getSharedPreferences("cloud", 0)
    private val device = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(app, SaveService::class.java))
        .daemon(false).processNameSuffix("save").version(2)
    private var service by mutableStateOf<ISaveService?>(null)
    private var binding = false
    var access by mutableStateOf("等待 Shizuku")
        private set
    val ready get() = service != null
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    internal var messageInCloud by mutableStateOf(false)
        private set
    var endpoint by mutableStateOf(preferences.getString("endpoint", "").orEmpty())
    var token by mutableStateOf("")
    internal var local by mutableStateOf<List<LocalItem>>(emptyList())
        private set
    internal var cloud by mutableStateOf<List<CloudItem>>(emptyList())
        private set
    internal var cloudLoaded by mutableStateOf(false)
        private set
    internal var pendingBackupSlots by mutableStateOf<List<String>>(emptyList())
        private set
    internal var pendingRestore by mutableStateOf<LocalItem?>(null)
    internal var pendingLocalDelete by mutableStateOf<LocalItem?>(null)
    internal var pendingCloudDelete by mutableStateOf<CloudItem?>(null)

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

    fun chooseBackupSlot() {
        val remote = service ?: return
        perform("") {
            val slots = withContext(Dispatchers.IO) { remote.listSlots().toList() }
            check(slots.isNotEmpty()) { "游戏目录中没有可备份的存档" }
            pendingBackupSlots = slots
        }
    }

    fun dismissBackupSlots() {
        pendingBackupSlots = emptyList()
    }

    fun backup(slot: String) {
        dismissBackupSlots()
        val remote = service ?: return
        perform("本地备份已保存") {
            withContext(Dispatchers.IO) {
                val snapshot = Snapshot.decode(remote.capture(device, slot))
                check(snapshot.files.isNotEmpty()) { "该槽位没有可备份的存档" }
                save(snapshot)
            }
            refreshLocal()
        }
    }

    internal fun restore(item: LocalItem, destination: String?) {
        pendingRestore = null
        val remote = service ?: return
        perform("存档已恢复并通过校验，可以打开游戏") {
            try {
                withContext(Dispatchers.IO) {
                    val snapshot = load(item.id, item.rollback)
                    val target = snapshot.forRestoreSlot(if (item.rollback) snapshot.slot else destination)
                    val previous = Snapshot.decode(remote.capture(device, target.slot))
                    save(previous, rollback = true)
                    remote.restore(target.encode(), previous.fingerprint)
                }
            } finally {
                refreshLocal()
            }
        }
    }

    fun clearCloud() {
        cloud = emptyList()
        cloudLoaded = false
        if (messageInCloud) message = ""
    }

    fun refreshCloud() = withCloud("云端列表已刷新") { client ->
        cloud = withContext(Dispatchers.IO) { client.list() }
        cloudLoaded = true
    }

    internal fun upload(item: LocalItem) = withCloud("快照已上传，已刷新云端列表", showInCloud = false) { client ->
        withContext(Dispatchers.IO) { client.upload(load(item.id, item.rollback)) }
        cloud = withContext(Dispatchers.IO) {
            try {
                client.list()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw IllegalStateException("快照已上传，但云端列表刷新失败：${failure.message}", failure)
            }
        }
        cloudLoaded = true
        check(cloud.any { it.id == item.id }) { "Worker 已接受上传，但云端列表中没有这份快照" }
    }

    internal fun deleteLocal(item: LocalItem) {
        pendingLocalDelete = null
        perform("本地快照已删除") {
            withContext(Dispatchers.IO) {
                val file = snapshotFile(item.id, item.rollback)
                file.delete()
                check(!file.baseFile.exists()) { "无法删除本地快照" }
            }
            refreshLocal()
        }
    }

    internal fun deleteCloud(item: CloudItem) {
        pendingCloudDelete = null
        withCloud("云端快照已删除") { client ->
            withContext(Dispatchers.IO) { client.delete(item.id) }
            cloud = cloud.filterNot { it.id == item.id }
        }
    }

    internal fun download(item: CloudItem) = withCloud("快照已下载，可从本地列表恢复") { client ->
        withContext(Dispatchers.IO) { save(client.download(item.id)) }
        refreshLocal()
    }

    internal suspend fun loadCloudHash(id: String): String {
        val client = Cloud(endpoint.trim(), token.trim())
        return withContext(Dispatchers.IO) { client.download(id).contentHash }
    }

    private fun withCloud(success: String, showInCloud: Boolean = true, action: suspend (Cloud) -> Unit) {
        val address = endpoint.trim()
        val secret = token.trim()
        perform(success, showInCloud) {
            val client = Cloud(address, secret)
            action(client)
            preferences.edit().putString("endpoint", address).apply()
        }
    }

    private fun perform(success: String, showInCloud: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        messageInCloud = showInCloud
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
                    LocalItem(it.id, it.timestamp, it.device, it.slot, rollback, it.contentHash)
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

private val gameSlots = listOf("0", "1", "2")

private fun slotLabel(slot: String?) = when (slot) {
    null -> "整份存档（旧版）"
    in gameSlots -> "槽位 ${gameSlots.indexOf(slot) + 1}"
    else -> "槽位编号 $slot"
}

@Composable
private fun SaveScreen(model: SaveModel) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("巴别塔圣歌存档", style = MaterialTheme.typography.headlineMedium)
            Text("Epic 安卓版", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Text(model.access)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { model.connect() }, enabled = !model.busy) { Text("连接 Shizuku") }
                Button(onClick = model::chooseBackupSlot, enabled = model.ready && !model.busy) {
                    Text("按槽位备份")
                }
            }
            if (!model.messageInCloud) {
                if (model.busy) Text("处理中…")
                if (model.message.isNotBlank()) Text(model.message)
            }
        }
        item {
            HorizontalDivider()
            Text("本地快照", style = MaterialTheme.typography.titleLarge)
            if (model.local.isEmpty()) Text("暂无备份")
        }
        items(model.local, key = { "local-${it.id}" }) { item ->
            Column {
                Text("${dateFormat.format(item.timestamp)} · ${item.device}")
                Text(slotLabel(item.slot))
                SnapshotHash(item.contentHash)
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
                    TextButton(onClick = { model.pendingLocalDelete = item }, enabled = !model.busy) {
                        Text("删除")
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
            if (model.messageInCloud) {
                if (model.busy) Text("处理中…")
                if (model.message.isNotBlank()) Text(model.message)
            }
            if (model.cloudLoaded) {
                Text(if (model.cloud.isEmpty()) "暂无云端快照" else "${model.cloud.size} 份云端快照")
            }
        }
        items(model.cloud, key = { "cloud-${it.id}" }) { item ->
            Column {
                Text("${dateFormat.format(item.timestamp)} · ${item.device}")
                Text(slotLabel(item.slot))
                if (item.contentHash != null) {
                    SnapshotHash(item.contentHash)
                } else {
                    val hash by produceState<String?>(null, item) {
                        value = try {
                            model.loadCloudHash(item.id)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ""
                        }
                    }
                    when (val result = hash) {
                        null -> Text("正在读取 SHA-256…", style = MaterialTheme.typography.bodySmall)
                        "" -> Text("无法读取 SHA-256", style = MaterialTheme.typography.bodySmall)
                        else -> SnapshotHash(result)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.download(item) }, enabled = !model.busy) { Text("下载到本地") }
                    TextButton(onClick = { model.pendingCloudDelete = item }, enabled = !model.busy) {
                        Text("删除")
                    }
                }
            }
        }
    }
    if (model.pendingBackupSlots.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = model::dismissBackupSlots,
            title = { Text("选择要备份的槽位") },
            text = {
                Column(modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    model.pendingBackupSlots.forEach { slot ->
                        TextButton(onClick = { model.backup(slot) }, enabled = model.ready && !model.busy) {
                            Text(slotLabel(slot))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = model::dismissBackupSlots) { Text("取消") } },
        )
    }
    model.pendingRestore?.let { item ->
        val canChooseSlot = item.slot != null && !item.rollback
        var destination by rememberSaveable(item.id, item.rollback) {
            mutableStateOf(item.slot?.takeIf { !canChooseSlot || it in gameSlots })
        }
        val scope = when {
            item.slot == null -> "全部槽位"
            destination != null -> slotLabel(destination)
            else -> "目标槽位"
        }
        AlertDialog(
            onDismissRequest = { model.pendingRestore = null },
            title = { Text("恢复此快照？") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (canChooseSlot) {
                        Text("目标槽位（备份来自${slotLabel(item.slot)}）")
                        Column(Modifier.selectableGroup()) {
                            gameSlots.forEach { slot ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().selectable(
                                        selected = destination == slot,
                                        onClick = { destination = slot },
                                        role = Role.RadioButton,
                                    ).padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    RadioButton(selected = destination == slot, onClick = null)
                                    Text(slotLabel(slot))
                                }
                            }
                        }
                    }
                    Text("请先退出游戏。恢复会替换${scope}的存档文件，并在本机保留恢复前的快照。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { model.restore(item, if (canChooseSlot) destination else item.slot) },
                    enabled = model.ready && !model.busy && (!canChooseSlot || destination != null),
                ) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { model.pendingRestore = null }) { Text("取消") } },
        )
    }
    model.pendingLocalDelete?.let { item ->
        DeleteSnapshotDialog(
            title = "删除本地快照？",
            details = "${slotLabel(item.slot)} · ${dateFormat.format(item.timestamp)}\n仅删除此 App 中保存的副本。",
            onDismiss = { model.pendingLocalDelete = null },
            onDelete = { model.deleteLocal(item) },
        )
    }
    model.pendingCloudDelete?.let { item ->
        DeleteSnapshotDialog(
            title = "删除云端快照？",
            details = "${slotLabel(item.slot)} · ${dateFormat.format(item.timestamp)}\n删除后，其他设备也无法再下载此快照。",
            onDismiss = { model.pendingCloudDelete = null },
            onDelete = { model.deleteCloud(item) },
        )
    }
}

@Composable
private fun SnapshotHash(hash: String) {
    SelectionContainer {
        Text("SHA-256\n$hash", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeleteSnapshotDialog(title: String, details: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(details) },
        confirmButton = { TextButton(onClick = onDelete) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
