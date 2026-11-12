# 巴别塔圣歌存档同步

Kotlin / Jetpack Compose 应用，支持 Android 11+ 上的《巴别塔圣歌》Epic 安卓版
`com.playdigious.chantsofsennaar.epic`。

## 构建与真机测试

使用 JDK 17 和 Android SDK 36。设置 `ANDROID_HOME`，或在 Android Studio 中打开项目。

```sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 安装 [Shizuku](https://shizuku.rikka.app/zh-hans/download/)，通过无线调试启动服务。
2. 在游戏里准备两个测试存档并退出。打开本 App，点击「连接 Shizuku」并授权，再点击「按槽位备份」，选择其中一个槽位。
3. 确认本地快照出现后，在游戏里删除已备份的测试存档并退出。在本 App 恢复该快照，重新打开游戏，确认该槽位可以继续游玩，另一个槽位的进度保持原样。
4. 恢复前会停止游戏，并在 App 私有目录中保留恢复前的快照。如果发生断电、服务退出等中断，可重新连接并从回滚快照恢复。

存档目录为 `/sdcard/Android/data/com.playdigious.chantsofsennaar.epic/files/Local`。
应用从 `journal_数字.cos`、`places_数字.cos` 及其 `_backup.cos` 文件的数字后缀识别槽位，选择列表只显示存在匹配文件的编号。
新快照只包含所选槽位的文件。恢复时按原编号写回，并删除该槽位中快照未包含的匹配文件。旧版整份快照仍可恢复，恢复范围为全部槽位。
快照最多包含 32 个文件，原始数据合计最多 192 KiB。
本地快照存放在应用私有目录，卸载会删除这些本地快照。

## 部署 Cloudflare Worker

开通 Cloudflare R2 并登录 Wrangler。下面的 Bucket 名称与 `worker/wrangler.toml` 一致，可按需修改两处。

```sh
cd worker
npx wrangler login
npx wrangler r2 bucket create sennaar-saves
npx wrangler deploy
npx wrangler secret put SYNC_TOKEN
```

用 `openssl rand -hex 32` 生成同步令牌，通过 Wrangler 的交互输入设置。保持 Bucket 私有。
Worker 使用原生 R2 binding，Android App 只需要 Worker 的 HTTPS 地址和同步令牌。
已有部署需要更新 Worker 才能上传按槽位备份的新快照。下载和恢复这类快照的设备也需要新版 App。原有云端快照继续保留。
首版面向个人使用，知道该令牌的设备共享同一个存档历史。应用保存 Worker 地址，令牌只保存在内存，进程重启后需重输。

## 两台设备验收

1. A、B 填写同一个 Worker 地址和同步令牌，并各自启动和授权 Shizuku。
2. A 选择一个槽位备份并上传。B 刷新云端列表，确认显示相同的槽位编号，下载该快照，再从本地列表恢复。
3. 在 B 打开游戏确认该槽位可继续游玩，且其他槽位的进度保持原样。让 A、B 分别推进并上传新快照，确认云端同时保留两份。
   列表展示上传时间和来源机型。本地快照展示备份时间。
