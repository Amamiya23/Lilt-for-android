# Lilt for Android

使用 Kotlin 的 Lilt 安卓端，支持 Android 12（API 31）及以上版本。
> 为了更贴合移动端体验，所以和桌面版Lilt有很大出入。后续可能考虑统一跨平台开发方式。

## 已实现

- 首页每日推荐、个性化歌曲推荐
- 歌曲／歌手／专辑／歌单搜索
- 专辑页、歌手页（热门歌曲、歌曲与专辑数），多位歌手的歌曲先选择歌手；专辑与歌手页可层层返回。
- 提供已购音乐、收藏的专辑、收藏的歌单和自建歌单。
- 账号扫码登录、昵称头像、我的歌单、云端喜欢状态读取与切换。
- 登录凭据到期后自动续期
- 在线音源请求
- 音量均衡；淡入淡出

## 代码

- `data/`：QQ 音乐协议、数据解析和加密会话。
- `playback/`：Media3 服务、播放控制与队列。
- `ui/`：Compose 页面、组件、底部面板和设计稿主题。
- `LiltViewModel.kt`：导航、页面状态和业务交互。

## 致谢

- [Lilt](https://github.com/Amamiya23/mygo-qyin)：QRC 解密、接口经验
- [qq-music-api](https://github.com/Rain120/qq-music-api)：通用网页版接口
- [Tabler](https://github.com/tabler/tabler-icons)：图标资源

**本项目与 QQ 音乐官方无关，音乐版权归各权利人所有。**

## 许可证

[AGPL-3.0](LICENSE)
