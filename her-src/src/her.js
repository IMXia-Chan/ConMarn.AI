// ============================================================================
// ConMarn 的「身体」。
//
// 这一层只干一件事:让别人能指挥她动。她自己不思考、不听、不说 ——
// 「听」在 Kotlin 侧的 Ears(麦克风+VAD),「想」在 llama.cpp,「说」在 TTS。
// 这里就是那具身体:呼吸、眨眼、看向你、说话时嘴动、情绪写在脸上。
//
// 为什么用 WebView + three.js 而不是原生 Filament:
//   VRM 的表情/口型/眨眼/弹簧骨这套东西,three-vrm(MIT)已经写好了,而且是
//   业界跑得最多的一份。原生路线要自己解 VRM 扩展、自己接 morph target、
//   自己写弹簧骨物理 —— 一模一样的东西重写一遍,只会更慢更差。
//
// 对外接口(全挂在 window.Her 上,Kotlin 用 evaluateJavascript 调):
//   Her.setTalking(bool)          开始/停止说话(打断也走这个)
//   Her.speakRange(start, end)    TTS 播到第几个字了 -> 口型对上
//   Her.setEmotion(name, w)       happy/sad/angry/relaxed/surprised/neutral
//   Her.setListening(bool)        他开口了 / 他说完了
//   Her.lookAtUser(nx, ny)        -1..1,摄像头里他的脸在哪
//   Her.setPresence(p)            1=在看他,0=走神
//   Her.setObjects(json)          房间里摆什么(数组,见下)
//   Her.setRoom(json)             换房间布置(背景 / 相机 / 每件摆在哪,见 setRoom)
//   Her.setScenery(url)           整个房间的模型(room.glb),可选
//   Her.setMotion(url)            她做的那段动作(motion/*.vrma),可选
//
// 反向(Kotlin 侧 @JavascriptInterface,这里只调):
//   HerBridge.onReady() / onError(msg) / onNote(msg)
//   HerBridge.onObjectTapped(id)  点中了某个物件
//   HerBridge.onEmptyTapped()     点了空处
// ============================================================================

import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';
// ★ 2026-10-08:她的**动作**(.vrma)。官方那份(MIT),干两件事:
//   VRMAnimationLoaderPlugin 让 GLTFLoader 认得 .vrma 里的「VRMC_vrm_animation」扩展;
//   createVRMAnimationClip 把它翻译成一条能喂给 AnimationMixer 的轨道。
//   ★ 轨道名指向的是**标准化骨骼节点**(Normalized_xxx)—— 所以下面 mixer 的根
//     必须是 vrm.scene(那正是标准化骨骼挂在的地方),不能是 scene。
import { createVRMAnimationClip, VRMAnimationLoaderPlugin } from '@pixiv/three-vrm-animation';
import { buildRoom } from './room.js';

// ---------------------------------------------------------------------------
// 小工具:让数值平滑地追目标值(指数趋近)。
// 直接赋值会「啪」地跳过去,像换贴图;她得是慢慢动过去的。
// ---------------------------------------------------------------------------
class Smoothed {
  constructor(v = 0, speed = 6) { this.v = v; this.target = v; this.speed = speed; }
  set(t) { this.target = t; }
  jump(t) { this.v = this.target = t; }
  step(dt) {
    // 1 - e^(-k*dt):和帧率无关的趋近,掉帧了也不会变慢
    this.v += (this.target - this.v) * (1 - Math.exp(-this.speed * dt));
    return this.v;
  }
}

// 一层层的正弦叠起来当噪声用 —— 不用 perlin,省一个依赖,观感够了。
function wobble(t, seed) {
  return (
    Math.sin(t * 0.37 + seed) * 0.6 +
    Math.sin(t * 0.91 + seed * 2.3) * 0.3 +
    Math.sin(t * 1.73 + seed * 5.1) * 0.1
  );
}

const clamp01 = (x) => Math.max(0, Math.min(1, x));
const clampTo = (x, lo, hi) => Math.max(lo, Math.min(hi, x));

/**
 * 「这真的是个数吗」—— 换房间布置那条路上到处要用。
 *
 * ★ 为什么不用 `Number(v)`:`Number(null)` 是 **0**,`Number('')` 也是 **0**。
 *   于是 JSON 里「我没写这一项」和「把它挪到原点」会变成同一件事,
 *   而且是**静默**的 —— 用户看到的是「东西跑到她脚底下去了」,
 *   完全想不到是自己少写了一行。
 */
const isNum = (v) => typeof v === 'number' && isFinite(v);

// 房间里物件的配色。素色、低饱和 —— 房间的底子是暗的,靠自发光提一点亮度
// 才看得见(见 makeObject)。按摆放顺序取,所以两件相邻的不会同色。
const OBJECT_COLORS = [0x7fa6d9, 0xe0a06a, 0x8fc9a8, 0xc98fb8, 0xd9cf7f];

// 按下到抬起位移超过这么多像素,就不算「点」,算「拖」。
// 按屏幕像素定,不按米 —— 手感是屏幕上的事。
const CLICK_MOVE_PX = 24;

/**
 * 把 room.json 里那**一个**底色,摊成和内置那层同形状的四段渐变。
 *
 * ★ 为什么不直接把 `scene.background` 设成纯色:见 makeBackdrop 的文件头 ——
 *   纯色衬得人像抠图。他给一个色,是要「房间的调子换成这个」,
 *   不是要「把背景铺平」,所以这里保持形状、只换调子。
 */
