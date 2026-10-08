# her-src —— 她的身体

这里是 ConMarn 的 **WebGL 那一层**:她的 VRM 形象,和她的房间。
它跑在手机 App 的 WebView 里(页面从 APK 的 `assets/her/` 加载)。

**这一层只负责让她动。** 她不在想事情、不在听、不在说话 ——
想事情在 `llama.cpp`,听在 `sherpa-onnx`,说在 TTS,全都在 Kotlin 那一侧。
这里是一具身体:呼吸、眨眼、看向你、说话时嘴动、情绪写在脸上。

```
her-src/
├── src/
│   ├── her.js     ← 她本人:加载 VRM、表情、口型、眨眼、看向你、拾取房间里的物件
│   └── room.js    ← 她的房间:用 three.js 的图元现搭的,配 canvas 现画的程序纹理
├── index.html     ← 一个 canvas,别的什么都没有
├── package.json
└── (产物,不进仓库)
    ├── node_modules/
    └── her.bundle.js   ← esbuild 打出来的,页面实际加载的就是它
```

---

## 怎么构建

```bash
cd her-src
npm install          # 只为装 three / three-vrm / esbuild

cd ..
tools/build-her.sh   # ★ 打包 + 自动拷进 app/src/main/assets/her/
```

也可以 `cd her-src && npm run build` —— 它就是转调同一个脚本。

### ★ 别手改 `her.bundle.js`

它是 **esbuild 的产物**。改了它,下次打包就覆盖回去了,而且中间那段时间你会以为
「代码改了怎么没生效」—— 这个项目在别的地方吃过同族的亏,代价是查半天。

### ★ 为什么参数不写在这个 `package.json` 里

`esbuild` 那串参数(`--bundle --format=iife --minify --target=es2020`)**只维护一份**,
在 [`../tools/build-her.sh`](../tools/build-her.sh)。所以这里的 `build` 是转调它,
不是把参数再抄一遍。

理由和这个项目里其他「只有一份清单」的规矩一样:**抄两遍的东西早晚对不上**,
而对不上的表现是「手机上跑的还是旧的那份」—— 不报错、不崩溃,只是不对。

### `index.html` 为什么有两份

`her-src/index.html` 和 `app/src/main/assets/her/index.html` 是同一个文件 ——
后者是 `build-her.sh` 拷过去的。**改就改这里的这一份**,打包时会同步过去。

(顺带:那个页面在 CSS 里给 canvas 写了 `touch-action: none`。
**那不是样式偏好,是必须的** —— WebView 默认把触摸当滚动手势,会在页面收到
`pointerup` 之前先把它吞掉,症状是「房间里的东西点了没反应」,
而且和「点空了」长得一模一样。)

---

## 改哪儿

### `src/her.js` —— 她本人

对外接口全挂在 `window.Her` 上,Kotlin 用 `evaluateJavascript` 调:

| 接口 | 干什么 |
| --- | --- |
| `Her.setTalking(bool)` | 开始 / 停止说话(打断也走这个) |
| `Her.speakRange(start, end)` | TTS 播到第几个字了 → 口型对上 |
| `Her.setEmotion(name, w)` | `happy` / `sad` / `angry` / `relaxed` / `surprised` / `neutral` |
| `Her.setListening(bool)` | 他开口了 / 他说完了 |
| `Her.lookAtUser(nx, ny)` | -1..1,摄像头里他的脸在哪 |
| `Her.setPresence(p)` | 1 = 在看他,0 = 走神 |
| `Her.setObjects(json)` | 房间里摆什么(数组) |
| `Her.setRoom(json)` | 换房间布置(背景 / 相机 / 每件摆在哪) |
| `Her.setScenery(url)` | 整个房间的模型(`room.glb`),可选 |

反方向是 Kotlin 侧的 `@JavascriptInterface`,这里只调:

| 回调 | 什么时候 |
| --- | --- |
| `HerBridge.onReady()` | 她的身体出场了 —— **Kotlin 在等这个** |
| `HerBridge.onError(msg)` | 出场失败(会显示「她没能出场」) |
| `HerBridge.onNote(msg)` | 一句旁白 |
| `HerBridge.onObjectTapped(id)` | 点中了房间里某个物件 |
| `HerBridge.onEmptyTapped()` | 点了空处 |

### `src/room.js` —— 她的房间

房间**不是模型文件,是代码搭的**:深胡桃木竖条墙、木地板、整面落地夜景窗
(月/星/云/三层城市)、灰蓝窗帘、踢脚线 —— 墙、地板、窗外的夜景**都是 canvas 现画的程序纹理**。
配色在文件顶部那张 `C` 表里,想换房间的样子,先动它。

★★ **两条不许动的东西**(`room.js` 文件头也写着,这里再说一遍):

1. **返回 `{ root, lights }` —— 几何和灯是分开收的。**
   拍悬浮窗那个透明底人形时要**藏掉几何、却必须留着灯**;合成一个对象就废了。
2. **`ROOM` 那几个尺寸。** 房间里的物件是按这套尺寸摆的,改了它们会浮起来。

> 另外:二次元那版配色(粉紫主调 + 描边)开发时另存过一份,**没有随仓库发布**。
> 要那个样子,改 `room.js` 里那张 `C` 配色表 + 尾部的灯就行 —— 两版的几何是同一套。

---

## 依赖

| 包 | 用途 | 许可证 |
| --- | --- | --- |
| [`three`](https://github.com/mrdoob/three.js) | 渲染 | MIT |
| [`@pixiv/three-vrm`](https://github.com/pixiv/three-vrm) | VRM 加载 + 表情 / 口型 / 弹簧骨 | MIT |
| [`esbuild`](https://github.com/evanw/esbuild) | 打包 | MIT |

它们**只在这一层用**(打包进 `her.bundle.js`),和 Android / Python 那两半没有关系。
