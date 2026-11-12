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
5. 再恢复一次同一份快照，这次选择另一个测试槽位。重新打开游戏，确认目标槽位载入备份中的进度，原槽位的进度保持原样。退出游戏后恢复刚生成的回滚快照，确认目标槽位恢复为覆盖前的进度。

存档目录为 `/sdcard/Android/data/com.playdigious.chantsofsennaar.epic/files/Local`。
应用从 `journal_数字.cos`、`places_数字.cos` 及其 `_backup.cos` 文件的数字后缀识别槽位，备份列表只显示存在匹配文件的编号。
单槽位快照默认恢复到原槽位，也可在游戏的槽位 1、2、3 中选择，包括空槽位。界面槽位 1–3 对应文件编号 0–2。恢复会替换目标槽位的存档文件，并保存目标槽位覆盖前的回滚快照。回滚快照写回它记录的槽位，旧版整份快照恢复全部槽位。
快照最多包含 32 个文件，原始数据合计最多 192 KiB。
本地快照存放在应用私有目录，卸载会删除这些本地快照。

## 发布 Android APK

首次发布前，准备一个发布签名密钥库。已有发布密钥时可直接复用，否则可以用 JDK 的 `keytool` 在仓库之外生成。

```sh
keytool -genkeypair -keystore ~/sennaar-release.jks -storetype JKS \
  -alias sennaar -keyalg RSA -keysize 2048 -validity 10000
```

在 GitHub 仓库的 Settings → Secrets and variables → Actions 中添加四个仓库机密。

| 机密 | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 发布签名密钥库的 Base64 内容，可用 `base64 -w 0 ~/sennaar-release.jks` 生成 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 签名密钥密码 |

将工作流推送到 GitHub 后，在包含工作流的提交上创建并推送版本标签。标签格式为 `v主版本.次版本.修订号`，也可省略 `v`。次版本和修订号各最多三位。

```sh
git tag v0.2.0
git push origin v0.2.0
```

GitHub Actions 从标签设置 APK 的版本名和版本号，构建并验证签名后，将 `sennaar-save-sync-0.2.0.apk` 上传到对应的 GitHub Release。后续发布请继续使用同一签名密钥，以便用户覆盖升级。

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
已有部署需要更新 Worker 才能上传按槽位备份的新快照及删除云端快照。更新 Worker 后原有云端快照继续保留。删除云端快照会从共享列表移除该快照，已下载的本地副本仍可在各设备单独管理。
首版面向个人使用，知道该令牌的设备共享同一个存档历史。应用保存 Worker 地址，令牌只保存在内存，进程重启后需重输。

## 两台设备验收

1. A、B 填写同一个 Worker 地址和同步令牌，并各自启动和授权 Shizuku。
2. A 选择一个槽位备份并上传，确认上传后自动刷新列表并显示该快照。B 刷新云端列表，确认显示相同的槽位，下载该快照，再从本地列表恢复。
3. 在 B 打开游戏确认该槽位可继续游玩，且其他槽位的进度保持原样。让 A、B 分别推进并上传新快照，确认云端同时保留两份。
   列表展示上传时间和来源机型。本地快照展示备份时间。
