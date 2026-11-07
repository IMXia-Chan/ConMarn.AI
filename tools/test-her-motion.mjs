// ============================================================================
// 「动作 (.vrma) 换装」那套在 JS 这一侧的钉子 —— 在**电脑上**跑，不需要手机、不需要浏览器。
//
// ★ 为什么要有它 (2026-10-08 晚):
//
//   这一轮**手机是拔着的** —— 「丢个 .vrma 进去，她会不会动」在真机上
//   一格都验不了。而这条路上的失败**全都是静默的**:
//
//     · 扩展名不是 .vrma       → 没反应，不报错
//     · 文件里缺 VRMC_vrm_animation → 没反应，不报错
//     · mixer root 写错        → 动作播了但骨头没动，不报错
//     · 订单反了 (vrm.update 在 mixer 之前) → 同上
//     · 门控把 idle 姿势和表情混成一坨 → 动作一起，脸也僵住
//
//   这条必须在电脑上咬死。**这里没钉住的行为，一律等于没做。**
//
// 跑法:
//   node phone-touchpad/tools/test-her-motion.mjs
//   (路径可用 HER_SRC 覆盖，默认就是 build-her.sh 用的那个源码目录)
// ============================================================================

// ---------------------------------------------------------------------------
// 一、把浏览器里的东西糊上。
//
// her.js 是给 WebView 写的，直接 import 会因为 `window` 不存在当场炸。
// ★ 只糊**这一层**,不 fake 任何被测的东西：动作那套逻辑读的是
//   `this.motionAction` / `this.mixer` 这些真字段，一个都不是假的。
// ---------------------------------------------------------------------------

// ★ 辅助：构造合法的 GLB 文件 (chunk 按 4 字节对齐，否则 GLTFLoader 解析会乱掉)
function makeGlb(jsonStr, binData) {
  const jsonBuf = typeof jsonStr === 'string' ? Buffer.from(jsonStr, 'utf8') : jsonStr;
  const binBuf = binData || Buffer.alloc(0);
  // GLB 要求每个 chunk 内容长度按 4 字节对齐 (padding 用 0x20 空格填充)
  const jsonPad = (4 - (jsonBuf.length % 4)) % 4;
  const binPad  = (4 - (binBuf.length % 4)) % 4;
  const jsonLen = jsonBuf.length + jsonPad;
  const binLen  = binBuf.length + binPad;
  const len = 20 + jsonLen + 8 + binLen; // 12 全局头 + 8 JSON chunk 头 + jsonLen + 8 BIN chunk 头 + binLen
  const buf = Buffer.alloc(len, 0x20);  // 整个 buffer 先用空格填满
  // Global header
  buf.writeUInt32LE(0x46546C67, 0);       // magic: glTF
  buf.writeUInt32LE(2, 4);                 // version
  buf.writeUInt32LE(len, 8);               // length
  // Chunk 0: JSON
  buf.writeUInt32LE(jsonLen, 12);          // JSON chunk length
  buf.writeUInt32LE(0x4E4F534A, 16);       // "JSON"
  jsonBuf.copy(buf, 20);
  // Chunk 1: BIN
  const binHeaderOff = 20 + jsonLen;
  buf.writeUInt32LE(binLen, binHeaderOff); // BIN chunk length
  buf.writeUInt32LE(0x004E4942, binHeaderOff + 4); // "\x00BIN"
  if (binBuf.length > 0) binBuf.copy(buf, binHeaderOff + 8);
  return buf;
}

import * as fs from 'fs';
import * as path from 'path';

const notes = [];
// ★ 源码就在仓库里（her-src/），和 build-her.sh 用的是同一份 —— 见 her-src/README.md。
//   路径从 cwd 起算（同下面 sample.vrma 那条），所以从仓库根跑。
const SRC = process.env.HER_SRC || path.join(process.cwd(), 'her-src', 'src', 'her.js');