function backdropStops(hex) {
  const n = parseInt(hex.slice(1), 16);
  const rgb = [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  const mix = (k) => '#' + rgb
    .map((v) => Math.max(0, Math.min(255, Math.round(v * k))).toString(16).padStart(2, '0'))
    .join('');
  return [[0.0, mix(0.62)], [0.45, mix(0.95)], [0.75, mix(1.35)], [1.0, mix(0.80)]];
}

/** 把一棵子树里的 geometry / material 都释放掉。反复 setObjects 时不释放会漏显存。 */
function disposeTree(root) {
  root.traverse((o) => {
    if (o.geometry) o.geometry.dispose();
    if (o.material) {
      const ms = Array.isArray(o.material) ? o.material : [o.material];
      for (const m of ms) m.dispose();
    }
  });
}

// ---------------------------------------------------------------------------
// 口型:中文 -> 五个元音槽位。
//
// 没有音素级对齐,只能从**正在念的那个字**猜个大概。这不精确,但比
// 「嘴随机开合」强得多 —— 至少每一句的字数、节奏是对的,嘴和声音是一起动的。
// 拼音韵母里 a/o/e/i/u 的频率分布大致如此,按这个归类。
// ---------------------------------------------------------------------------
const VISEME_OF_CHAR = (() => {
  const m = new Map();
  const put = (chars, v) => { for (const c of chars) m.set(c, v); };
  // 开口最大的是 a 系
  put('啊阿哈呀吧啪妈爸大他她它那拉卡啦答塔娜杀沙咋擦撒瓦', 'aa');
  // 圆唇 o 系
  put('哦噢喔我握波泼摸佛破磨托说国罗货错所多朵坐', 'ou');
  // 扁唇 e 系
  put('额哎唉诶得德特呢了乐可个和这着谁给黑嘿诶', 'ee');
  // 齐齿 i 系
  put('一以已亿意义你里力几记起气七西息笔批米地体', 'ih');
  // 撮口 u 系
  put('五无午武物不木目服读图路故苦户出书如土度都', 'ou');
  return m;
})();

// 认不出来的字:按它的 Unicode 值散列到一个槽位,保证节奏在动
const FALLBACK_VISEMES = ['aa', 'ih', 'ou', 'ee', 'oh'];
function visemeFor(ch) {
  const hit = VISEME_OF_CHAR.get(ch);
  if (hit) return hit;
  return FALLBACK_VISEMES[ch.codePointAt(0) % FALLBACK_VISEMES.length];
}

// ---------------------------------------------------------------------------
// 主体
// ---------------------------------------------------------------------------
class Her {
  constructor() {
    this.renderer = null;
    this.scene = null;
    this.camera = null;
    this.vrm = null;
    this.clock = new THREE.Clock();

    // 状态
    this.talking = false;
    this.listening = false;
    this.presence = 1;
    this.emotion = 'neutral';
    this.emotionW = 0;         // 情绪强度
    this.gaze = { x: 0, y: 0 };

    // 说话进度:TTS 报过来的字符区间
    this.speakChars = { start: 0, end: 0, at: 0 };
    this.mouthOpen = new Smoothed(0, 22);   // 嘴张合要快,慢了像卡带
    this.currentViseme = 'aa';
    // 原生侧推过来的**真实响度**(0..1)和它到达的时刻。
    // -1 表示「还没有过」——用它和 0 区分开:0 是「真的在静音」,是合法的开口度。
    this.mouthRms = -1;
    this.mouthRmsAt = 0;

    // 平滑器
    this.breathePhase = Math.random() * 10;
    this.blink = new Smoothed(0, 18);
    this.blinkTimer = 2 + Math.random() * 3;
    this.nextBlinkIn = 2 + Math.random() * 3;
    this.headYaw = new Smoothed(0, 2.5);
    this.headPitch = new Smoothed(0, 2.5);
    this.lean = new Smoothed(0, 3);      // 凑近(在听的时候)
    this.gazeTarget = new THREE.Object3D();

    this.exprWeights = new Map();        // 表情权重,自己算平滑
    this.rest = null;                    // 原始站姿(见 captureRest)
    this.ready = false;

    // 房间里的东西(见 setObjects)。★ 它是**数据**,不是画死的布景。
    this.objRoot = null;                 // 所有物件的父节点,拾取就打在它身上
    this.bbox = null;                    // 她的包围盒,frameCamera 量的;摆放物件要用

    // ---- 换房间布置(见 setRoom / setScenery)------------------------------
    // ★ 四个都默认「没改」。**没有 room.json 的时候,这一段代码一步都不走**,
    //   房间和她本来的样子一个像素都不变 —— 这是「放文件就生效」的地基:
    //   新功能不许改老房间的样子。
    this.roomObjects = null;             // {id: {x,y,z,scale}} —— 只覆盖写了的那几件
    this.roomDist = 1;                   // 相机远近:是**倍数**,不是绝对值(见 setRoom)
    this.roomHeight = 1;                 // 相机高低:也是倍数
    this.roomBackground = null;          // '#rrggbb',盖掉内置那层渐变
    this.roomBackgroundImage = null;     // 'wall.png' —— 一张图当背景(见 setBackdropImage)
    this.scenery = null;                 // 整个房间的模型(room.glb),由 setScenery 装上
    // 内置房间(room.js 现搭的那间屋子)。★ 它是**默认就有的样子**,不是唯一的样子:
    // 用户丢一份 room.glb 进来 → 它整份让位(setScenery 里藏掉);
    // room.json 写 "builtin": false → 关掉(setRoom 里认这一项)。
    this.roomRoot = null;
    this.roomBuiltin = true;             // 它**想不想**被看见(见 boot 里怎么落地)

    // ---- 她的动作(见 setMotion)-----------------------------------------
    // ★ 三样都默认空 = **没放动作文件时这一段代码一步都不走**,
    //   她照旧是原来那套待机微动(呼吸 / 缓慢摆头 / 眨眼)—— 上次验过的样子一个像素不变。
    this.mixer = null;                   // THREE.AnimationMixer,根是 vrm.scene(见 import 那段)
    this.motionAction = null;            // 正在做的那条动作;null = 没有动作
    this.motionName = '';                // 日志用:现在做的是谁
  }

  /**
   * 把「房间里的东西」整批藏起来 / 放出来。
   *
   * ★★ 只有一处会用到它,而那一处非它不可:`captureFigure`(拍悬浮窗那张**透明人形**)。
   *   那张图是把整个场景按 alpha=0 渲一遍,**凡是挂在 scene 上的几何都会被拍进去** ——
   *   包括墙、桌子、手边的物件、room.glb。拍出来就是「一个人形糊在一堵墙上」,
   *   而它不报任何错,只表现为「悬浮窗里那颗球样子很怪」,极难往回查到这儿。
   *
   * 藏的是**几何,不是灯**。灯必须留着 —— 全暗的情况下拍出来的人形是黑的,
   * 拿去当悬浮窗就是一团黑影。这正是 `buildRoom()` 把 root 和 lights **分开返回**
   * 的原因(见 room.js 的说明)。
   *
   * ★ 用 `visible` 而不是 `remove()`:这一趟是**同步**的(见 captureFigure),
   *   加回来/拿下去任何一步出错,房间就永久没了。翻一个布尔是最便宜的还原方式。
   *
   * ★★ **返回「原来各自是什么样」** —— 调用方要照着它还原,不能一律打开。
   *   用户丢过 `room.glb` 时内置房间**本来就该是关的**(setScenery 里关的),
   *   一律打开会让它重新盖回用户那间上面 —— 而且之后每拍一次图就叠一次,
   *   谁也看不出是这儿干的。
   *
   * @returns {Array<[object, boolean]>} 每项是 [那个 Group, 它原来的 visible]
   */
  setRoomVisible(on) {
    const prev = [];
    for (const g of [this.roomRoot, this.objRoot, this.scenery]) {
      if (g) { prev.push([g, g.visible]); g.visible = on; }
    }
    return prev;
  }

  // -------------------------------------------------------------------------
  async boot(canvas, vrmUrl) {
    const renderer = new THREE.WebGLRenderer({
      // ★ alpha 必须是 true(2026-10-04「能不能就是把悬浮窗改成**人形**」)。
      //   拍人形图那一步要把清屏 alpha 设成 0 才拿得到透明底,而 alpha:false 的
      //   绘图缓冲**根本没有 alpha 通道** —— 设 0 也只会得到一块黑。
      //   房间里看不出任何区别:scene.background 是一张铺满的背景贴图,
      //   它把清屏色整个盖住。所以这是「为拍图开一扇窗」,不是改房间的样子。
      canvas, antialias: true, alpha: true,
      powerPreference: 'high-performance',
    });
    // alpha:true 时 three 的清屏 alpha **默认是 0** —— 万一哪天背景贴图没了,
    // 房间会整个变透明。显式按回 1,不留这种「看运气」的默认值。
    renderer.setClearColor(0x000000, 1);
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.15;
    this.renderer = renderer;

    const scene = new THREE.Scene();
    scene.background = this.makeBackdrop();
    // 一点雾,把她身后的空间推远,不然像贴纸贴在墙上
    scene.fog = new THREE.Fog(0x140f12, 3.2, 7.5);
    this.scene = scene;

    const camera = new THREE.PerspectiveCamera(30, 1, 0.05, 40);
    this.camera = camera;
    scene.add(this.gazeTarget);

    // 光:一盏暖主光从侧前方打脸,一盏粉轮廓光从背后勾边,底下补一点,
    // 别让下巴下面死黑。这套配色是照着她那张参考图的暖色调定的。
    const key = new THREE.DirectionalLight(0xffe9d6, 2.4);
    key.position.set(1.4, 2.0, 2.2);
    scene.add(key);
    const rim = new THREE.DirectionalLight(0xff7fb0, 1.5);
    rim.position.set(-1.8, 1.4, -1.6);
    scene.add(rim);
    scene.add(new THREE.HemisphereLight(0xbfd0ff, 0x2a1a22, 1.1));
    const fill = new THREE.PointLight(0xffd9c0, 0.6, 6);
    fill.position.set(0, 1.0, 1.2);
    scene.add(fill);

    // ---- 房间 ----
    // ★ 2026-10-06:在那之前房间里**什么都没有** —— 一层渐变背景、一点雾、
    //   四盏灯,她站在一片虚空里。房间的几何是这一版才有的(见 room.js)。
    //
    // ★★ 几何和灯**分开收着**,这件事在 captureFigure 里是必须的:
    //   拍悬浮窗那张透明人形时,几何要**藏掉**(不然人形会连着一堵墙),
    //   但灯**必须留着**(不然拍出来的人没打光,糊成一片)。
    //   收成一个东西就没法只藏一半了。
    const room = buildRoom();
    this.roomRoot = room.root;
    // ★ 这一项可能在出场**之前**就读到了(它只是「想不想看见内置房间」),
    //   所以落地放在这儿 —— 只写在 setRoom 里的话,先读配置后出场就把它丢了。
    this.roomRoot.visible = this.roomBuiltin;
    scene.add(this.roomRoot);
    for (const l of room.lights) scene.add(l);

    // ---- 加载模型 ----
    const loader = new GLTFLoader();
    loader.register((parser) => new VRMLoaderPlugin(parser));
    const gltf = await loader.loadAsync(vrmUrl);
    const vrm = gltf.userData.vrm;
    if (!vrm) throw new Error('这个文件不是 VRM(没有 VRMC_vrm 扩展)');

    // 官方建议的三件套:去掉用不上的顶点、合并骨架、关掉视锥剔除。
    // 最后一条尤其要命:VRM 的网格包围盒算不准,不关的话她动一下就被裁掉半张脸。
    VRMUtils.removeUnnecessaryVertices(gltf.scene);
    VRMUtils.combineSkeletons(gltf.scene);
    if (vrm.meta?.metaVersion === '0') VRMUtils.rotateVRM0(vrm);
    gltf.scene.traverse((o) => { o.frustumCulled = false; });

    scene.add(vrm.scene);
    this.vrm = vrm;

    // 视线跟着这个空物体走
    if (vrm.lookAt) vrm.lookAt.target = this.gazeTarget;

    this.applyIdlePose();
    this.captureRest();
    this.frameCamera();
    window.addEventListener('resize', () => this.frameCamera());

    // ★ 拾取**在这里**装,不在构造函数里 —— 构造函数跑的时候 renderer 还不存在。
    this.setupPicking();

    this.ready = true;
    this.loop();
    if (window.HerBridge?.onReady) window.HerBridge.onReady();
  }

  // -------------------------------------------------------------------------
  // 背景:一块暖色渐变,画在 canvas 上贴到场景。
  // 不用纯黑 —— 纯黑衬得人像抠图;参考图那种暖褐底子才像「房间」。
  //
  // ★ 换过底色(room.json 的 "background")时,**形状不变,只换调子** ——
  //   理由和上面那句一样:一块纯色衬得人像抠图。所以是把那一个色摊成
  //   同样四段,而不是铺平。
  // -------------------------------------------------------------------------
  makeBackdrop() {
    const c = document.createElement('canvas');
    c.width = 8; c.height = 256;
    const g = c.getContext('2d');
    const grad = g.createLinearGradient(0, 0, 0, 256);
    const stops = this.roomBackground
      ? backdropStops(this.roomBackground)
      : [[0.0, '#1b1418'], [0.45, '#2a1c22'], [0.75, '#3a2429'], [1.0, '#160f13']];
    for (const [at, col] of stops) grad.addColorStop(at, col);
    g.fillStyle = grad;
    g.fillRect(0, 0, 8, 256);
    const tex = new THREE.CanvasTexture(c);
    tex.colorSpace = THREE.SRGBColorSpace;
    return tex;
  }

  /**
   * 换掉房间底色。**只接受 `#rrggbb`**(六个十六进制)。
   *
   * ★ 认不出来的写法一律**当没写**(返回 false,调用方会把原因说出来),
   *   不做「猜一个接近的颜色」那种事 —— 猜错了用户看到的是「我写的它没照做」,
   *   而且没有任何地方会报错。
   */
  setBackdropColor(hex) {
    if (typeof hex !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(hex)) return false;
    this.roomBackground = hex.toLowerCase();
    // ★ 底色和背景图是**同一个位置的两个写法**,所以设了一个就把另一个清掉。
    //   不清的话,图比底色晚一拍回来,屏幕上最后是哪一张就全看时序了 ——
    //   那是「我写了 A 它显示 B」那一类,而且不报错。
    this.roomBackgroundImage = null;
    if (this.scene) {
      const old = this.scene.background;
      this.scene.background = this.makeBackdrop();
      // 旧那张贴图不释放就是换一次漏一张(和 disposeTree 同一个道理)
      if (old && old.dispose) old.dispose();
    }
    return true;
  }

  /**
   * 换掉房间背景:**用一张图**。参数是一个**光秃秃的文件名**(如 `'wall.png'`)。
   *
   * ## 为什么是文件名,不是路径、不是网址
   *
   * 图必须躺在手机上那两个文件夹里(`files/room/` 先找,再找 `files/person/`)——
   * 那条路是 `Wardrobe.resolve` 按**文件名精确找**的(见它文件头那张表)。
   * 所以这里**故意**不认任何带 `/`、`\`、`:` 的写法:认了就等于给自己开一条
   * 「去别处取图」的口子。**写死了,就没有"哪天它能去网上取图"这件事。**
   *
   * ## 返回值说的是「这个写法我认不认」,不是「图读到了没有」
   *
   * 读图是异步的(要等文件真的下来)。所以这里立刻返回 true 表示**写法对**,
   * 真正读没读到**事后写一行日志** —— 成功和失败各说各的话。
   * ★ 失败时**屏幕上原来那层一个像素都不动**:不能因为一张图没读到就先黑一下。
   *
   * ## 一件必须说白的事
   *
   * 背景图是**被拉伸铺满整个画面**的(three.js 的 `scene.background` 就是一张全屏的图),
   * 所以她那个房间是**锁横屏**的、屏幕约 2.2:1 —— 拿 16:9 的图进来左右会拉宽约 24%。
   * ★ 而且它是一张**平面**:你左右动不会有视差,她看着像**贴**在画前面。
   * 这两条是这条路天生就有的,不是没做好。
   */
  setBackdropImage(name) {
    if (typeof name !== 'string') return false;
    const s = name.trim();
    if (!s || /[/\\:]/.test(s)) return false;
    // ★ 只认这几种 —— 要和 ConMarnActivity.serveAsset 里那张 MIME 表对得上,
    //   不然文件读得到、Content-Type 却是 octet-stream,浏览器认不认全看它心情。
    if (!/\.(png|jpe?g|webp)$/i.test(s)) return false;

    this.roomBackgroundImage = s;
    this.roomBackground = null;      // 同上:一个位置一个当前值
    if (!this.scene) return true;

    const url = new URL(s, document.baseURI).href;
    const old = this.scene.background;
    // ★ 整段包起来:一张图读不出来,**绝不许把整份 setRoom 带崩**。
    //   同一份 room.json 里还有相机、物件摆法、builtin —— 它们和这张图毫无关系,
    //   不该跟着一起丢。这和 Kotlin 那边「换装判定出错也照旧读内置那份」是同一条。
    try {
      // 先不动屏幕,等图真下来了再换。
      new THREE.TextureLoader().load(
        url,
        (tex) => {
          tex.colorSpace = THREE.SRGBColorSpace;
          if (!this.scene) return;
          this.scene.background = tex;
          if (old && old.dispose && old !== tex) old.dispose();
          this.note('房间背景:用上了图 ' + s);
        },
        undefined,
        () => {
          this.roomBackgroundImage = null;
          this.note('房间背景:图 ' + s + ' 没读出来。它要在 `files/room/` 或 `files/person/` 里、' +
            '名字一字不差地叫 ' + s + '(png/jpg/webp)。背景照旧用原来那层');
        }
      );
    } catch (e) {
      this.roomBackgroundImage = null;
      this.note('房间背景:图 ' + s + ' 这条路自己出错了(' + (e && e.message ? e.message : e) +
        '),背景照旧用原来那层');
    }
    return true;
  }

  // -------------------------------------------------------------------------
  // 把 T 字站姿改成自然站姿。
  //
  // VRM 模型的默认姿势是两臂平举(T-pose,建模用的),直接摆出来像晾衣架。
  // ★ 不写死「绕 Z 轴转 70 度」那种常量 —— 每个模型骨骼的朝向都不一样,
  //   猜错就是把胳膊拧到身后去。这里改成量着做:取上臂→前臂的**当前指向**,
  //   算出「把它转到朝下」需要的那个旋转,用四元数套上去。跟轴怎么命名无关。
  // -------------------------------------------------------------------------
  applyIdlePose() {
    const vrm = this.vrm;
    const hum = vrm?.humanoid;
    if (!hum) return;
    vrm.update(0);
    vrm.scene.updateMatrixWorld(true);

    const V3 = THREE.Vector3;
    for (const side of ['left', 'right']) {
      const upper = hum.getNormalizedBoneNode(`${side}UpperArm`);
      const lower = hum.getNormalizedBoneNode(`${side}LowerArm`);
      if (!upper || !lower) continue;

      const a = upper.getWorldPosition(new V3());
      const dir = lower.getWorldPosition(new V3()).sub(a);
      if (dir.lengthSq() < 1e-8) continue;
      dir.normalize();

      // 目标朝向:垂下来,略微外张(往外哪边,看它现在指向哪边)
      const out = dir.x >= 0 ? 1 : -1;
      const target = new V3(out * 0.22, -1, 0.05).normalize();

      // 世界空间里「从 dir 转到 target」的那个旋转,套到骨骼当前朝向上,
      // 再换算回它的父节点坐标系 —— 就是骨骼该有的局部旋转。
      const swing = new THREE.Quaternion().setFromUnitVectors(dir, target);
      const parentQ = upper.parent.getWorldQuaternion(new THREE.Quaternion());
      const worldQ = upper.getWorldQuaternion(new THREE.Quaternion());
      const newWorldQ = swing.multiply(worldQ);
      upper.quaternion.copy(parentQ.invert().multiply(newWorldQ));
      upper.updateMatrixWorld(true);
    }
  }

  // -------------------------------------------------------------------------
  // 记住「她本来的站姿」。
  //
  // ★ 下面每一帧的待机微动都必须是「原始姿势 + 偏移」,绝不能写成
  //   `head.rotation.y += yaw` —— 那是**累加**:yaw 是个绝对角度,每帧都往上加,
  //   十几秒就能把她的头拧过 90°(真机上就是这样:头歪着,像落枕)。
  //   three-vrm 不会帮你把 normalized bone 的旋转重置回原始值,所以只能自己存一份。
  // -------------------------------------------------------------------------
  captureRest() {
    const vrm = this.vrm;
    if (!vrm) return;
    vrm.update(0);
    vrm.scene.updateMatrixWorld(true);
    const hum = vrm.humanoid;
    const rot = (n) => {
      const b = hum?.getNormalizedBoneNode(n);
      return b ? b.rotation.clone() : null;
    };
    this.rest = {
      head: rot('head'),
      chest: rot('chest'),
      spine: rot('spine'),
      hipsY: hum?.getNormalizedBoneNode('hips')?.position.y ?? 0,
      sceneZ: vrm.scene.position.z,
    };
  }

  // -------------------------------------------------------------------------
  // 取景:半身构图,头顶在画面上三分之一。
  //
  // ★ 这里踩过一个坑,真机上看得一清二楚:加载完立刻去读骨骼的世界坐标,
  //   读出来是 0 —— 骨骼矩阵一次都还没算过。算出来的「取景距离」于是只有
  //   0.4 米,镜头直接怼到她腰上,屏幕上就剩一截白衣服和一条胳膊。
  //   所以必须先 update 一遍矩阵再量;而且用整个模型的包围盒来量,
  //   比「猜头顶在哪个骨骼上」更不容易错 —— 换一个 VRM 也不用改代码。
  // -------------------------------------------------------------------------
  frameCamera() {
    const vrm = this.vrm;
    if (!vrm) return;
    const canvas = this.renderer.domElement;
    const w = canvas.clientWidth || window.innerWidth;
    const h = canvas.clientHeight || window.innerHeight;
    this.renderer.setSize(w, h, false);
    const aspect = w / h;
    this.camera.aspect = aspect;

    vrm.update(0);
    vrm.scene.updateMatrixWorld(true);

    const box = new THREE.Box3().setFromObject(vrm.scene);
    const H = box.max.y - box.min.y;
    if (!isFinite(H) || H < 0.1) return;   // 量不出来就别乱动相机
    // ★ 留一份给 setObjects 用。它只量 vrm.scene,所以**摆物件不会反过来动相机**
    //   —— 加了空调、加了灯,取景一格都不变。
    this.bbox = box.clone();
    const cx = (box.min.x + box.max.x) * 0.5;

    // 从腰往上到头顶再留一点空。留一点腿也行,但不能让她只剩半个身子。
    const topY = box.max.y + H * 0.06;
    const bottomY = box.min.y + H * 0.42;
    const centerY = (topY + bottomY) * 0.5;
    const viewH = topY - bottomY;

    const halfFov = THREE.MathUtils.degToRad(this.camera.fov * 0.5);
    // 竖直方向要装得下 viewH;横向也得够(她张开手臂、或者横屏很宽时),
    // 取两者里更远的那个 —— 两个方向都不切人。
    const distV = (viewH * 0.5) / Math.tan(halfFov);
    const distH = (H * 0.55) / (Math.tan(halfFov) * aspect);
    // ★ room.json 给的是**倍数**,乘在算出来的取景距离上 —— 不是绝对值。
    //   绝对值看着更"直接",但换个身高不同的 VRM 之后,那个写死的米数
    //   就会把她切成半截。倍数在换人之后**自动跟着走**。
    //   没写 room.json 时两个都是 1,这一行和原来一模一样。
    const dist = Math.max(distV, distH) * this.roomDist;

    this.camera.position.set(cx, centerY * this.roomHeight, dist);
    this.camera.lookAt(cx, centerY - H * 0.01, 0);
    this.camera.updateProjectionMatrix();

    // 雾跟着相机走。写死的 near/far 在相机退远之后会把她整个人糊掉。
    if (this.scene.fog) {
      this.scene.fog.near = dist + 0.15;
      this.scene.fog.far = dist + 4.5;
    }
  }

  // -------------------------------------------------------------------------
  // ★ 2026-10-04:给悬浮窗拍一张「人形」。
  //
  // 用户原话:「**悬浮窗没有她的脸**」,接着在输入框里说全了:
  // 「能不能就是把悬浮窗改成**人形**,然后点一下是下面的进她的房间,还有中转站、快捷启动」。
  //
  // 关键判断:圆头像里那张是**照片**,房间里这个是**她**。
  // 所以这张图**只能从活着的场景里拍** —— 另外找一张图顶上,又变成「另一张脸」,
  // 那样等于没解决他的问题,只是把问题换了个样子。
  //
  // 做法:临时摘背景、摘雾、清屏 alpha 设 0,相机退到装得下**整个人**的位置,
  // 渲染一帧,`toDataURL` 拿透明底 PNG,然后**把现场原样还回去**。
  //
  // ⚠️ 还原那一段是这个函数里最容易漏、也最难看出来的一块:漏了的话房间会变成
  //    透明底、相机停在取景框上 —— 而这些**一样都不会报错**。
  //    所以整段包在 finally 里,而且末尾直接调 frameCamera() 让尺寸/相机/雾一次归位。
  // -------------------------------------------------------------------------
  captureFigure() {
    const vrm = this.vrm, r = this.renderer, sc = this.scene, cam = this.camera;
    if (!vrm || !r || !sc || !cam || !this.bbox) return '';
    const canvas = r.domElement;

    // 出图尺寸:竖长条,正好一个人。别贪大 —— 这张图 base64 之后要走
    // evaluateJavascript 的字符串回传通道,太大有被截的风险。
    const W = 280, H = 700;

    // 记住现场
    const bg = sc.background, fog = sc.fog, aspect0 = cam.aspect;
    const pos0 = cam.position.clone();

    r.setSize(W, H, false);     // false = 不动 CSS,屏幕上的尺寸一点不变
    sc.background = null;       // 摘背景,否则透明底被它糊上
    sc.fog = null;              // 雾会把她的边染成雾色
    r.setClearColor(0x000000, 0);

    // 全身取景。注意这和 frameCamera 的「腰以上」**不是一回事** ——
    // 那里要的是她的表情,这里要的是**整个人**,人形才有「人」的样子。
    const box = this.bbox;
    const bw = box.max.x - box.min.x, bh = box.max.y - box.min.y;
    const cx = (box.min.x + box.max.x) * 0.5, cy = (box.min.y + box.max.y) * 0.5;
    const pad = Math.max(bw, bh) * 0.04;
    const halfFov = THREE.MathUtils.degToRad(cam.fov * 0.5);
    const distV = (bh + pad * 2) * 0.5 / Math.tan(halfFov);
    const distH = (bw + pad * 2) * 0.5 / (Math.tan(halfFov) * (W / H));
    const dist = Math.max(distV, distH);
    cam.aspect = W / H;
    cam.position.set(cx, cy, dist);
    cam.lookAt(cx, cy, 0);
    cam.updateProjectionMatrix();

    // ★★ 把房间里的一切(墙、桌、物件、room.glb)整批藏掉再拍。
    //   不藏的话,悬浮窗里那个「人形」会是一堵墙加一张桌子 ——
    //   而它看起来只是「这张图拍得怪」,不会报任何错。
    //   灯**不藏**(见 setRoomVisible 的说明)。`shown` 是「原来各自什么样」。
    const shown = this.setRoomVisible(false);
    let url = '';
    try {
      r.render(sc, cam);
      // ★ 必须在**同一个同步块**里取图。没有 preserveDrawingBuffer 时,
      //   绘图缓冲在这一帧合成完就被清掉了 —— 分到下一个 tick 再读只会拿到空白。
      //   而 preserveDrawingBuffer 是每帧都要付的代价,为了一张图不值。
      url = canvas.toDataURL('image/png');
    } catch (e) {
      url = '';
    } finally {
      sc.background = bg;
      sc.fog = fog;
      for (const [g, v] of shown) g.visible = v;
      r.setClearColor(0x000000, 1);
      cam.aspect = aspect0;
      cam.position.copy(pos0);
      cam.updateProjectionMatrix();
      this.frameCamera();       // 尺寸 / 相机 / 雾 —— 一次归位,不靠我记得每一项
    }
    return url;
  }

  // =========================================================================
  // 房间里的东西
  //
  // ★★ 这一层**不认识「电脑」**。它只认识 id 和 kind。
  //
  //    清单是 Kotlin 那边从 `HandRegistry.all()` 生成后推过来的 —— 也就是
  //    「房间里摆着什么」和「她会做什么」是**同一份数据**。
  //    加一只空调 = 注册表里多一条,这个文件一个字符都不用改。
  //
  //    反过来做(JS 里画死一份物件、Kotlin 里写死一份能力)就是这个项目
  //    已经吃过一次的「工具表抄三遍」:PC 一份、TOOL_SCHEMA 一份、
  //    SYSTEM_PROMPT 里再抄一份 —— 加一个能力要改三处,而且漏一处不报错。
  //
  //    `kind` 只用来决定「它长什么样」,不用来决定「点了做什么」。
  //    点了做什么是 Kotlin 的事(见 ConMarnActivity.objectTapped)。
  //
  // ★ 收**数组**不收对象:今天一件(电脑),以后 N 件(空调、灯、窗帘),
  //   骨架一次做对,以后加东西不用动这里。
  // =========================================================================

  /** 把物件清单整份换掉。`json` 是字符串或数组,元素形如 `{id, kind, name}`。 */
  setObjects(json) {
    let list;
    try {
      list = typeof json === 'string' ? JSON.parse(json) : json;
    } catch (e) {
      // 静默失败的样子是「房间是空的、点什么都没反应」,和「还没推过来」分不清。
      // 说出去。
      this.note('物件清单解析失败: ' + e.message);
      return;
    }
    if (!Array.isArray(list)) {
      this.note('物件清单不是数组,忽略');
      return;
    }

    if (!this.objRoot) {
      this.objRoot = new THREE.Group();
      this.objRoot.name = 'room-objects';
      this.scene.add(this.objRoot);
    }
    // ★ 整份换掉,不做增量合并。清单本来就是**全量**推过来的;
    //   做增量就得处理「上一版有、这一版没了」的物件 —— 而那种物件
    //   恰恰是最该消失的那个(手不在线了,东西还杵在房间里点得动)。
    for (const old of this.objRoot.children.slice()) {
      this.objRoot.remove(old);
      disposeTree(old);
    }

    const n = list.length;
    let placed = 0;
    list.forEach((item, i) => {
      if (!item || !item.id) return;
      const obj = this.makeObject(item, this.slotX(i, n), i);
      obj.userData.herObjectId = String(item.id);
      // ★ room.json 里**写了**的就按写的摆;没写的一个字都不动(见 roomPlace)。
      //   这行放在 add 之前,是因为 roomPlace 可能读 this.centerX(),
      //   而那件事和父子关系无关 —— 顺序只是让「先摆好再挂上去」读起来顺。
      this.roomPlace(obj, obj.userData.herObjectId);
      this.objRoot.add(obj);
      placed++;
    });

    // ★ 日志里带上**摆出来的实际位置**。这一行是用户写 room.json 时唯一的
    //   「起手数字」—— 没有它,他只能靠猜填 x/y/z,而猜错了的表现是
    //   「东西不见了 / 跑到画外」,**不是报错**。
    const where = list.filter((x) => x && x.id).map((x) => {
      const o = this.objRoot.children.find(
        (c) => c.userData.herObjectId === String(x.id));
      if (!o) return x.id;
      const p = o.position;
      return x.id + '(' + p.x.toFixed(2) + ',' + p.y.toFixed(2) + ',' + p.z.toFixed(2) + ')';
    }).join(', ');
    this.note('房间物件: ' + placed + ' 件 [' + where + ']');
  }

  /** 第 i 件(共 n 件)摆在哪。左右交替、越往外越远 —— 不挡她,也不会两件叠一起。 */
  slotX(i, n) {
    if (n <= 1) return this.centerX() + 0.62;   // 只有一件:放她右手边(书桌那侧)
    const side = (i % 2 === 0) ? 1 : -1;
    const k = Math.floor(i / 2);
    // ★ 夹住:再往外就出画了。真到 5 件以上就该换成真的环形/网格摆法,
    //   那时候这里会挤 —— 挤是看得见的,总比看不见强。
    return this.centerX() + side * Math.min(0.62 + k * 0.22, 0.92);
  }

  centerX() {
    const b = this.bbox;
    return b ? (b.min.x + b.max.x) * 0.5 : 0;
  }

  // =========================================================================
  // 换房间布置(用户要的「换个文件就生效」里,**房间**那一半)
  //
  // ★★ 这一整段是**加**上去的:没有 room.json 的时候它一次都不会被调用,
  //    房间的样子、物件的摆法、相机、背景 —— **一个像素都不变**。
  //    Kotlin 那边负责读文件、判定用不用、把话写进 model.log(见 Wardrobe);
  //    这里只负责照做。
  //
  // ★ 它**不碰物件清单**。房间里摆什么由「她有几只手」决定(Kotlin 的
  //   HandRegistry,见 setObjects 的文件头);这份配置只决定**每一件摆在哪**。
  //   想加一件东西是给她加一只手,不是在这里写一行 —— README 里也是这么说的。
  // =========================================================================

  /**
   * 吃一份 room.json。`json` 是字符串或对象。
   *
   * 认得的只有四项:`builtin` / `background` / `camera` / `objects`。
   * ★ **认不出来的写法一律当没写,并且说出来** —— 这个功能的失败长相全是
   *   「我写了它没照做」,一句提示都没有的话根本分不清是拼错了还是没读到文件。
   */
  setRoom(json) {
    let cfg;
    try {
      cfg = typeof json === 'string' ? JSON.parse(json) : json;
    } catch (e) {
      this.note('房间配置解析失败: ' + e.message);
      return;
    }
    if (!cfg || typeof cfg !== 'object' || Array.isArray(cfg)) {
      this.note('房间配置不是对象,忽略');
      return;
    }

    const did = [];

    // 0) 内置房间开 / 关
    //    ★ 为什么需要「关掉」:内置那间屋子是**默认就会有**的。想做一间
    //      **完全不同**的房间(纯色摄影棚、户外、太空)又不关它,就只能把自己的
    //      模型盖上去 —— 两间屋子在同一个位置互相穿插,而那个样子看起来是
    //      「我的模型做坏了」,不是「里面还藏着一间」。
    if (cfg.builtin !== undefined) {
      if (typeof cfg.builtin === 'boolean') {
        this.roomBuiltin = cfg.builtin;
        if (this.roomRoot) this.roomRoot.visible = cfg.builtin;
        did.push('内置房间 ' + (cfg.builtin ? '开' : '关'));
      } else {
        // ★ 要写 JSON 的 true/false,不是 "true"/"false"。字符串在这里当没写 ——
        //   不然 "false" 会被读成「有值 = 开」,正好反了,而且一句话都不说。
        this.note('房间配置:builtin 要写 true 或 false(不加引号),收到 ' +
          JSON.stringify(cfg.builtin) + ';这一项当没写');
      }
    }

    // 1) 背景 —— **一个字段,两种写法**,而且两种的判据**互不相交**:
    //      `#rrggbb`(六位十六进制)  vs  一个裸图片文件名(`wall.png`)。
    //    所以先试颜色、再试图,不会出现「两种都认」——那才会真的打架。
    if (cfg.background !== undefined) {
      const bg = cfg.background;
      if (this.setBackdropColor(bg)) {
        did.push('背景 ' + this.roomBackground);
      } else if (this.setBackdropImage(bg)) {
        did.push('背景 图 ' + this.roomBackgroundImage + '(没读出来会另说一句)');
      } else {
        this.note('房间配置:background 认不出来(' + JSON.stringify(bg) +
          ')。两种写法 —— 底色:像 "#160f13" 这样的六位十六进制;' +
          '图片:一个**光文件名**(如 "wall.png"),它要躺在 room/ 或 person/ 里;' +
          '这一项当没写');
      }
    }

    // 2) 相机 —— **倍数**,不是米。见 frameCamera 里那段说明。
    const cam = (cfg.camera && typeof cfg.camera === 'object' && !Array.isArray(cfg.camera))
      ? cfg.camera : null;
    if (cam) {
      let camChanged = false;
      // ★ 夹住了就**说出来**。他写 `9`、日志里却看见 `×3`,不解释一句,
      //   下一句一定是「我写了它没照做」—— 而这一整条路上的失败本来就全是静默的。
      //   (写 0 也一样:0 不在 0.3~3.0 里,和写 0.01 是同一种情况,不该特殊对待。)
      const clamped = (wrote) => wrote >= 0.3 && wrote <= 3 ? '' : '(写了 ' + wrote + ',超出 0.3~3.0,夹住)';
      if (isNum(cam.distance)) {
        this.roomDist = clampTo(cam.distance, 0.3, 3);
        did.push('距离 ×' + this.roomDist + clamped(cam.distance)); camChanged = true;
      }
      if (isNum(cam.height)) {
        this.roomHeight = clampTo(cam.height, 0.3, 3);
        did.push('高低 ×' + this.roomHeight + clamped(cam.height)); camChanged = true;
      }
      // ★ 她还没出场时 frameCamera 自己会直接 return(量不到包围盒),
      //   所以这里不用额外判 ready —— 多一道判据就多一处会跟事实对不上的地方。
      if (camChanged) this.frameCamera();
    }

    // 3) 每一件摆在哪
    if (cfg.objects && typeof cfg.objects === 'object' && !Array.isArray(cfg.objects)) {
      this.roomObjects = cfg.objects;
      did.push('摆法 ' + Object.keys(cfg.objects).length + ' 件');
      // ★ 物件可能**已经**摆出来了(setRoom 和 setObjects 的先后不保证)。
      //   这时要回头把新摆法补套上去 —— 漏了这一步的表现是
      //   「配置读到了、日志也写了,可房间没变」,而且**不报任何错**。
      if (this.objRoot) {
        for (const o of this.objRoot.children) this.roomPlace(o, o.userData.herObjectId);
      }
    }

    this.note(did.length
      ? '房间布置: ' + did.join(' · ')
      : '房间配置里没有一项认得的(能写的是 builtin / background / camera / objects)');
  }

  /**
   * 整个房间的模型(`room.glb` / `room.gltf`)。
   *
   * ★ **只有 Kotlin 那边确认过文件在,才会调这里** —— 所以这里不去「试一下
   *   万一有呢」。那样每次出场都要打一个 404,而且控制台里那行红字
   *   会把真正的错误淹掉。
   *
   * 模型按**米**、原点在她脚下(和 VRM 同一套坐标)。这一点写在 room/README.txt 里
   * —— 不写的话用户做出来的房间会是「一堵墙贴在她脸上」,而且看不出是尺寸问题。
   */
  async setScenery(url) {
    if (!this.scene || !url) return;
    let gltf;
    try {
      gltf = await new GLTFLoader().loadAsync(url);
    } catch (e) {
      // ★ 走到这儿 = Kotlin 确认过文件在、却还是没加载成功(格式不对 / 传了一半)。
      //   不说出来的话,用户看到的是「我把房间丢进去了,它没换」而房间里一切正常。
      this.note('房间模型加载失败: ' + (e?.message || e));
      return;
    }
    const root = gltf.scene || gltf.scenes?.[0];
    if (!root) { this.note('房间模型里没有场景,忽略'); return; }

    if (this.scenery) {
      this.scene.remove(this.scenery);
      disposeTree(this.scenery);
    }
    // 房间整个换掉了,视锥剔除反而害事(它的包围盒是按零件算的)
    root.traverse((o) => { o.frustumCulled = false; });
    this.scenery = root;
    this.scene.add(root);

    // ★★ 内置那间屋子**整份让位**。不藏的话是两间屋子叠在一起:
    //   用户的墙和我们的墙在同一个位置互相穿插,而它看起来不像「叠了两间」,
    //   像「这个模型做坏了」—— 用户最难怀疑到的就是「我自己写的那间还在里面」。
    this.roomBuiltin = false;
    if (this.roomRoot) this.roomRoot.visible = false;

    // ★ 关雾。雾的 near/far 是按「她一个人」那点取景距离算的(frameCamera),
    //   换成几米宽的屋子之后,墙和家具会被整个糊掉 ——
    //   而糊掉的东西看起来**就像「房间没加载出来」**。
    this.scene.fog = null;
    this.note('房间模型:已换上(雾关掉了,否则墙会被糊没)' +
      (this.roomRoot ? ' · 内置房间让位' : ''));
  }

  /**
   * 她要做的**动作**(`motion/*.vrma`,VRM Animation)。
   *
   * ★★ 为什么这是**唯一**一件由 Kotlin 主动推、而不是页面自己来请求的东西:
   *   模型和房间模型都有个自然的请求方(页面知道该请求哪个名字);
   *   而动作没有 —— 页面不知道用户放了什么、也不知道该请求哪个文件名。
   *   所以 `pushMotion` 先查盘,确认文件在,才调到这里。
   *
   * ★ 走到这儿说明**文件确实在**(Kotlin 查过)。所以这里不「试一下万一有呢」——
   *   那样每次出场都打一个 404,而控制台里那行红字会把真正的错误淹掉。
   *   它是 `setScenery` 的同一条规矩。
   *
   * ★★ 和待机微动的关系(这一条最容易做错):
   *   动作文件里写的是**绝对姿势**(胳膊抬到哪、腿迈到哪),而 update() 里那套
   *   待机微动每帧都在重写 head/chest/spine/hips —— 两边同时写同几根骨头,
   *   结果是一抖一抖地互相打架。所以动作一开始,update() 里那一段整块让位
   *   (见那边的 `motionOn` 判断)。
   *   ★ 但**眨眼 / 表情 / 口型 / 视线一样都不让** —— 那些走的是
   *     expressionManager 和 lookAt,不是骨骼,和动作各管各的。
   *     所以她是「一边做动作一边说话一边眨眼」,不是「做动作时变成木头人」。
   */
  async setMotion(url) {
    const vrm = this.vrm;
    if (!this.scene || !vrm || !url) return;

    let gltf;
    try {
      const loader = new GLTFLoader();
      loader.register((parser) => new VRMAnimationLoaderPlugin(parser));
      gltf = await loader.loadAsync(url);
    } catch (e) {
      // ★ 走到这儿 = Kotlin 确认过文件在、却还是没读进来(格式不对 / 传了一半)。
      //   不说出来的话,用户看到的是「我把动作丢进去了,她没动」——
      //   而那个和「你没放对文件夹」在屏幕上一模一样。
      this.note('动作加载失败: ' + (e?.message || e));
      return;
    }

    // GLTFLoader 把解出来的动作放在 userData.vrmAnimations(是个数组)。
    // ★ 一个 .vrma 文件正常只有一段动作。多于一段是**我们不认识的形状** ——
    //   静默挑第一段的话,用户会以为后面那几段也生效了。
    const anims = gltf.userData?.vrmAnimations;
    if (!Array.isArray(anims) || anims.length === 0) {
      this.note('这个文件里没有 VRM 动作(缺 VRMC_vrm_animation 扩展),忽略');
      return;
    }
    if (anims.length > 1) {
      this.note('动作文件里有 ' + anims.length + ' 段,只用第一段(其余没用上)');
    }

    let clip;
    try {
      // 把 VRM Animation 翻译成普通 AnimationClip。
      // ★ 它会自己去找标准化骨骼节点 —— 找不到的那几条轨道它**不会报错**,
      //   只是那条轨道不存在(表现成「有的骨头不动」)。
      clip = createVRMAnimationClip(anims[0], vrm);
    } catch (e) {
      this.note('动作翻译失败: ' + (e?.message || e));
      return;
    }

    // ★ 根必须是 vrm.scene —— 标准化骨骼就挂在它底下(见文件头 import 那段)。
    //   挂在 scene 上的话 PropertyBinding 找不到那些节点,结果是**一条轨道都不生效**,
    //   而它不报错,只表现成「她不动」。
    if (!this.mixer) this.mixer = new THREE.AnimationMixer(vrm.scene);
    // 换动作时把上一条**停掉再换**,不是叠上去 —— 两条动作同时在跑
    // 就是一组骨头被两个方向扯,画面上是抽搐。
    if (this.motionAction) this.motionAction.stop();

    const action = this.mixer.clipAction(clip);
    // 循环播。VRMA 里那种「站一会儿、动一下」的待机动作只有循环才一直有东西看;
    // 播一次就停在最后一帧 = 又变回一尊蜡像。
    action.setLoop(THREE.LoopRepeat, Infinity);
    action.reset();
    action.play();
    this.motionAction = action;
    this.motionName = url.split('/').pop() || url;

    this.note('动作:已换上 ' + this.motionName +
      '(时长 ' + clip.duration.toFixed(1) + 's,循环播;待机微动让位)');
  }

  /**
   * 把 room.json 里那一件的摆法,套到这个已经造好的物件上。
   *
   * ★ 判据收窄成「**确实是个数**」。放宽成 `Number(ov.x)` 的话,JSON 里的
   *   `null` 会被当成 0 —— 于是「我没写这一项」变成「把它挪到原点」,
   *   而且**静默**。缺省、字符串、null 一律当没写。
   */
  roomPlace(obj, id) {
    if (!obj || !id) return;
    const ov = this.roomObjects ? this.roomObjects[String(id)] : null;
    if (!ov || typeof ov !== 'object' || Array.isArray(ov)) return;

    const x = isNum(ov.x) ? ov.x : null;
    const y = isNum(ov.y) ? ov.y : null;
    const z = isNum(ov.z) ? ov.z : null;
    const sc = isNum(ov.scale) ? ov.scale : null;
    if (x !== null) obj.position.x = x;
    if (y !== null) obj.position.y = y;
    if (z !== null) obj.position.z = z;
    if (sc !== null && sc > 0.01) obj.scale.setScalar(sc);

    // ★ 挪过位置之后朝向要**重算**。原来那个朝向是按它自己的槽位算的,
    //   把它搬到另一边之后它会朝着空气侧身 —— 而那看着像「模型坏了」。
    obj.rotation.y = -Math.sign(obj.position.x - this.centerX() || 1) * 0.32;
  }

  /**
   * 造一件东西。**素色占位方块** —— 不画贴图、不做美术。
   *
   * 但形状照着 kind 挑一下(显示器 / 一块竖板 / 方块),因为一个纯立方体
   * 摆在房间里,用户根本不知道哪个是电脑 —— 那就不是「东西自己会说话」,
   * 是「东西不说话」。这一步几乎不花成本,但让它从「一堆方块」变成「一台电脑」。
   */
  makeObject(item, x, i) {
    const g = new THREE.Group();
    const b = this.bbox;
    const H = b ? (b.max.y - b.min.y) : 1.6;
    // 台面高度:取视野下缘再抬一点。frameCamera 的取景下缘是 min.y + 0.42H,
    // 摆在那儿会被切掉半截,所以抬到 0.46H。
    const baseY = (b ? b.min.y : 0) + H * 0.46;

    g.position.set(x, baseY, 0.12);
    // 朝镜头偏一点。纯正面朝前会像贴在墙上的贴纸,侧一点才有体积感。
    g.rotation.y = -Math.sign(x - this.centerX() || 1) * 0.32;

    const color = OBJECT_COLORS[i % OBJECT_COLORS.length];
    const mat = new THREE.MeshStandardMaterial({
      color, roughness: 0.52, metalness: 0.18,
      // 背景是暗的(见 makeBackdrop),不补一点自发光它会黑成一坨
      emissive: new THREE.Color(color).multiplyScalar(0.22),
    });
    const S = 0.20;   // 基准尺寸(米)。约合屏幕上 200px —— 手指点得中。

    if (item.kind === 'windows-pc') {
      // 屏幕 + 脖子 + 底座。三个素色方块,不画贴图,但一眼看得出是台电脑。
      const screen = new THREE.Mesh(new THREE.BoxGeometry(S * 1.35, S * 0.85, S * 0.10), mat);
      screen.position.y = S * 1.16;
      const neck = new THREE.Mesh(new THREE.BoxGeometry(S * 0.16, S * 0.44, S * 0.16), mat);
      neck.position.y = S * 0.48;
      const base = new THREE.Mesh(new THREE.BoxGeometry(S * 0.85, S * 0.08, S * 0.55), mat);
      base.position.y = S * 0.06;
      g.add(screen, neck, base);
    } else if (item.kind === 'android-self') {
      const slab = new THREE.Mesh(new THREE.BoxGeometry(S * 0.62, S * 1.15, S * 0.09), mat);
      slab.position.y = S * 0.62;
      g.add(slab);
    } else {
      const cube = new THREE.Mesh(new THREE.BoxGeometry(S, S, S), mat);
      cube.position.y = S * 0.5;
      g.add(cube);
    }
    return g;
  }

  /** 从被命中的 mesh 往上找到那件东西的 id。 */
  idOf(obj) {
    for (let o = obj; o; o = o.parent) {
      if (o.userData && o.userData.herObjectId) return o.userData.herObjectId;
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // 拾取:从零写的 40 行。
  //
  // ★ 全项目原本**一次都没用过 Raycaster**,所以这里没有先例可抄,注意两点:
  //
  // 1. **不能只在 `pointerdown` 上判定。** 手指按下去时抖个几像素是常事,
  //    按下去就算点中的话,以后加拖动(拖她、转视角)会误触。
  //    所以按下记位置,抬起时量位移,超过阈值(CICK_MOVE_PX)就不算点击。
  //    阈值按**屏幕像素**算,不按米 —— 手感是屏幕上的事。
  // 2. **点空也要报。** 「点空白 = 开关控件栏」是现在唯一能唤出输入框的路,
  //    在 Kotlin 那边(见 ConMarnActivity),这里只负责把「点空了」这件事说出去。
  // -------------------------------------------------------------------------
  setupPicking() {
    const canvas = this.renderer.domElement;
    const ray = new THREE.Raycaster();
    const ndc = new THREE.Vector2();
    let down = null;

    const toNdc = (ev) => {
      const r = canvas.getBoundingClientRect();
      ndc.x = ((ev.clientX - r.left) / r.width) * 2 - 1;
      ndc.y = -((ev.clientY - r.top) / r.height) * 2 + 1;
      return ndc;
    };

    canvas.addEventListener('pointerdown', (ev) => {
      down = { x: ev.clientX, y: ev.clientY };
    });

    canvas.addEventListener('pointercancel', () => { down = null; });

    canvas.addEventListener('pointerup', (ev) => {
      const d = down;
      down = null;
      if (!d) return;
      if (Math.hypot(ev.clientX - d.x, ev.clientY - d.y) > CLICK_MOVE_PX) return;  // 是拖动,不是点

      let hits = [];
      if (this.objRoot) {
        ray.setFromCamera(toNdc(ev), this.camera);
        hits = ray.intersectObjects(this.objRoot.children, true);
      }
      if (hits.length) {
        const id = this.idOf(hits[0].object);
        if (id) {
          if (window.HerBridge?.onObjectTapped) window.HerBridge.onObjectTapped(id);
          return;
        }
      }
      // 没点中东西 —— 包括「房间还是空的」。空处照旧开合控件栏。
      if (window.HerBridge?.onEmptyTapped) window.HerBridge.onEmptyTapped();
    });
  }

  /** 回 Kotlin 一句「不是错误、但你想知道」的话。没有通道就退回 console。 */
  note(msg) {
    if (window.HerBridge?.onNote) window.HerBridge.onNote(String(msg));
    else console.log('[her] ' + msg);
  }

  // -------------------------------------------------------------------------
  // 对外接口
  // -------------------------------------------------------------------------
  setTalking(on) {
    this.talking = !!on;
    if (!on) {
      this.mouthOpen.set(0);
      this.speakChars = { start: 0, end: 0, at: 0 };
      this.mouthRms = -1; this.mouthRmsAt = 0;   // 下一句从「还没量到」重新开始
    }
  }

  // TTS 报告现在念到哪几个字了(UtteranceProgressListener.onRangeStart)
  speakRange(start, end, text) {
    this.speakChars = { start, end, at: performance.now() };
    if (text) this.speakText = text;
  }

  // ★★ 原生侧把**正要播出去的那块音频**的响度推过来(sherpa 合成回调里的 RMS)。
  //
  //   这条路比 speakRange 准,而且是**天生同步**的:响度和声音是同一块数据,
  //   不存在「她说第 5 个字了」和「扬声器实际放到哪」之间的那点漂移。
  //   在这台机器上它尤其重要 —— 系统 TTS **一个中文音色都没有**,
  //   onRangeStart 大概率根本不报,那时 speakRange 那条路是死的。
  //
  //   level: 0..1(0 = 静音,该闭嘴;1 = 张满)
  setMouth(level) {
    this.mouthRms = clamp01(level);
    this.mouthRmsAt = performance.now();
  }

  setEmotion(name, weight = 1) {
    this.emotion = name || 'neutral';
    this.emotionW = clamp01(weight);
  }

  setListening(on) {
    this.listening = !!on;
    // 他在说话 -> 她不吭声,凑近一点,看着他的方向
    this.lean.set(this.listening ? 1 : 0);
  }

  // 摄像头里他的脸在哪(-1..1,0 是正中)
  lookAtUser(nx, ny) {
    this.gaze.x = Math.max(-1, Math.min(1, nx ?? 0));
    this.gaze.y = Math.max(-1, Math.min(1, ny ?? 0));
  }

  setPresence(p) { this.presence = clamp01(p); }

  // -------------------------------------------------------------------------
  // 每帧
  // -------------------------------------------------------------------------
  loop = () => {
    requestAnimationFrame(this.loop);
    if (!this.ready) return;
    // 后台/息屏时不要空转烧电
    if (document.hidden) return;
    const dt = Math.min(this.clock.getDelta(), 0.1);   // 卡一下也别让物理炸掉
    const t = this.clock.elapsedTime;
    this.update(dt, t);
    this.renderer.render(this.scene, this.camera);
  };

  update(dt, t) {
    const vrm = this.vrm;
    const em = vrm.expressionManager;
    const hum = vrm.humanoid;

    // ---- 呼吸 ----
    // 一秒多一次,不是匀速正弦 —— 吸快呼慢才像活人
    this.breathePhase += dt * (this.talking ? 1.35 : 1.0);
    const br = Math.sin(this.breathePhase * Math.PI * 2 * 0.26);
    const breath = br * 0.5 + 0.5;

    // ---- 待机微动 ----
    // 头一直在极慢地飘,幅度很小(1~3 度)。没有这个,她会像一尊蜡像。
    const swayAmp = this.listening ? 0.35 : 1.0;
    const yaw = wobble(t * 0.42, 1.7) * 0.035 * swayAmp;
    const pitch = wobble(t * 0.31, 4.2) * 0.028 * swayAmp;
    this.headYaw.set(yaw);
    this.headPitch.set(pitch + (this.listening ? 0.035 : 0) + breath * 0.006);

    const lean = this.lean.step(dt);
    const rest = this.rest;
    const head = hum?.getNormalizedBoneNode('head');

    // ★★ 有动作在播的时候,**下面这一整块让位**(见 setMotion 的说明)。
    //   动作文件写的是绝对姿势,而这几行每帧都在重写同几根骨头 ——
    //   两边同时写,画面是一抖一抖地互相打架。
    //   ★ 让掉的只有「姿势」这一组:head / chest / spine 的旋转、hips 的高低、
    //     和整个场景的前后位移。**眨眼 / 表情 / 口型 / 视线一样都不让** ——
    //     它们走的是 expressionManager 和 lookAt,不是骨头,和动作各管各的。
    //     所以她是「一边做动作一边说话一边眨眼」,不是「做动作时变成木头人」。
    const motionOn = !!this.motionAction;
    if (!motionOn) {
      if (head && rest?.head) {
        // 原始姿势 + 偏移(见 captureRest 的说明:这里写成 += 会把她拧歪)
        head.rotation.set(
          rest.head.x + this.headPitch.step(dt),
          rest.head.y + this.headYaw.step(dt),
          rest.head.z + wobble(t * 0.23, 7.7) * 0.02 + (this.listening ? 0.04 : 0),
        );
      }
      const chest = hum?.getNormalizedBoneNode('chest');
      if (chest && rest?.chest) {
        chest.rotation.set(
          rest.chest.x - breath * 0.022 - lean * 0.05,
          rest.chest.y,
          rest.chest.z + wobble(t * 0.19, 3.1) * 0.012,
        );
      }
      const spine = hum?.getNormalizedBoneNode('spine');
      if (spine && rest?.spine) {
        spine.rotation.set(
          rest.spine.x + breath * 0.010, rest.spine.y, rest.spine.z,
        );
      }
      const hips = hum?.getNormalizedBoneNode('hips');
      if (hips && rest) hips.position.y = rest.hipsY + breath * 0.004;

      // 凑近:整个上半身朝镜头压一点点
      if (rest) vrm.scene.position.z = rest.sceneZ + lean * 0.006;
    }

    // ---- 眨眼 ----
    // 真人 2~6 秒一次,不是钟表。连着眨两下也很常见,这里用短间隔模拟。
    this.blinkTimer += dt;
    if (this.listening) this.blinkTimer += dt * 0.6;   // 专注听的时候眨得少一点
    if (this.blinkTimer >= this.nextBlinkIn) {
      this.blinkTimer = 0;
      this.nextBlinkIn = 1.8 + Math.random() * 3.4;
      this.blink.jump(0); this.blink.set(1);
      // 一次性眨眼:到 1 之后立刻回落,靠 Smoothed 的快速度做出「闭-开」
      setTimeout(() => this.blink.set(0), 70 + Math.random() * 50);
    }
    const blinkV = this.blink.step(dt);

    // ---- 说话 ----
    let mouth = 0;
    if (this.talking) {
      const text = this.speakText || '';
      const now = performance.now();
      // TTS 还没回报区间(或者根本没报)时,退化成按时间走的口型循环,
      // 至少不是一张死嘴
      const stale = now - (this.speakChars.at || 0) > 400;
      let viseme;
      if (!stale && text) {
        const idx = Math.max(0, Math.min(text.length - 1, this.speakChars.start));
        viseme = visemeFor(text[idx]);
      } else {
        viseme = FALLBACK_VISEMES[Math.floor(t * 7.3) % FALLBACK_VISEMES.length];
      }
      // 元音开合幅度:aa/ou/oh 张得大,ih/ee 张得小
      const openness = { aa: 1.0, ou: 0.85, oh: 0.75, ee: 0.45, ih: 0.35 }[viseme] ?? 0.6;
      // ★ 幅度用哪一份:有真实响度就用真实的。
      //   300ms 是「这块音频还在播」的判据 —— 合成一块大约几十毫秒,
      //   隔了 300ms 还没来新的,说明这条路断了(不是她在静音),该退回老路。
      if (now - (this.mouthRmsAt || 0) < 300) {
        // ★★ 幅度是真的(来自正在播的那块波形),**形状**还是按元音走 ——
        //   两者相乘才是对的样子:只给幅度的话,她张嘴但不知道张成哪个元音;
        //   只给形状的话,就是原来那条和声音无关的假包络。
        //   0.35 那个下限是留给「响度到 0 的那一瞬」的:真要给 0,
        //   字和字之间的闭嘴会关得太死,看着像在打拍子。
        mouth = clamp01(this.mouthRms) * (0.35 + 0.65 * openness);
      } else {
        // 音节包络:说话不是一个恒定的开口度,是快起快落
        const env = 0.55 + 0.45 * Math.abs(Math.sin(t * 11.0));
        mouth = openness * env;
      }
      this.currentViseme = viseme;
    }
    // 嘴开合平滑(快),但绝不能停在中途 —— 停住就是「张着嘴发呆」
    this.mouthOpen.set(mouth);
    const open = this.mouthOpen.step(dt);

    // ---- 表情权重 ----
    // 情绪和口型是两套槽位,可以同时生效(她可以「笑着说」)。
    const targets = new Map();
    const ew = this.emotionW;
    if (this.emotion && this.emotion !== 'neutral') targets.set(this.emotion, ew);
    // 在听的时候自然一点:放松 + 一点点专注(用 surprised 的微剂量当「抬起眼」)
    if (this.listening) {
      targets.set('relaxed', Math.max(targets.get('relaxed') || 0, 0.55));
    }
    // 情绪不该和眨眼抢槽位,blink 单独驱动
    for (const name of ['happy', 'sad', 'angry', 'relaxed', 'surprised', 'neutral']) {
      const want = targets.get(name) || 0;
      const cur = this.exprWeights.get(name) || 0;
      // 情绪过渡要慢(人是慢慢笑开的),但收回去可以快一点
      const speed = want > cur ? 2.2 : 3.5;
      const next = cur + (want - cur) * (1 - Math.exp(-speed * dt));
      this.exprWeights.set(name, next);
      if (em) em.setValue(name, next);
    }
    if (em) em.setValue('blink', blinkV);

    // ---- 口型槽位 ----
    // 五个元音按权重混合,而不是硬切 —— 硬切会有「咔」的一声视觉顿挫
    const vis = this.currentViseme;
    for (const v of ['aa', 'ih', 'ou', 'ee', 'oh']) {
      const w = this.talking ? (v === vis ? open : 0) : 0;
      if (em) em.setValue(v, w);
    }

    // ---- 视线 ----
    // 她在看谁:听他说话时看摄像头(他),否则视线自己飘(像在想事)
    if (this.gazeTarget) {
      const lookUser = this.presence * (this.listening ? 1.0 : 0.55);
      const driftX = wobble(t * 0.27, 11.3) * 0.35 * (1 - lookUser);
      const driftY = wobble(t * 0.21, 13.9) * 0.25 * (1 - lookUser);
      const gx = this.gaze.x * lookUser + driftX;
      const gy = this.gaze.y * lookUser + driftY;
      const headPos = head ? head.getWorldPosition(new THREE.Vector3()) : new THREE.Vector3(0, 1.4, 0);
      // 目标放在她前方 1 米处,按 -1..1 偏移 —— 她的眼睛就跟着转
      this.gazeTarget.position.set(headPos.x + gx * 0.55, headPos.y + gy * 0.35, headPos.z + 1.0);
    }

    // ---- 她的动作 ----
    // ★ 必须在 vrm.update(dt) **之前**。顺序是有道理的:
    //   mixer 写的是**标准化骨骼**(Normalized_xxx,那就是 setMotion 建它时传 vrm.scene 的原因),
    //   vrm.update 负责把标准化骨骼**拷到真骨头**上、再算弹簧骨和视线。
    //   反过来写会慢一帧 —— 而且弹簧骨会拿**上一帧**的姿势去算,头发会抖得很怪。
    if (this.mixer) this.mixer.update(dt);

    // 交给 three-vrm 收尾:骨骼矩阵、弹簧骨、视线骨骼全在这
    vrm.update(dt);
  }
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------
const her = new Her();
window.__her = her;

window.addEventListener('error', (e) => {
  // 出错要让她「说」出来 —— WebView 里的报错在 logcat 里很难找,
  // 把消息抛回 Kotlin 侧直接弹出来,省半小时瞎猜。
  if (window.HerBridge?.onError) window.HerBridge.onError(String(e.message || e));
});

(async () => {
  const canvas = document.getElementById('her-canvas');
  const vrmUrl = new URL('sample.vrm', document.baseURI).href;
  try {
    await her.boot(canvas, vrmUrl);
  } catch (err) {
    if (window.HerBridge?.onError) window.HerBridge.onError(String(err?.message || err));
  }
})();

window.Her = {
  setTalking: (on) => her.setTalking(on),
  speakRange: (s, e, text) => her.speakRange(s, e, text),
  setMouth: (level) => her.setMouth(level),
  setEmotion: (n, w) => her.setEmotion(n, w),
  setListening: (on) => her.setListening(on),
  lookAtUser: (x, y) => her.lookAtUser(x, y),
  setPresence: (p) => her.setPresence(p),
  setObjects: (json) => her.setObjects(json),
  // 换房间布置(见 setRoom / setScenery 的文件头)。★ 两个都是**可选的**:
  // 手机上没有 room/ 那个文件夹、或者里面是空的,这两个一次都不会被调到。
  setRoom: (json) => her.setRoom(json),
  setScenery: (url) => her.setScenery(url),
  setMotion: (url) => her.setMotion(url),
  // ★ 拍一张透明底的人形(悬浮窗用)。同步返回一个 dataURL 字符串;
  //   她还没出场 / 量不到包围盒时返回空串 —— **调用方必须当「没有」处理**,
  //   别把空串当成图存下去,那样悬浮窗会变成一块空白。
  captureFigure: () => her.captureFigure(),
};
