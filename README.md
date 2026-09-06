<p align="center">
  <h1 align="center">📖 小说有声阅读 (NovelReader)</h1>
  <p align="center">安卓本地小说阅读器：卷轴阅读 · TTS 逐句朗读 · 断点续读 · 纯本地隐私</p>
</p>

一个 **本地优先** 的安卓小说阅读 App（Android 7.0+）。核心场景：把 `.txt` 小说导入手机，像卷轴一样连续滚动阅读，并调用 **安卓系统 TTS 引擎** 逐句朗读、自动跨章，读到哪停到哪。

- 🔒 **默认不联网**：书籍与进度只存在本机；唯一的网络用途是「自动更新」——启动时检查 GitHub Releases 是否有新版并后台下载（6 小时限频一次，也可在设置里手动「检查更新」），不检查、不下载时不会发起任何网络请求
- 📖 **导入任意 txt**：支持 UTF-8 / GBK 编码，文件保存在应用私有目录
- 📜 **卷轴式无缝阅读**：跨章节连续滚动，不翻页
- 🔊 **TTS 朗读**：自动对齐到当前页完整段落段首 → 逐句高亮 → 读完全书自动停
- ⏱ **断点续读**：关闭 App 再打开，回到断点所在段落开头
- 💾 **进度双保险**：本地存储 + 自动镜像到「下载/NovelReader」，卸载重装可恢复
- 🎨 主题（白天/护眼/夜间）、字号、行距、朗读语速自由调
- ⚙️ 语音引擎自适应：自动识别手机已装的引擎（讯飞 / Google / ROM 内置等 30+ 包名），检测失败时给出可操作的提示并可一键打开系统 TTS 设置
- 📄 **不含任何内置书籍**：书籍内容完全由用户自己导入，项目不捆绑任何受版权保护的文本

## 技术架构

| 部分 | 说明 |
|---|---|
| 壳 | 单 `Activity` + 全屏 `WebView`(AndroidX WebKit `WebViewAssetLoader` 承载本地资源) |
| UI | `assets/` 内原生 HTML/CSS/JS 单页应用(SPA)，无任何前端框架 |
| 原生桥 | `@JavascriptInterface` 桥：书库、TTS、进度、备份、导入全部走 `Bridge.java` |
| 正文解析 | `ChapterParser.java`：章节切分（兼容「第N章/节/回/卷」多种编号） |
| TTS | `TtsEngine.java`：系统 `TextToSpeech` 多引擎回退封装，逐句队列 + 引擎绑定看门狗 |
| 构建 | Gradle 8.7 / AGP 8.2，SDK 34，minSdk 24，无第三方运行时依赖 |
| 自更新 | `UpdateChecker.java`：GitHub Releases API 比对版本 → 后台下载 → FileProvider 拉起系统安装页 |

## 构建

需要：JDK 17、Android SDK(platform 34 / build-tools 34)、Gradle 8.7。

```bash
bash build.sh [debug|release|both]   # 默认 both
# 产物: app/build/outputs/apk/{debug,release}/*.apk
```

首次运行会自动生成 release 签名(`keystores/`，**勿提交到仓库**)。

## 自动更新

- 启动后延迟数秒静默检查 `muronglanghuan/novel-reader` 的最新 Release（6 小时限频）
- 发现新版：自动后台下载 APK → 弹出系统安装页（需一次手动确认，属系统限制）
- 首次安装会引导授予「安装未知应用」权限
- 网络不可用/被墙时静默跳过，不影响阅读；设置页可手动「检查更新」

## 目录结构

```
app/src/main/
├── assets/                  # 前端整站(HTML/CSS/JS)
├── java/com/like/novelreader/
│   ├── MainActivity.java    # 唯一 Activity(WebView 壳)
│   ├── Bridge.java          # JS ⇄ 原生桥(书库/TTS/进度/导入)
│   ├── TtsEngine.java       # 系统 TTS 封装(多引擎回退 + watchdog)
│   ├── ChapterParser.java   # txt 章节解析
│   ├── BookStore.java       # 书库(用户导入)
│   └── ProgressStore.java   # 阅读进度持久化
└── AndroidManifest.xml      # <queries> 声明 TTS 引擎可见性(Android 11+)
```

## 使用

1. 安装 APK 后打开 App → 点「＋ 导入小说」从手机存储选择自己的 `.txt` 文件
2. 点书进入阅读：单击正文显示/隐藏工具条；「开始朗读」/「自动卷轴」都会自动对齐到当前页第一个完整段落的段首再开始
3. 朗读功能依赖手机已安装可用的中文语音引擎（系统设置 → 文字转语音）

## 说明

- 项目为个人作品；无网络权限，导入文件仅保存在应用私有目录，卸载即清除
- 本项目不附带任何书籍文件，请只导入你有权使用的文本内容