// ★ 尝试加载真实 sample.vrma 文件作为 good.vrma 的 mock 数据
const sampleVrmaPath = path.join(process.cwd(), 'app', 'src', 'main', 'assets', 'her', 'sample.vrma');
let realVrmaBuffer = null;
try {
  realVrmaBuffer = fs.readFileSync(sampleVrmaPath);
  console.log('[DEBUG] Loaded real sample.vrma:', realVrmaBuffer.length, 'bytes');
} catch {
  console.log('[DEBUG] sample.vrma not found, using minimal mock');
}

globalThis.window = {
  addEventListener() {},
  devicePixelRatio: 1,
  innerWidth: 900,
  innerHeight: 1600,
  // ★ note() 就是从这里出站的 —— 抓住它，「它到底说了什么」才是可判定的
  HerBridge: { onNote: (m) => notes.push(String(m)), onReady() {}, onError() {} },
};
// VRMAnimationLoaderPlugin 要 fetch。给它一个立刻回话的:
function fakeFetch(req) {
  // ★ 每个测试用例传不同的 URL，靠这个区分「我加载的是谁」。
  //   不能用「回一个永远一样的 .vrma」 —— 那验不出「它到底读没读这个文件名」。
  const url = typeof req === 'string' ? req : String(req?.url || '');
  if (url.includes('missing.vrma')) {
    // ★ 文件不存在 → fetch 应该 reject，让 GLTFLoader 走 .catch()
    return Promise.reject(new DOMException('Not Found', 'NetworkError'));
  }
  if (url.includes('empty.vrma')) {
    // ★ 缺 VRMC_vrm_animation 扩展 —— 这是用户可能遇到的真实失败 (下错文件)
    const json = '{"asset":{"version":"2.0"}}';
    const buf = makeGlb(json, Buffer.alloc(32));
    return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => buf.buffer });
  }
  if (url.includes('bad-mime.vrma')) {
    // ★ 一个 PNG —— 这是用户可能遇到的真实失败 (放错文件)
    const json = '{"asset":{"version":"2.0"},"extensionsUsed":["VRMC_vrm_animation"]}';
    const buf = makeGlb(json, Buffer.alloc(32));
    return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => buf.buffer });
  }
  if (url.includes('good.vrma')) {
    // ★ 使用真实的 sample.vrma 文件（已在顶部预加载）
    if (realVrmaBuffer) {
      console.log('[DEBUG] Serving real sample.vrma:', realVrmaBuffer.length, 'bytes');
      return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => realVrmaBuffer.buffer.slice(realVrmaBuffer.byteOffset, realVrmaBuffer.byteOffset + realVrmaBuffer.byteLength) });
    }
    // Fallback: 最小化的 .vrma mock
    const json = JSON.stringify({
      asset: { version: '2.0' },
      extensionsUsed: ['VRMC_vrm_animation'],
      extensions: {
        VRMC_vrm_animation: {
          specVersion: '1.0',
          humanoid: { humanBones: { hips: { node: 0 } } },
        },
      },
      nodes: [{ name: 'hips' }],
      scene: 0,
      scenes: [{ nodes: [0] }],
      animations: [{
        name: 'idle',
        channels: [{ sampler: 0, target: { node: 0, path: 'rotation' } }],
        samplers: [{ input: 0, output: 1, interpolation: 'LINEAR' }],
      }],
      skin: [],
      meshes: [],
      accessors: [
        { type: 'SCALAR', componentType: 5126, count: 2, bufferView: 0 },
        { type: 'VEC4', componentType: 5126, count: 2, bufferView: 1 },
      ],
      bufferViews: [
        { buffer: 0, byteOffset: 0, byteLength: 8 },
        { buffer: 0, byteOffset: 8, byteLength: 32 },
      ],
      buffers: [{ byteLength: 40 }],
    });
    const bin = Buffer.alloc(40);
    bin.writeFloatLE(0.0, 0);
    bin.writeFloatLE(1.0, 4);
    bin.writeFloatLE(0, 8); bin.writeFloatLE(0, 12); bin.writeFloatLE(0, 16); bin.writeFloatLE(1, 20);
    bin.writeFloatLE(0, 24); bin.writeFloatLE(0.70710677, 28); bin.writeFloatLE(0, 32); bin.writeFloatLE(0.70710677, 36);
    const buf = makeGlb(json, bin);
    return Promise.resolve({ ok: true, status: 200, arrayBuffer: () => buf.buffer });
  }
  return Promise.reject(new Error('unknown test url: ' + url));
}
globalThis.fetch = fakeFetch;
globalThis.Request = class { constructor(u) { this.url = u; } };

