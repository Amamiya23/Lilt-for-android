# Lilt for Android

使用 Kotlin、Jetpack Compose 和 Media3 开发的原生 QQ 音乐客户端，支持 Android 12（API 31）及以上版本；最低安装版本为 31，编译与目标 SDK 均为 36。界面沿用 Pencil 中的 Lilt 设计，使用 Radix Colors 的绿色与 Slate 中性色阶，图标使用 Tabler Icons。

## 已实现

- 首页每日推荐、QQ 个性化歌曲推荐（如「幸运好歌」「听某歌手的也在听」，标题由服务端提供）、推荐歌单，以及显示前三首的排行榜。
- 歌曲／歌手／专辑／歌单搜索；歌手结果显示单曲与专辑数，可直接播放热门歌曲。
- 专辑页、歌手页（热门歌曲、歌曲与专辑数），多位歌手的歌曲先选择歌手；专辑与歌手页可层层返回。
- 「我的」页显示绿钻／超级会员与到期日，提供已购音乐（默认展示专辑，可切换单曲，可打开专辑曲目）、收藏的专辑、收藏的歌单和自建歌单。
- QQ 与微信扫码登录、昵称头像、我的歌单、云端喜欢状态读取与切换。
- 登录凭据到期后自动续期：请求前续期本地已过期的 musickey，服务端拒绝凭据时续期一次并重试；续期被拒绝时清除登录并提示重新扫码。
- 在线标准、HQ、无损音源请求，按账号权限和音源可用性回退。
- 后台播放、系统媒体控制、进度调整、随机与循环播放；队列显示来源，长按拖动排序，左滑移除。
- 歌曲菜单：下一首播放、添加到自建歌单、喜欢、下载、查看歌手与专辑；播放页另有播放音质与睡眠定时。
- 新建歌单（「我的」页或添加到歌单时，新建后直接加入当前歌曲）；自建歌单页右上角可重命名或删除歌单（删除前确认）。
- 电台续播：队列快播完时追加私人电台歌曲。
- 音量均衡：按 QQ 提供的响度（增益 +4 dB 预增益并受峰值限制）调节音量，只衰减不放大；淡入淡出：播放、暂停和切歌时短暂渐变。
- 在线音频缓存，可设置上限（512 MB 至 8 GB）并清除；下载音质可单独设置。
- 专辑封面播放页；QQ 云端 QRC 逐字歌词，每个字按自身时长点亮；没有 QRC 时回退到按行同步的 LRC，并匹配翻译。
- 最近播放、本地音频导入、系统下载管理；下载完成后自动进入「本地与下载」，应用重启和打开本地页时会恢复本应用历史下载，使用本地 content URI 离线读取。
- 设置中的音质、缓存上限和「外观」使用带选中标记、无投影、细边线分层的锚定下拉菜单；开关支持整行点击。登录入口保留在设置，移除「我的」右上二维码。
- 清除缓存先显示 MD3 确认弹窗；取消或关闭不清理，确认后清除音频与图片缓存，已下载歌曲保留。
- 播放／暂停图形形变；触摸反馈使用图标轻微缩放、文字透明度和控件状态变化，不绘制覆盖整行／整块的按压涟漪。底栏固定图标尺寸、文字字重和布局，选中背景保持绿色 RGB，只做 120 ms 透明度过渡，避免与透明黑直接插色产生灰帧；设置选择只反馈右侧值与箭头，开关只反馈拇指与状态。保留键盘焦点与鼠标悬停提示。
- 播放页顶部跟手下拉，支持短拖回弹、长拖或下甩收起，并保留当前面板直至退出；动效遵循系统动画时长设置。
- 歌单逐曲显示专辑封面，专辑曲目保留序号列表；播放页歌词与队列入口固定为 56 dp，短屏内容可滚动。
- 手机底部导航与宽屏导航栏，浅色和深色主题。
- 首页、搜索、我的分别保留滚动位置（包括首页横向货架）；重复点击当前 Tab 不刷新页面或请求网络。歌单在账号变化、收藏／添加操作成功和手动刷新时同步；已有数据时同步失败保留列表。

直接使用 QQ 音乐接口，不需要另行部署服务端。接口经验来自桌面项目 `../mygo-cloudmusic`。登录凭据使用 Android Keystore 加密，保存在应用的 `noBackupFilesDir`，不写入源码或日志。

微信登录和凭据续期需要一个已登记的客户端设备：应用生成随机的合成设备标识（不读取本机硬件标识），向 QQ 音乐登记 QIMEI 和会话号，并与凭据一起加密保存。

## 构建与安装

使用 Android Studio 的 JDK 21，安装 Android SDK 36，在 `local.properties` 中配置 `sdk.dir`：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
./gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

