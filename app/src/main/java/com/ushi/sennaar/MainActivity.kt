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
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    val contentHash: String,
    val progress: List<SaveProgress>,
)

internal data class CloudDetails(
    val contentHash: String,
    val timestamp: Instant,
    val progress: List<SaveProgress>,
)

internal data class CurrentSave(val slot: String, val progress: List<SaveProgress>)

internal data class PendingOverwrite(val target: Snapshot, val fingerprint: String)

class SaveModel(app: Application) : AndroidViewModel(app) {
    private val store = File(app.filesDir, "snapshots").apply { mkdirs() }
    private val cloudDetails = mutableMapOf<CloudItem, CloudDetails>()
    private val cloudSnapshots = android.util.LruCache<CloudItem, Snapshot>(16)
    private val cloudReads = mutableMapOf<CloudItem, Deferred<Snapshot>>()
    private val credentials = SyncCredentials(app.getSharedPreferences("cloud", 0))
    private val device = "${Build.MANUFACTURER} ${Build.MODEL}"

    internal fun isLocalDevice(source: String) = source == device.take(100)
    private val serviceArgs = Shizuku.UserServiceArgs(ComponentName(app, SaveService::class.java))
        .daemon(false).processNameSuffix("save").version(3)
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
    var endpoint by mutableStateOf(credentials.endpoint)
    var token by mutableStateOf(credentials.readToken())
    internal var local by mutableStateOf<List<LocalItem>>(emptyList())
        private set
    internal var cloud by mutableStateOf<List<CloudItem>>(emptyList())
        private set
    internal var cloudLoaded by mutableStateOf(false)
        private set
    internal var currentSaves by mutableStateOf<List<CurrentSave>?>(null)
        private set
    internal var pendingOverwrite by mutableStateOf<PendingOverwrite?>(null)
        private set
    internal var pendingRestore by mutableStateOf<LocalItem?>(null)
    internal var pendingLocalDelete by mutableStateOf<LocalItem?>(null)
    internal var pendingCloudDelete by mutableStateOf<CloudItem?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ISaveService.Stub.asInterface(binder)
            access = "Shizuku 已连接"
            refreshCurrentSaves()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            currentSaves = null
            binding = false
            access = "存档服务已断开，可重新连接"
        }
    }
    private val received = Shizuku.OnBinderReceivedListener { connect(false) }
    private val died = Shizuku.OnBinderDeadListener {
        service = null
        currentSaves = null
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
        perform("") {
            refreshLocal()
            service?.let { readCurrentSaves(it) }
        }
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

    fun refreshCurrentSaves() {
        val remote = service ?: return
        perform("") { readCurrentSaves(remote) }
    }

    private suspend fun readCurrentSaves(remote: ISaveService) {
        val saves = withContext(Dispatchers.IO) {
            remote.listSlots().mapNotNull { slot ->
                val snapshot = Snapshot.decode(remote.capture(device, slot))
                snapshot.takeIf { it.files.isNotEmpty() }?.let { CurrentSave(slot, it.progress()) }
            }
        }
        if (service === remote) currentSaves = saves
    }

    fun backup(slot: String) {
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

    internal fun prepareRestore(item: LocalItem, destination: String?) {
        pendingRestore = null
        val remote = service ?: return
        perform("") {
            val (target, previous) = withContext(Dispatchers.IO) {
                val target = load(item.id).forRestoreSlot(destination)
                target to Snapshot.decode(remote.capture(device, target.slot))
            }
            val overwrite = PendingOverwrite(target, previous.fingerprint)
            if (previous.files.isEmpty()) {
                restore(remote, overwrite)
            } else {
                pendingOverwrite = overwrite
            }
        }
    }

    fun cancelOverwrite() {
        pendingOverwrite = null
    }

    fun confirmOverwrite() {
        val overwrite = pendingOverwrite ?: return
        val remote = service ?: return
        cancelOverwrite()
        perform("") { restore(remote, overwrite) }
    }

    private suspend fun restore(remote: ISaveService, overwrite: PendingOverwrite) {
        withContext(Dispatchers.IO) {
            remote.restore(overwrite.target.encode(), overwrite.fingerprint)
        }
        message = RESTORE_SUCCESS
        try {
            readCurrentSaves(remote)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentSaves = null
            message = "$RESTORE_SUCCESS。刷新槽位失败：${failure.message}"
        }
    }

    private fun updateCloud(items: List<CloudItem>) {
        val remaining = items.toSet()
        cloudDetails.keys.retainAll(remaining)
        for (item in cloudSnapshots.snapshot().keys - remaining) cloudSnapshots.remove(item)
        for (item in cloudReads.keys.toSet() - remaining) cloudReads.remove(item)?.cancel()
        cloud = items
    }

    fun clearCloud() {
        updateCloud(emptyList())
        cloudLoaded = false
        if (messageInCloud) message = ""
    }

    fun refreshCloud() = withCloud("云端列表已刷新") { client ->
        updateCloud(withContext(Dispatchers.IO) { client.list() })
        cloudLoaded = true
    }

    internal fun upload(item: LocalItem) = withCloud("快照已上传，已刷新云端列表", showInCloud = false) { client ->
        val snapshot = withContext(Dispatchers.IO) { load(item.id).also(client::upload) }
        val items = withContext(Dispatchers.IO) {
            try {
                client.list()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw IllegalStateException("快照已上传，但云端列表刷新失败：${failure.message}", failure)
            }
        }
        val uploaded = checkNotNull(items.find { it.id == item.id }) {
            "Worker 已接受上传，但云端列表中没有这份快照"
        }
        val details = summarizeCloudSnapshot(uploaded, snapshot)
        updateCloud(items)
        cloudSnapshots.put(uploaded, snapshot)
        cloudDetails[uploaded] = details
        cloudLoaded = true
    }

    internal fun deleteLocal(item: LocalItem) {
        pendingLocalDelete = null
        perform("本地快照已删除") {
            withContext(Dispatchers.IO) {
                val file = snapshotFile(item.id)
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
            updateCloud(cloud.filterNot { it.id == item.id })
        }
    }

    internal fun download(item: CloudItem) = withCloud("快照已下载，可从本地列表恢复") { client ->
        val snapshot = readCloudSnapshot(item, client)
        withContext(Dispatchers.IO) { save(snapshot) }
        refreshLocal()
    }

    internal suspend fun loadCloudDetails(item: CloudItem): CloudDetails {
        cloudDetails[item]?.let { return it }
        readCloudSnapshot(item, Cloud(endpoint.trim(), token.trim()))
        return checkNotNull(cloudDetails[item]) { "云端快照列表已变化，请重新读取" }
    }

    private suspend fun summarizeCloudSnapshot(item: CloudItem, snapshot: Snapshot) =
        withContext(Dispatchers.IO) {
            CloudDetails(item.contentHash ?: snapshot.contentHash, snapshot.timestamp, snapshot.progress())
        }

    private suspend fun readCloudSnapshot(item: CloudItem, client: Cloud): Snapshot {
        cloudSnapshots.get(item)?.let { return it }
        val read = cloudReads.getOrPut(item) {
            viewModelScope.async(start = CoroutineStart.LAZY) {
                try {
                    val snapshot = withContext(Dispatchers.IO) { client.download(item.id) }
                    val details = summarizeCloudSnapshot(item, snapshot)
                    if (item in cloud) {
                        cloudSnapshots.put(item, snapshot)
                        cloudDetails[item] = details
                    }
                    snapshot
                } finally {
                    if (cloudReads[item] === coroutineContext[Job]) cloudReads.remove(item)
                }
            }
        }
        return read.await()
    }

    private fun withCloud(success: String, showInCloud: Boolean = true, action: suspend (Cloud) -> Unit) {
        val address = endpoint.trim()
        val secret = token.trim()
        perform("", showInCloud) {
            val client = Cloud(address, secret)
            action(client)
            message = try {
                withContext(Dispatchers.IO) { credentials.save(address, secret) }
                success
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "$success。同步令牌未能保存"
            }
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
                if (success.isNotEmpty()) message = success
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
                val id = file.nameWithoutExtension
                if (!SNAPSHOT_ID.matches(id)) return@mapNotNull null
                runCatching { load(id) }.getOrNull()?.let {
                    LocalItem(it.id, it.timestamp, it.device, it.slot, it.contentHash, it.progress())
                }
            }.sortedByDescending { it.timestamp }
        }
    }

    private fun snapshotFile(id: String): AtomicFile {
        require(SNAPSHOT_ID.matches(id))
        return AtomicFile(File(store, "$id.json"))
    }

    private fun load(id: String) =
        Snapshot.decode(snapshotFile(id).readFully().toString(Charsets.UTF_8)).also {
            check(it.id == id) { "本地快照与所选记录不匹配" }
        }

    private fun save(snapshot: Snapshot) {
        val file = snapshotFile(snapshot.id)
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

private const val RESTORE_SUCCESS = "存档已恢复并通过校验，可以打开游戏"

private val gameSlots = listOf("0", "1", "2")

private fun slotLabel(slot: String?) = when (slot) {
    null -> "整份存档（旧版）"
    in gameSlots -> "槽位 ${gameSlots.indexOf(slot) + 1}"
    else -> "槽位编号 $slot"
}

@Composable
private fun SaveScreen(model: SaveModel) {
    val local = model.local
    val localByHash = remember(local) { local.associateBy { it.contentHash } }
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
            }
            if (!model.messageInCloud) {
                if (model.busy) Text("处理中…")
                if (model.message.isNotBlank()) Text(model.message)
            }
        }
        item {
            HorizontalDivider()
            Text("当前游戏存档", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = model::refreshCurrentSaves, enabled = model.ready && !model.busy) {
                Text("刷新槽位")
            }
            if (!model.ready) Text("连接 Shizuku 后可读取存档")
        }
        model.currentSaves?.let { saves ->
            val bySlot = saves.associateBy { it.slot }
            items((gameSlots + bySlot.keys).distinct(), key = { "current-$it" }) { slot ->
                Column {
                    Text(slotLabel(slot))
                    val save = bySlot[slot]
                    if (save == null) {
                        Text("空槽位", style = MaterialTheme.typography.bodySmall)
                    } else {
                        SnapshotProgress(save.progress, slot)
                        TextButton(onClick = { model.backup(slot) }, enabled = model.ready && !model.busy) {
                            Text("备份到本地")
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("本地快照", style = MaterialTheme.typography.titleLarge)
            if (model.local.isEmpty()) Text("暂无备份")
        }
        items(model.local, key = { "local-${it.id}" }) { item ->
            Column {
                SnapshotSource(item.timestamp, item.device, model.isLocalDevice(item.device))
                Text(slotLabel(item.slot))
                SnapshotHash(item.contentHash)
                SnapshotProgress(item.progress, item.slot)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.pendingRestore = item }, enabled = model.ready && !model.busy) {
                        Text("恢复")
                    }
                    TextButton(onClick = { model.upload(item) }, enabled = !model.busy && model.token.isNotBlank()) {
                        Text("上传")
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
                SnapshotSource(item.timestamp, item.device, model.isLocalDevice(item.device))
                Text(slotLabel(item.slot))
                val matchingLocal = item.contentHash?.let(localByHash::get)
                if (matchingLocal != null) {
                    SnapshotHash(matchingLocal.contentHash)
                    SnapshotProgress(matchingLocal.progress, item.slot)
                } else {
                    val details by produceState<Result<CloudDetails>?>(null, item) {
                        value = null
                        value = try {
                            Result.success(model.loadCloudDetails(item))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Result.failure(failure)
                        }
                    }
                    (item.contentHash ?: details?.getOrNull()?.contentHash)?.let { SnapshotHash(it) }
                    val result = details
                    when {
                        result == null -> Text("正在读取存档进度…", style = MaterialTheme.typography.bodySmall)
                        result.isFailure -> Text("无法读取快照详情", style = MaterialTheme.typography.bodySmall)
                        else -> {
                            val loaded = result.getOrThrow()
                            Text("快照创建于 ${dateFormat.format(loaded.timestamp)}",
                                style = MaterialTheme.typography.bodySmall)
                            SnapshotProgress(loaded.progress, item.slot)
                        }
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
    model.pendingRestore?.let { item ->
        val canChooseSlot = item.slot != null
        var destination by rememberSaveable(item.id) {
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
                    Text("请先退出游戏。恢复会替换${scope}的存档文件。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { model.prepareRestore(item, if (canChooseSlot) destination else item.slot) },
                    enabled = model.ready && !model.busy && (!canChooseSlot || destination != null),
                ) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { model.pendingRestore = null }) { Text("取消") } },
        )
    }
    model.pendingOverwrite?.let { overwrite ->
        val destination = overwrite.target.slot?.let(::slotLabel) ?: "游戏存档"
        AlertDialog(
            onDismissRequest = model::cancelOverwrite,
            title = { Text("覆盖已有存档？") },
            text = { Text("${destination}已有存档。继续恢复会覆盖现有进度。") },
            confirmButton = {
                TextButton(onClick = model::confirmOverwrite, enabled = model.ready && !model.busy) {
                    Text("确认覆盖")
                }
            },
            dismissButton = { TextButton(onClick = model::cancelOverwrite) { Text("取消") } },
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
private fun SnapshotSource(timestamp: Instant, device: String, isLocal: Boolean) {
    val localLabel = if (isLocal) " · 本机" else ""
    Text("${dateFormat.format(timestamp)} · $device$localLabel")
}

@Composable
private fun SnapshotProgress(progress: List<SaveProgress>, slot: String?) {
    val style = MaterialTheme.typography.bodySmall
    if (progress.isEmpty()) Text("暂无可读取的存档进度", style = style)
    for (save in progress) {
        if (slot == null) Text(slotLabel(save.slot), style = style)
        save.playSeconds?.let { seconds ->
            Text("游戏时长 ${seconds / 3600} 小时 ${seconds / 60 % 60} 分 ${seconds % 60} 秒", style = style)
        }
        save.scene?.let { Text("保存场景 $it", style = style) }
        save.lastSaved?.let { Text("场景保存于 ${dateFormat.format(it)}", style = style) }
        save.journalSaved?.let { Text("笔记保存于 ${dateFormat.format(it)}", style = style) }
        save.glyphs.forEachIndexed { index, glyphs ->
            Text("语言 ${index + 1} · 已确认 ${glyphs.confirmed}/${glyphs.total} · " +
                "待确认 ${glyphs.seen - glyphs.confirmed}", style = style)
        }
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
