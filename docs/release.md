# 稳定版发布

只通过公开的 GitHub Release 分发 APK。设置页从最新稳定版 Release 读取 `update.json` 和 `update.sig`，校验后下载 `Lilt.apk`。不要将私钥、keystore 或密码提交到仓库。

应用启动时自动检查，成功检查之间至少间隔 24 小时；手动检查不受间隔限制。发现更新后仅在设置入口和设置页提示，用户主动下载并通过系统安装器确认安装。自动检查失败不会打断用户，下次启动可重试；不自动下载或在后台发送通知。

## 一次性准备

在仓库外的安全位置生成一套 Android 签名密钥，APK 和更新清单共用它。已经生成 `lilt-release.jks` 的话，无需重新生成：

```powershell
keytool -genkeypair -v -keystore lilt-release.jks -alias lilt -keyalg RSA -keysize 4096 -validity 10000
```

别名固定为 `lilt`，密钥口令须与密钥库口令相同。工作流会在构建前从密钥库提取公钥并嵌入 APK，应用内只包含公钥。无需自行生成或提交公钥；普通本地构建默认没有公钥，因此不能检查更新。公开仓库和 Release 是应用不带凭据访问更新的前提。

将下列值添加到仓库 Settings → Secrets and variables → Actions 的 repository secrets：

| Secret | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 密钥库文件的 Base64 文本 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |

Windows PowerShell 中可在密钥库所在目录运行以下命令，将 Base64 内容复制到剪贴板，然后粘贴为 `ANDROID_KEYSTORE_BASE64`：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\lilt-release.jks))) | Set-Clipboard
```

在 GitHub 之外安全备份密钥库及密码。后续发布必须使用同一个密钥：直接替换会导致已安装的应用无法验证更新清单，也无法覆盖安装。共用密钥意味着它泄露会同时影响 APK 和更新清单的签名。Debug 构建通常使用不同签名，不能被正式 APK 覆盖安装；开发机需要卸载 debug 版后安装正式版，卸载会删除应用数据。

## 发布新版本

1. 修改 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`。`versionName` 使用稳定版 `X.Y.Z`；`versionCode` 每次必须大于上一个正式版。
2. 更新 `RELEASE_NOTES.md`，它同时用于 GitHub Release 和应用内更新说明。
3. 提交这些修改并推送，然后创建与版本完全一致的 tag，例如 `git tag v0.2.0`，再执行 `git push origin v0.2.0`。
4. GitHub Actions 检查 tag 与版本、比较上一个正式版的 `versionCode`、运行单元测试、构建并签署 APK，最后创建草稿并上传三个资源。只有资源全部上传成功后才发布并标记为 Latest。
5. 在 GitHub Actions 和 Release 页面确认结果，并使用正式签名版在设备上验证检查、下载和安装。

如果上传失败，Release 可能仍是草稿；检查原因并删除该草稿后再重新运行工作流。不要更改已发布版本的资源，修复问题应使用更高版本重新发布。可以在 GitHub 仓库设置中启用不可变 Release。

### 使用小号 Actions 构建

当主账号暂时无法运行 Actions 时，可以在 `Amamiya233/Lilt-for-android` 构建，在 `Amamiya23/Lilt-for-android` 发布。小号仓库也需要配置相同的两个 Secrets，并启用 Actions。工作流始终针对主仓库校验版本，并生成指向主仓库的 APK 地址；小号运行只上传名为 `release-assets` 的 Actions artifact，不创建小号 Release。

1. 将相同提交推送到两个仓库，在两个仓库创建指向该提交的版本 tag。主仓库的 Actions 在计费锁定期间可能无法运行，不影响使用小号构建。
2. 等待小号仓库该 tag 对应的 Release 工作流成功。下载该次运行的 artifact，例如：

   ```powershell
   gh run download <运行ID> --repo Amamiya233/Lilt-for-android --name release-assets --dir release-assets
   ```

3. 检查下载的 `Lilt.apk`、`update.json` 和 `update.sig` 完整，再使用对主仓库有发布权限的 GitHub 账号创建主仓库 Release。例如：

   ```powershell
   gh release create v0.2.0 --repo Amamiya23/Lilt-for-android --verify-tag --draft --title v0.2.0 --notes-file RELEASE_NOTES.md release-assets/Lilt.apk release-assets/update.json release-assets/update.sig
   gh release edit v0.2.0 --repo Amamiya23/Lilt-for-android --draft=false --latest
   ```

   发布前确认 tag 指向预期提交、清单里的 APK 地址指向主仓库，并确认 APK 摘要和清单签名。不要修改签名后的清单，否则验证会失败。上述 `v0.2.0` 换成实际版本。

## 清单协议

`update.json` 是 UTF-8 JSON，包含 `versionName`、`versionCode`、`packageName`、`minSdk`、`apkUrl`、`sha256`、`size` 和 `notes`。`update.sig` 是对 `update.json` **原始文件字节**使用 RSA SHA-256 生成的二进制签名；修改换行或重新格式化 JSON 都会使签名失效。`sha256` 是最终签名 APK 的十六进制摘要，`size` 是字节数。