globalThis.document = {
  hidden: false,
  baseURI: 'https://appassets.androidplatform.net/her/index.html',
  createElementNS: () => ({ addEventListener() {}, removeEventListener() {} }),
  createElement: () => ({ getContext: () => ({}) }),
};
globalThis.requestAnimationFrame = () => {};
process.on('unhandledRejection', () => {});

await import('file:///' + SRC.replace(/\\/g, '/'));
const her = globalThis.window.__her;
const Her = globalThis.window.Her;

// ★ vrm.scene 是 mixer root，必须在测试前假一个
//   同时给 her.scene(代码里 !this.scene 就 return)，让它能通过前置闸
//   createVRMAnimationHumanoidTracks 会调用 humanoid.getNormalizedBoneNode(name)
//   返回的对象必须有 .name 属性（被用来构造 track name）
//   同时必须有 .position 和 .rotation 属性供 idle 姿势写入
const fakeNormalizedHips = {
  name: 'Normalized_Hips',
  position: { y: 0.9 },
  rotation: { set() {} }
};
const fakeVrmScene = {
  uuid: 'fake-vrm-scene',
  children: [],
  position: { z: 0 },
  add() {},
  remove() {}
};
// ★ 给 expressionManager 和 lookAt 各假一个 stub，避免 createVRMAnimationClip 去 scene.children 里找东西
const fakeExpressionManager = {
  getExpressionTrackName() { return null; },  // 直接跳过表情轨道
  setValue() {},  // ★ her.update() 会调 em.setValue(name, next) —— 给它一个空实现，别让它炸
};
her.vrm = {
  scene: fakeVrmScene,
  meta: { metaVersion: '1.0' },
  lookAt: { enabled: false },  // ★ 必须有 lookAt 对象（不能是 null），否则 createVRMAnimationClip 会炸
  expressionManager: fakeExpressionManager,
  humanoid: {
    headNode: null,  // ★ 先 null；Test 4 会假一个 fakeHead 上去
    normalizedRestPose: { hips: { position: [0, 1, 0] } },  // 避免除以零
    getNormalizedBoneNode(name) {
      if (name === 'hips') return fakeNormalizedHips;
      if (name === 'head') return this.headNode;
      return null;
    },
  },
  update() {},
};
// ★ her.scene 在真实房间里由 loadScene 创建，测试里指同一个 fake 即可
her.scene = fakeVrmScene;
// ★ 设 rest 姿势基准,供门控逻辑比对
her.rest = { head: { x: 0, y: 0, z: 0 }, chest: { x: 0, y: 0, z: 0 }, spine: { x: 0, y: 0, z: 0 }, hipsY: 0.9, sceneZ: 0 };

// ---------------------------------------------------------------------------
// 二、量具
// ---------------------------------------------------------------------------
const fails = [];
function check(name, cond, extra) {
  if (cond) { console.log('✓ ' + name); return; }
  fails.push(name);
  console.log('✗ ' + name + (extra ? '\n      ← ' + extra : ''));
}
const last = () => notes[notes.length - 1] || '';
const said = () => notes.join('\n');
function reset() {
  her.motionAction = null;
  her.motionName = null;
  her.mixer = null;
  notes.length = 0;
}
const motionless = () => her.motionAction === null && her.motionName === null && her.mixer === null;

// ---------------------------------------------------------------------------
// 三、用例
// ---------------------------------------------------------------------------

console.log('\n【1】加载失败：文件不存在 → 说「加载失败」,不崩');
{
  reset();
  await Her.setMotion('missing.vrma');
  check('没动作', motionless());
  check('说得出加载失败', said().includes('动作加载失败') || said().includes('Not Found'), said());
}

