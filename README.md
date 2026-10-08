# ConMarn

> 一个住在你手机里的虚拟伙伴。
>
> 她的**脑子在手机上**(本地大模型,离线),**耳朵也在手机上**(本地语音识别);
> **手在电脑上** —— 隔着一层局域网,她能替你点鼠标、敲键盘、开应用、读屏幕。
> 她的身体是一个 VRM 模型,站在一个用 WebGL 画出来的房间里。
>
> 这个仓库是她的全部源码。**不含任何模型文件、不含第三方二进制、不含 APK 或 EXE** ——
> 那几样怎么自己拿,见 **[THIRD_PARTY.md](THIRD_PARTY.md)**。

---

## 目录

- [一、她是什么](#一她是什么)
- [二、技术架构](#二技术架构)
- [三、技术栈](#三技术栈)
- [四、模型清单与去哪拿](#四模型清单与去哪拿)
- [五、怎么构建](#五怎么构建)
- [六、换人物形象 / 换房间布置](#六换人物形象--换房间布置)
- [七、隐私边界](#七隐私边界)
- [八、仓库里有什么 / 没有什么](#八仓库里有什么--没有什么)
- [九、授权](#九授权)

---

## 一、她是什么

ConMarn 是一个人机交互的实验:把「一个虚拟伙伴」整个搭在一台 Android 手机 + 一台电脑上,
**尽量不依赖任何云服务**。

她同时做四件事:

| 能力 | 在哪跑 | 说明 |
| --- | --- | --- |
| **脑**(思考、对话、决策) | 手机 | 内嵌 llama.cpp,常驻一个 4B 档的本地模型 |
| **耳**(唤醒词 + 连续对话) | 手机 | 内嵌 sherpa-onnx:KWS 唤醒 → VAD 断句 → ASR 识别 |
| **嗓**(说话) | 手机 | 内嵌 sherpa-onnx TTS(VITS / ZipVoice),本地合成 |
| **身**(形象与房间) | 手机 | WebView + three.js + VRM,她的身体是一个 3D 模型 |
| **手**(操作电脑) | 电脑 | 一个 Python 服务,用 Windows UIA + OCR 驱动鼠标键盘 |

一句话:**脑子和耳朵都在手机本地,电脑只是「一双手」。**

这条分工不是设计洁癖,是被两件事逼出来的:

1. **她的脑子必须在本地** —— 不然电脑一关她就哑了,而且你说的话全都要出门。
2. **手机上没有权限做的事,只能交给电脑** —— Android 上要操作别的 App 得申请无障碍服务,
   而厂商系统(REDMAGIC / ColorOS 这类)对第三方助手卡得很死。**电脑那边没有这个限制。**

---

## 二、技术架构

```
┌──────────────────────────── 手机 (Android) ────────────────────────────┐
│                                                                         │
│   ┌── 脑 ────────────────────────────┐   ┌── 耳 ──────────────────┐    │
│   │  llama.cpp (内嵌 .so)             │   │  sherpa-onnx (JNI)     │    │
│   │  brain.gguf  ── 127.0.0.1:8080   │   │  KWS 唤醒词「从漫」      │    │
│   │  前缀快照缓存(冷启动 11 分钟→0.4秒)│   │  VAD 断句 → SenseVoice │    │
│   └──────────────┬───────────────────┘   └───────────┬────────────┘    │
│                  │                                    │                 │
│                  │        ┌── 嗓 ────────────────┐    │                 │
│                  │        │ sherpa-onnx TTS      │    │                 │
│                  │        │ VITS / ZipVoice 克隆 │    │                 │
│                  │        └──────────────────────┘    │                 │
│                  │                                    │                 │
│          ┌───────┴────────────────────────────────────┴──────┐          │
│          │           AiAgent —— 对话循环 + 工具派发            │          │
│          │  history / 心情 / 长期记忆 / 工具表 / 风险闸        │          │
│          └───────┬───────────────────────────────┬───────────┘          │
│                  │                               │                      │
│          ┌───────┴────────┐              ┌───────┴─────────┐           │
│          │  SelfHand       │              │  PcHand          │           │
│          │  (手机自己:      │              │  (电脑那只手:     │           │
│          │   开应用/闹钟/   │              │   通过局域网)     │           │
│          │   剪贴板/拨号)   │              │                  │           │
│          └────────────────┘              └───────┬──────────┘           │
│                                                   │                      │
│   ┌── 身 ─────────────────────────────────┐      │                      │
│   │  WebView (appassets.androidplatform.net)│      │                      │
│   │  three.js + @pixiv/three-vrm            │      │                      │
│   │  她的房间 + 可点物件 + ARKit 表情/口型   │      │                      │
│   └─────────────────────────────────────────┘      │                      │
└────────────────────────────────────────────────────┼─────────────────────┘
                                                      │
                            局域网 (Wi-Fi, TCP 9527)   │  HMAC-SHA256 签名
                            双向认证 + 配对码 + TOTP    │  每条指令带序号
                                                      ▼
┌────────────────────────── 电脑 (Windows) ──────────────────────────────┐
│  pc-server/server.py  —— 认证、TOTP、配对、文件传输、媒体流             │
│  pc-server/ai_tools.py —— 「那只手」:14 个工具                          │
│  pc-server/uia.ps1     —— PowerShell + Windows UIA 桥                  │
│                                                                        │
│  工具:open_app · list_windows · focus_window · media · get_state       │
│        screenshot · click_at · list_ui · read_screen · click_ui        │
│        scroll · search · hotkey · type                                 │
└────────────────────────────────────────────────────────────────────────┘
```

### 2.1 手机侧:脑

`ModelManager` 在 App 启动后把内嵌的 `libllamaserver.so` 从 `nativeLibraryDir`
以子进程方式 exec 起来(Android 10+ 只有这个目录允许 exec app 私有的可执行文件),
监听 `127.0.0.1:8080`,对外是标准的 OpenAI 兼容接口。

模型是 `models/brain.gguf`(Qwen3-4B 档),**不打进 APK** ——
和 `asr/`、`tts/` 一样,推手机里就行,可随时删掉。

★★ **这个项目最关键的一处工程是「前缀快照」。**
她的系统提示词 + 工具表一共约 4889 token,是**每一次请求都不变的前缀**。
在手机上满负荷冷算这段前缀要 **11 分钟**(实测 685,325 ms)。
于是每次算完把 KV 状态存盘(`--slot-save-path`),下次直接从盘上还原 ——
**685,325 ms → 434 ms**。这个数字是整个项目里最值钱的一个。

> ⚠️ 快照和模型**必须匹配**:换一个模型、或者改一个字系统提示词,旧的快照就用不了,
> 会重新走一次冷算。这是设计,不是 bug。

### 2.2 手机侧:耳

内嵌 sherpa-onnx,三段流水线,全部本地:

```
待机 ──KWS 常驻监听「从漫」──→ 命中 ──→ 对话中(VAD 连续切句)
                                          │  她说话时麦克风关掉(半双工)
                                          ▼
                                   SenseVoice ASR → 送进脑子
```

- **唤醒词**是中文「从漫」,用拼音 KWS(`kws-zipformer-wenetspeech-3.3M`,约 5MB)。
  选中文是因为**拼音按音节切**,不会像 BPE 那样把生僻词切错。
- **识别**用 SenseVoice int8(中英日韩粤),`asr/model.int8.onnx`(约 239MB)。
- **半双工是硬要求**:她的 TTS 从扬声器出来、被自己的麦克风听回去的话,
  会得到一句语法通顺、上下文合理的「用户输入」—— 她开始自问自答,而且**不报任何错**。
  所以麦克风的开关挂在 TTS 的 `onDone` 回调上。

### 2.3 手机侧:身

她的身体不是原生 UI,是一个 **WebView 里跑 WebGL** 的三维场景:

- `three.js` 渲染 + `@pixiv/three-vrm` 加载 VRM 人物模型
- 页面从 APK 的 `assets/her/` 加载(自定义 `shouldInterceptRequest`,
  只放 `her/` 一个目录,走 `appassets.androidplatform.net` —— 不是 `file://`,也不是网络)
- **换装**:`files/person/` 放 `.vrm`、`files/room/` 放 `.glb` 就生效,不用改代码(见第六节)
- Kotlin 和 JS 之间用 `addJavascriptInterface` 双向通信:JS 报 `onReady` / `onObjectTapped`,
  Kotlin 发 `say()` / `setExpression()` / `setObjects()` 等

前端源码在 **[`her-src/`](her-src/)**(esbuild 打包成 `her.bundle.js`,
见 [`tools/build-her.sh`](tools/build-her.sh))。**仓库里那份 bundle 是已经打好的产物**,
改 `her-src/src/*.js` 之后要重新打包。

### 2.4 电脑侧:手

`pc-server/` 是一个纯 Python 的 Windows 服务,**不含任何 GUI 框架依赖**
(界面用 tkinter,二维码用 segno,截图用 pillow —— 都可以不装)。

它的核心是 `ai_tools.py` 里那 14 个工具。实现上分三层,
**从便宜到贵,能用便宜的就别用贵的**:

| 层 | 手段 | 什么时候用 |
| --- | --- | --- |
| 1 | **Windows UIA**(`uia.ps1`) —— 按控件名找、读值、离屏直接 Invoke | 有控件树的应用(浏览器、Office、大多数桌面软件) |
| 2 | **OCR**(截图 + 识别) | UIA 瞎了的时候 —— **微信是典型**(Qt/DirectUI 自绘,整个窗口 UIA 只暴露 1 个元素) |
| 3 | **视觉模型**(`eye.gguf`,跑在手机上) | 最后手段 —— 一次点击要 220 秒,只配兜底 |

> ★ 有一条贯穿全项目的规矩:**「会撒谎的手不是手,是陷阱」**。
> 每个工具的回执里必须能回答「做成了没有」——
> 比如 `type` 打完之后会**回读**验证,而不是敲完就报成功。

### 2.5 两条数据流

| 方向 | 名字 | 语义 |
| --- | --- | --- |
| ↓ 下行 | **手** | 你让她做 → 她执行 → **诚实回执** |
| ↑ 上行 | **感官 / 神经** | 有事就推一个事件,不用你叫 |

「一只手」的判据只有两条:**能自述「我会什么」**、**能诚实回答「做成了没有」**。

### 2.6 一个刻意的设计:工具不都塞进提示词

能被模型看见的工具表**就是那座预热缓存的前缀** —— 每多一个工具,前缀涨约 71 token,
冷算时间跟着涨。所以:

- **常驻的电脑保持具名工具**(那 14 个已经付过钱了,4B 选工具的准确率不受损)
- **其余所有能力共用 `list_hands` / `use_hand` 两个通用入口** ——
  手的能力清单**放在工具回执里**(回执属于对话消息,在缓存前缀**之后**,不破坏缓存)

**装 1 只手和装 20 只手,前缀一样大。**

---

## 三、技术栈

| 层 | 用什么 |
| --- | --- |
| **Android** | Kotlin · Java 17 · AGP 9 · `minSdk 30` / `targetSdk 37` |
| **本地大模型** | 内嵌 **llama.cpp**(NDK 自编,dotprod/i8mm,`libllamaserver.so`) |
| **语音** | **sherpa-onnx**(JNI;KWS 唤醒词 + silero VAD + SenseVoice ASR + VITS/ZipVoice TTS) |
| **她的身体** | **WebView + three.js + @pixiv/three-vrm**(VRM 1.0) |
| **电脑那只手** | **Python 3**(无第三方框架;Windows **UIA** via PowerShell + **OCR** + `SendInput`) |
| **两端通信** | 局域网 socket,**HMAC-SHA256 签名 + 递增序号 + TOTP 动态码** |
| **构建** | Gradle(Android)· esbuild(前端)· 纯 shell 脚本(取第三方库) |
| **测试** | JUnit(纯 JVM 单测,不连手机)· PC 侧一堆 `test_*.py` |

### 3.1 关于「纯逻辑层」这条规矩

项目里有一个贯穿始终的习惯:凡是**判定**都要抽成一个零 Android 依赖的纯对象
(`MoodMath` / `EarMath` / `WardrobeMath` / `RiskMath` / `FastPath` …),
IO 和系统调用留在调用方。这样**几百条判定都能在电脑上用 JVM 单测钉死**,
不必连手机。

代价是代码看起来「薄得奇怪」—— 比如 `Wardrobe.kt` 只做三件事:
列目录、开文件、写日志,判定全在 `WardrobeMath`。这是刻意的。

### 3.2 关于命名

`applicationId` 是 **`com.example.touchpad`**,目录叫 `phone-touchpad`,里面还留着
`TouchpadClient` / `MainActivity` 这些名字。这不是笔误 —— 这个项目最早是一个
「手机当电脑触控板」的小工具,**这些是历史名字,改包的代价(丢掉用户全部数据)远大于收益**。
名字已经统一显示成 ConMarn,但内部的类名和包名不打算改。

---

## 四、模型清单与去哪拿

**模型一律不打进仓库**(体积大、且各有各的许可证)。
以下全部放在手机的
`/sdcard/Android/data/com.example.touchpad/files/` 下,**放进去就生效,不用改代码**。

| 目录 / 文件 | 是什么 | 必需? | 去哪拿 |
| --- | --- | --- | --- |
| `models/brain.gguf` | **脑** —— 文本大模型 | ★ 必需 | 任意 llama.cpp 能跑的 GGUF。这个项目用的是 Qwen3-4B 档 |
| `models/eye.gguf` + `models/mmproj.gguf` | **眼** —— 视觉定位(看屏幕找控件) | 可选 | Qwen-VL 档的 GGUF + 对应的 `mmproj`。**不装 = 少了最后一道兜底,其余功能不受影响** |
| `asr/model.int8.onnx` + `asr/tokens.txt` | **耳** —— SenseVoice 语音识别 | 可选 | sherpa-onnx 预训练模型 `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` |
| `asr/silero_vad.onnx` | 断句 | 同上 | sherpa-onnx release 里的单文件 |
| `kws/encoder-…int8.onnx` `kws/decoder-…int8.onnx` `kws/joiner-…int8.onnx` `kws/tokens.txt` | **唤醒词** | 同上 | sherpa-onnx 的 `kws-zipformer-wenetspeech-3.3M-2024-01-01` |
| `kws/keywords.txt` | 唤醒词表(**你自己生成**) | 同上 | 用 sherpa-onnx 自带的 `text2token` 生成,格式是拼音 + `@汉字` |
| `tts/zv/`(**或** `tts/eula/`) | **嗓** —— 本地 TTS | 可选 | `zv` = ZipVoice(声音克隆,配一段参考音频);`eula` = VITS(自带 804 个音色)。**放哪套就跑哪套** |
| `person/*.vrm` | **她的身体** | ★ 必需 | 任意 VRM 1.0 模型。**仓库里不带她的脸**(那是第三方示例模型) |
| `room/*.glb` + `room/room.json` + 贴图 | 房间布置 | 可选 | 见第六节 |

> ★ **同名陷阱**:`asr/` 和 `kws/` 里**各有一个 `tokens.txt`,同名不同物**
> (一个是整句识别的词表,一个是关键词的拼音表)。所以它们**必须分目录放**,
> 平铺在一起会互相覆盖,而症状是两个功能一起不工作、**并且都不报错**。

> ★ `eye.gguf`(眼)不做常驻 —— 按需加载、闲置卸载。一次点击约 220 秒
> (视觉编码器要切 2046 个 token),**只当最后手段**。

---

## 五、怎么构建

### 5.0 先说明:仓库里缺的那几块

这个仓库**故意不含**以下内容,构建前你得自己准备:

| 缺什么 | 为什么 | 怎么办 |
| --- | --- | --- |
| `app/src/main/jniLibs/arm64-v8a/*.so` | 全是第三方构建,约 41.7MB | 见 [THIRD_PARTY.md](THIRD_PARTY.md) |
| `app/src/main/assets/her/*.vrm` | 那是**她的脸**(pixiv 的 VRoid 示例模型) | 自己放一个,见第六节 |
| `pc-server/{secret,master}.json` | 首次配对时自动生成 | 不用管,第一次跑就生成 |
| `keystore.properties` / `*.jks` | 签名密钥,绝不入库 | 自己配一个,或者不配(不签名也能构建 debug 包) |

### 5.1 电脑端

```bash
pip install segno pillow          # 都可选,不装就用降级方案
python pc-server/server.py
```

首次运行会让你设一个 6 位登录 PIN。界面上会显示本机 `IP:9527`。

| 包 | 不装的后果 |
| --- | --- |
| `segno` | 二维码不显示 → 退化成手动抄 32 位种子,其余功能正常 |
| `pillow` | 屏幕镜像不可用 → 触控板/文本输入仍正常 |

> ⚠️ **Windows 建议以管理员身份运行**,否则她控制不了那些以管理员身份打开的窗口
> (Windows 的 UIPI 权限隔离,不是 bug)。

### 5.2 前端(`her-src/`)

```bash
cd her-src
npm install
npm run build          # esbuild 打包 → 产物拷进 app/src/main/assets/her/
```

`tools/build-her.sh` 就是干这件事的,可以直接跑它。

### 5.3 Android

★ **先说一件和常见项目不一样的事:这个仓库里没有 `gradlew` / `gradlew.bat`。**
`gradle/wrapper/` 底下那两个文件(`gradle-wrapper.jar` 和 `.properties`)在,
但**入口脚本本身没有** —— 作者在本机一直用自己装的 Gradle,所以那两份从来没生成过。
两条路任选:

```bash
# A. 让 Gradle 把缺的两个脚本生成出来,之后就和其他项目一样了
gradle wrapper
./gradlew :app:assembleDebug            # 出包
./gradlew :app:testDebugUnitTest        # 跑纯 JVM 单测(不连手机)

# B. 不生成脚本,直接用本机那个 gradle
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
```

需要的版本写在 `gradle/wrapper/gradle-wrapper.properties` 里。

- ★ **构建之前先把 `.so` 补齐**(见 5.0 和 [THIRD_PARTY.md](THIRD_PARTY.md))。
  缺了它们 Gradle 照样能出包,但装到手机上她的脑和嗓子起不来 —— **失败在运行期,不在编译期**。
- 签名:项目根的 `keystore.properties`(**gitignore**)。**不存在时 release 不签名**,
  方便别人拉下来自己配密钥。
- ★ `jniLibs` 必须 `useLegacyPackaging = true`(已在 `build.gradle.kts` 里)。
  默认情况下 `.so` 是压缩着留在 APK 里的,磁盘上没有文件,**没有东西可以 exec**。

### 5.4 电脑端测试

```bash
cd pc-server
python test_ai.py
python test_hand.py
python test_click_ui.py
# …等等,见 pc-server/test_*.py
```

> 大部分测试需要一台真的 Windows 桌面。少数只测纯逻辑的不需要。

---

## 六、换人物形象 / 换房间布置

**设计目标是:换人物和换房间不用改代码、不用重新构建。**

两个目录,和 `models/`、`asr/`、`tts/` 住在一起:

```
/sdcard/Android/data/com.example.touchpad/files/
├── person/          ← 人物形象
└── room/            ← 房间布置
```

### 6.1 换人物

往 `person/` 里丢一个 `.vrm` 文件,**重开一次**就生效。文件名随便(`新人物.vrm` 也行)——
程序**只认扩展名,不认文件名**。

> 这个仓库里**不带她的脸**。`assets/her/` 下那个位置是空的,
> 你自己放一个 VRM 进去(或者推到 `person/`)。

### 6.2 换房间

往 `room/` 里丢:

| 文件 | 作用 |
| --- | --- |
| `room/room.json` | 房间配置:每件东西**摆在哪、长什么样** |
| `room/*.glb` 或 `*.gltf` | 整个房间的模型 |
| `room/*.png` `*.jpg` `*.webp` | 贴图 |

> ★ **房间里的物件清单不是 `room.json` 说了算的** ——
> 它是**她有哪些手**决定的。这个设计防的是一个必然会犯的错:
> UI 里手写一份物件清单,能力注册表里再写一份,两份早晚对不上。
> `room.json` 只负责**摆位和外观**。

### 6.3 白名单(为什么只能放这些)

外部目录**只接受这几种扩展名**,不在表里的连读都不读:

```
vrm · glb · gltf · bin · png · jpg · jpeg · webp · json
```

**`.html` / `.js` / `.css` / `.svg` 永远不在表里。** 原因是安全:
那个 WebView 有和 Kotlin 通信的桥,如果能往里面塞脚本,等于把桥交出去。
这是**白名单**而不是黑名单 —— 漏一个的代价是「她加载不出来」,
不是「有人借着她的页面调用了 Kotlin」。

### 6.4 出问题怎么查

换装最容易出的错是「**我换了它没变**」,而那种失败**一个字都不报**。所以:

- 每次用了哪个文件、为什么用它,**都会写进 `model.log`**
- 文件坏掉 / 0 字节 / 读不到 —— **都会明说**,不会静默退回内置的那份
- 日志里那句 `人物: 用了 xxx` 就是判据

观察口径:`/sdcard/Android/media/com.example.touchpad/model.log`(**UTF-8**)。

---

## 七、隐私边界

这个项目有一条从第一天就立起来的红线,而且**尽量做成了结构,不是注释**:

### 7.1 截图永不上云

屏幕内容**永远不出这台手机**——视觉定位跑在**手机本地**那个模型上;
往外发的只有文本。

### 7.2 音频不出本机

耳朵收到的音频是内存里的一个 `FloatArray`,**解码完即弃**:
没有 `.pcm`、没有 `.wav`,不进任何缓存、不落盘。

> **准确的说法是「不出本机」,不是「永不上云」** ——
> 代码留了一个「换成你自己的识别服务器」的接口。
> 但**没配地址就构造不出来**,而且开了之后界面上会写明音频去哪。
> 默认永远是本地。

### 7.3 凭据

- 配对种子、恢复码:**Windows DPAPI 加密落盘**,绑当前 Windows 账户
- API key(如果你想接云端兜底):Android Keystore 加密
- 日志里**只记字段名,不记值**
- 密钥、签名文件、日志文件**全部 gitignore**

### 7.4 她是能操作你电脑的 —— 这条要自己权衡

她能点鼠标、敲键盘、开应用。**这条路的边界就是「你坐在电脑前能做的事」。**
所以:

- 只在**你自己可信的 Wi-Fi** 下用,别在公共热点上开
- 配对种子 / 二维码 / 恢复码就是钥匙,**别截图、别发人**
- 电脑端登录要 PIN;手机连上要配对码;每条控制指令带 HMAC 签名

### 7.5 明确的**不**承诺

- ❌ **「她只认你本人的声音」—— 不说这句。** 声音是可以录的。
  凡是靠声音判断「是不是本人」的方案,在「能转账」这个级别上都不该单独成立。
- ❌ **「用完即删能擦掉一切」—— 不说这句。** 有形状的(卡号、身份证、手机号)
  能找出来;一个纯字母数字的密码**进过文字之后,任何正则都找不回来**。

---

## 八、仓库里有什么 / 没有什么

### 有

- `app/` —— 整个 Android 工程(Kotlin 源码 + 资源 + 单测)
- `pc-server/` —— 电脑端 Python 服务 + 那只手的 14 个工具 + 一堆测试
- `her-src/` —— 她的房间那层 WebGL 前端源码(three.js + VRM)
- `tools/` —— 构建 / 取第三方库 / 观察设备的脚本
- `eval/` —— 提示词与工具表的漂移守卫、评测用例

### 没有(故意的)

| 没有 | 原因 |
| --- | --- |
| `.apk` / `.exe` | 只开源代码 |
| `jniLibs/**/*.so`(约 41.7MB) | 全是第三方构建,见 [THIRD_PARTY.md](THIRD_PARTY.md) |
| `sample.vrm`(她的脸) | 第三方示例模型,**而且那是她的脸** |
| 任何模型文件 | 体积 + 各自的许可证,见第四节 |
| `keystore.properties` / `*.jks` | 签名密钥 |
| `secret.json` / `master.json` / `*.log` | 配对凭据和运行日志 |

---

## 九、授权

本项目以 **[Apache License 2.0](LICENSE)** 授权,全文见 [LICENSE](LICENSE)。

它包含若干第三方组件(llama.cpp、sherpa-onnx、onnxruntime 等),
各自的许可与归属见 **[THIRD_PARTY.md](THIRD_PARTY.md)** 和 **[NOTICE](NOTICE)**。

```
Copyright 2026 IMXia-Chan

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