覆盖安装要求与已安装应用使用同一个调试签名。若环境变量 `ANDROID_SDK_HOME` 指向其他目录，Gradle 会使用那里的 `.android/debug.keystore`，`adb install -r` 会因签名不一致失败；构建前将它指向包含 `.android` 的用户目录即可，不要为此卸载应用。`assembleRelease` 生成经过压缩的未签名 release APK；正式发布前需要配置发布签名。

## 验证

2026-11-11 已购音乐回归：31 项单元测试、Android Lint、debug 和未签名 release 构建通过；4 项 instrumentation 测试覆盖分类切换、专辑详情返回及既有导航。Android 16 模拟器上的真实账号读取到 29 首已购单曲、14 张已购专辑，验证专辑标题与曲目读取。两类订单分别使用 `fcg_get_order` 的 `type=2/songlist` 和 `type=1/albumlist`，按原始订单数分页；专辑订单兼容 `album_name` 标题，数字专辑 ID 在导航中保留。

2026-11-11 底栏真机复测：在用户 Android 16 手机（1200×2670、520 dpi、1.0 倍字体）录制单击、快速切换、重复点击及滚动后切回；逐帧复现并修正原版灰色选中背景、图标缩放变暗与字重跳变，确认修复后背景不发灰、页面返回保留浏览位置。`BottomNavigationExperienceTest` 覆盖过渡中间帧和滚动恢复；与既有导航、播放设置合计 7 项 instrumentation 回归通过。debug／release 构建与 Lint 通过。

2026-11-11 体验优化回归（Android 16 模拟器）：

- 28 项单元测试、Android Lint、debug 和未签名 release 构建通过。
- 6 项 instrumentation 回归通过：旧下载恢复、新下载元数据持久化、离线 URI 可读、本地页合并展示；播放页按钮尺寸、面板退出状态、短拖回弹／长拖收起、设置下拉与整行开关、既有导航返回。
- 360×640 dp、1.3 倍字体且系统关闭动画时，3 项播放／设置交互回归通过；检查手机与平板横屏、浅深色外观截图。

下载由系统 DownloadManager 执行；`data/DownloadLibrary.kt` 查询本应用成功的下载并合并入本地曲库，不扫描其他应用的下载。新下载保存封面等来源信息；旧下载从系统的曲名与描述恢复。下载回归使用离线文件注册系统下载记录，不依赖 QQ 账号或网络传输。

2026-11-10 在 Android 16 模拟器完成：

- 28 项单元测试通过，包括桌面项目录制的 4 份 QRC 密文样本、逐字解析、微信状态与登录响应、QIMEI 签名与加密结构、过期凭据的保存与续期条件。
- Android Lint、debug 和未签名 release 构建通过。
- 7 项 instrumentation 测试通过：真实账号的资料、歌单、每日推荐、收藏读取与三种音质实际播放；《晴天》云端 QRC 解出 63 行、602 个逐字单元；微信登录完成设备登记并取得可扫描的二维码；以及导航、队列和后台播放。
- 真实账号界面检查：推荐歌曲与排行榜前三首、会员与到期日、逐字歌词点亮、歌曲菜单、歌手与专辑页及返回；开启音量均衡后系统音轨音量为 −4.2 dB。
- 微信扫码确认需要真人操作，尚未由测试覆盖；凭据续期的服务端路径与桌面端一致，尚未在真实过期凭据上验证。

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

设计稿中以下部分暂未实现，原因是桌面端没有可复用的接口或安卓端没有对应条件：

- 歌词搜索、收藏歌单（桌面端只有取消收藏）、从这首歌开始电台、评论、锁屏歌词、检查更新。
- 设计稿使用 Noto Sans SC 和背景模糊玻璃；安卓端使用系统字体，玻璃效果以半透明表面近似，播放页使用 Android 12+ 原生背景模糊。
- 搜索只返回第一页。
- 音乐内容、地区限制、会员与播放权限由 QQ 音乐接口决定。

## 代码

- `data/`：QQ 音乐协议、数据解析和加密会话。
- `playback/`：Media3 服务、播放控制与队列。
- `ui/`：Compose 页面、组件、底部面板和设计稿主题。
- `LiltViewModel.kt`：导航、页面状态和业务交互。

项目许可证见 [LICENSE](LICENSE)；Tabler Icons 使用 MIT 许可证，原文保存在 `app/src/main/assets/licenses/tabler-icons.txt`。QRC 解密使用的 DES 表来自 Go 标准库，BSD 许可证原文保存在 `app/src/main/assets/licenses/go-des.txt`。设置中的开源许可包含依赖与组件，并单独致谢 [mygo-qyin](https://github.com/Amamiya23/mygo-qyin) 和 [qq-music-api](https://github.com/Rain120/qq-music-api) 两个参考项目。
