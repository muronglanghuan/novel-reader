# NovelReader（小说有声阅读）开发备忘

单 Activity + WebView(WebViewAssetLoader) 承载整站 SPA 的本地安卓小说阅读器。
**用户为中文个人作者**, 交互消息用中文回复; 已在 GitHub 公开: `muronglanghuan/novel-reader`。
详尽的发布历史见 `CHANGELOG.md`, 用户手册在 `dist/使用说明与真机自查清单.txt`。

## 常用命令(环境固定, 勿改路径)
- 构建: `bash build.sh [debug|release|both]`（内部使用 /g/android-tools 的 JDK/Gradle/SDK）
- 产物: `app/build/outputs/apk/{debug,release}/*.apk`; 发布包同步到 `dist/`(中文名) 
- GitHub Release: `gh release create vX.Y --repo muronglanghuan/novel-reader --title ... --notes ... 资产名`；**资产用 ASCII 名** `NovelReader-vX.Y-release.apk`(中文名会被 shell/API 截断)
- 版本: `app/build.gradle` 同步 versionCode/versionName; tag 为 `vX.Y`(无前缀 v 会被更新器跳过; 资产必须含 `-release.apk`)

## 架构要点(改代码前必读)
- `MainActivity.java`: 唯一 Activity; **不要调用 wv.onPause()**(冻结 JS 定时器 → 朗读链停);
  已设 `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT)`(后台/息屏保活)
- 朗读链路: JS 逐句驱动 —— speakCurrent → 引擎 onDone(原生→JS 事件) → 下一句;
  JS 是唯一推进者。跨章/推进逻辑全在 `app.js`(`advanceParagraph`/`listenFrom`)
- **已知深坑(勿复辟)**: `chapterParas(ch)` 返回**段落数组**不是章节记录, 别取 `.paras`;
  prune 的滚动补偿已被移除, 视觉稳定靠浏览器原生 scroll anchoring(勿加回手写 scrollTop 补偿)
- `ReadAloudService`: 朗读期间前台服务 + PARTIAL_WAKE_LOCK(息屏持续朗读的关键);
  JS 通过 `B.ttsReadStarted(章节名)`/`B.ttsReadStopped()` 启停; 通知栏"停止朗读"→ MainActivity `ACTION_STOP_READING` → evaluateJavascript stopTts
- TTS 引擎可见性: manifest `<queries>`(Android 11+) 已放行 30+ 引擎包; `TtsEngine.engines()` 走 PackageManager 直查(勿改回探测实例法); init 有 8s 看门狗
- 定时停止: JS `Tts.timerAt` + `pendingTimerAt`(待定, 在 listenFrom 成功后兑现——stopTts 不得清 pending, 否则"定时后点朗读"会失效); 到点在 speakCurrent 检查(事件驱动, 息屏有效)
- 自动更新: `UpdateChecker.java` 比对 GitHub latest tag 与 versionName; 6h 限频; 自动下载+FileProvider 拉起安装页

## 测试(模拟器 127.0.0.1:16384 = MuMu debug 包; emulator-5554 = release 冒烟)
- CDP: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`; release 包**无** WebView 调试
- 模拟朗读: 页面内 patch `B.ttsState→{ok:true}` 与 `B.ttsSpeak→setTimeout(done)`; 页面全局 `Tts`/`R` 为模块级, 非 window 属性
- 复现滚动/跨章问题用真实 touch 手势(CDP Input.dispatchTouchEvent), 勿用程序化 scrollTop(会与锚定竞争制造假象)
- 测试书 `哇！爆率真的很高`(5.8MB/590章)在模拟器 imports; 仓库不含任何书籍(版权)

## 边界与提醒
- 更新通道是 GitHub(国内网络可能不通, 自动检查静默失败属预期)
- 不把 `keystores/`、`dist/`、`shots/`、`*.apk` 提交仓库(.gitignore 已配)
- 用户实机为国产 ROM(强后台管理); 息屏停读若仍复现, 先引导其加"省电白名单"
- 迭代节奏: 改完 → emulator debug 验证 → `bash build.sh both` → dist/使用说明 加条目 → commit+push → `gh release create`(用户的手机靠自动更新收版)
