# Lilt for Android

使用 Kotlin、Jetpack Compose 和 Media3 开发的原生 QQ 音乐客户端，支持 Android 8.0 及以上版本。界面依据 Pencil 中的 Lilt 设计稿实现，采用 Radix Colors 和 Tabler Icons。

## 已实现

- 首页推荐、每日推荐、歌单与排行榜、歌曲／歌手／专辑／歌单搜索。
- QQ 扫码登录、昵称头像、我的歌单、云端喜欢状态读取与切换。
- 在线标准、HQ、无损音源请求，按账号权限和音源可用性回退。
- 后台播放、系统媒体控制、播放队列、进度调整、随机与循环播放。
- 专辑封面播放页、按行同步的 LRC 歌词。
- 最近播放、本地音频导入、系统下载管理、主题与图片缓存设置。
- 手机底部导航与宽屏导航栏，浅色和深色主题。

直接使用 QQ 音乐接口，不需要另行部署服务端。接口经验来自桌面项目 `../mygo-cloudmusic`。登录凭据使用 Android Keystore 加密，保存在应用的 `noBackupFilesDir`，不写入源码或日志。

## 构建与安装

使用 Android Studio 的 JDK 21，安装 Android SDK 36，在 `local.properties` 中配置 `sdk.dir`：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。`assembleRelease` 生成经过压缩的未签名 release APK；正式发布前需要配置发布签名。

## 验证

2026-11-10 在 Android 16 模拟器完成：

- 10 项解析单元测试、Android Lint、debug 和未签名 release 构建通过。
- 5 项 instrumentation 测试通过，覆盖真实账号的每日推荐、歌单、收藏读取、歌词与三种音质的实际播放，以及导航、队列和后台播放。
- 最后一次导航修正后，2 项导航用例再次通过。
- 原生截图检查覆盖手机／平板横竖屏、浅色／深色，以及 1.3 倍系统字体。设计预览用于视觉比较，账号测试使用真实接口。

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

真实账号测试需要先启动应用，通过手机 QQ 扫码确认。测试只读取云端歌单和收藏状态，并在 Android 播放器中验证标准、HQ、无损音源。不会修改云端收藏。

账号登录后，使用覆盖安装和手动 instrumentation 保留登录状态：

```powershell
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e authenticated true -e live true com.lilt.app.test/androidx.test.runner.AndroidJUnitRunner
```

不要在真实账号会话中使用可能卸载目标应用的测试流程。卸载会删除应用登录状态；`am force-stop` 和 `install -r` 会保留数据。

播放器命令是异步执行的。测试应等待目标曲目的标识和播放进度，清理队列后等待空队列状态，再关闭连接，避免将上一首的状态当作新请求的结果。

`src/debug` 包含与 Pencil 设计对应的预览数据和图片，仅在显式传入 `design_preview=true` 时使用。正常启动使用真实接口；release 不包含这些预览资源。

## 当前边界

- 支持 QQ 扫码；微信登录所需的设备初始化流程尚未移植。
- 使用 LRC 按行同步，尚未实现 QRC 逐字解密与同步。
- 登录过期后需要重新扫码，尚未实现桌面端的凭据自动续期。
- 搜索目前返回第一页，尚未实现分页；专辑、歌手搜索结果进入关联歌曲搜索。
- 尚未覆盖桌面端全部能力，例如已购专辑、收藏专辑、响度均衡、交叉淡入淡出和自动更新。
- 音乐内容、地区限制、会员与播放权限由 QQ 音乐接口决定。

## 代码

- `data/`：QQ 音乐协议、数据解析和加密会话。
- `playback/`：Media3 服务、播放控制与队列。
- `ui/`：Compose 页面、组件和 Radix 主题。
- `LiltViewModel.kt`：导航、页面状态和业务交互。

项目许可证见 [LICENSE](LICENSE)；Tabler Icons 使用 MIT 许可证，原文保存在 `app/src/main/assets/licenses/tabler-icons.txt`。