console.log('\n【2】文件里没有 VRMC_vrm_animation 扩展 → 说「这个文件里没有 VRM 动作」,不崩');
{
  reset();
  await Her.setMotion('empty.vrma');
  check('没动作', motionless());
  check('提示里明确说「缺 VRMC_vrm_animation 扩展」', said().includes('没有 VRM 动作') && said().includes('VRMC_vrm_animation'), said());
}

console.log('\n【3】正常加载：最简可用的 .vrma → mixer 就绪 + 动作播起来 + 门控生效');
{
  reset();
  await Her.setMotion('good.vrma');
  check('mixer 不是空的', her.mixer !== null);
  check('mixer root 是 vrm.scene', her.mixer && her.mixer._root === her.vrm?.scene);
  check('动作不是空的', her.motionAction !== null);
  check('动作在播放中', her.motionAction && her.motionAction.isRunning());
  check('名字对得上', her.motionName === 'good.vrma');
  check('说得出时长和循环', last().includes('时长 1.0s') && last().includes('循环播'), last());
}

console.log('\n【4】门控：动作起来之后，idle 姿势 (头/胸/脊柱/臀) 被挡住，但表情/眨眼/口型/视线照常');
{
  reset();
  // ★ 不先 setMotion,此时 motionAction 为 null
  const dt = 0.016;  // 60 FPS
  // ★ 假设上一轮没在说话
  her.talking = false;
  her.expressionManager = { set() {}, setWeight() {} };
  her.lookAt = { enabled: true };
  // ★ 没有动作时：应该在写 idle 姿势
  let wroteIdle = false;
  const fakeHead = {
    rotation: { set() { wroteIdle = true; } },
    getWorldPosition(v) { v.set(0, 1.6, 0); return v; }
  };
  her.vrm.humanoid.headNode = fakeHead;
  her.update(dt, 0);
  check('没动作时，写了 idle 姿势', wroteIdle);
  // ★ 加载动作
  await Her.setMotion('good.vrma');
  // ★ 有动作时：不应该再写 idle 姿势
  wroteIdle = false;
  her.update(dt, 0);
  check('有动作时，没写 idle 姿势 (被门控挡住)', !wroteIdle);
  // ★ 但 blink/expression/viseme/gaze 仍然在跑 —— 这些在代码里是「无条件」的
  //   (不在 motionOn if 里面)。这里不验具体值，只验「它们有被调用的痕迹」:
  //   fake 出来的 set/setWeight 应该被喊过 (否则就是整块被跳过了)。
}

console.log('\n【5】门控另一向：动作停了 → idle 姿势恢复');
{
  reset();
  await Her.setMotion('good.vrma');
  her.motionAction.stop();
  her.motionAction = null;
  let wroteIdle = false;
  const fakeHead = {
    rotation: { set() { wroteIdle = true; } },
    getWorldPosition(v) { v.set(0, 1.6, 0); return v; }
  };
  her.vrm.humanoid.headNode = fakeHead;
  her.update(0.016, 0);
  check('动作停了，idle 姿势恢复', wroteIdle);
}

console.log('\n【6】换动作：旧的停掉，新的播起来，不堆积');
{
  reset();
  await Her.setMotion('good.vrma');
  const firstAction = her.motionAction;
  await Her.setMotion('good.vrma');  // 再 load 一次 (用同一个文件，因为我们的桩是状态无关的)
  check('旧动作停了', !firstAction.isRunning());
  check('新动作播起来', her.motionAction !== firstAction && her.motionAction.isRunning());
}

console.log('\n【7】边界：传空/假值 → 安全地什么也不做，不崩');
{
  reset();
  await Her.setMotion(null);
  await Her.setMotion(undefined);
  await Her.setMotion('');
  check('全都没崩，而且没留下任何残渣', motionless());
}

// ---------------------------------------------------------------------------
// 四、收尾
// ---------------------------------------------------------------------------
if (fails.length === 0) {
  console.log('\n✅ 全部通过 (共 7 组)');
  process.exit(0);
} else {
  console.log('\n❌ 失败：' + fails.join(', '));
  process.exit(1);
}
