// ============================================================================
// 房间 —— 用 three.js 的图元现搭一个,不用外部模型文件。
//
// 2026-10-08 实景夜窗版(用户看过效果图拍的板:「我觉得可以,就这版了」):
//   - 深胡桃木竖条板墙 + 木地板(墙和地板都是 canvas 现画的程序纹理)
//   - 后墙整面落地夜景窗(月/星/云/三层城市,也是 canvas 画的)
//   - 灰蓝窗帘、深色金属窗框、踢脚线、深色天花板
//   - 家具沿用原来那些,只换成深胡桃木 / 黄铜 / 低饱和的配色
//   - 灯改成「窗外的冷月光 + 屋里落地灯的暖光」
//
// ★★ 两条不许动的东西:
//   ① 返回 { root, lights } —— 几何和灯**分开收**。拍悬浮窗透明人形时
//      要藏掉几何、却必须留着灯,合起来就废了(见 her.js 里 captureFigure)。
//   ② 下面 ROOM 那几个尺寸 —— 房间物件是按这套尺寸摆的,改了它们会浮起来。
//
// ★ 二次元那版(粉紫主调 + 描边)开发时另存过一份,**没随仓库发布**。
//   要那版的样子,改下面 C 那张配色表 + 尾部的灯就行 —— 房间的几何两版是同一套。
// ============================================================================

import * as THREE from 'three';

/** 实景配色:深胡桃木 / 黄铜 / 低饱和 */
const C = {
  // 墙:深胡桃木
  wall: 0x3a2a1c,
  // 墙裙(这一版没用到,留着)
  wainscot: 0x2c1f14,
  // 木线 / 踢脚
  trim: 0x241a11,
  // 地板
  floor: 0x2a1a0f,
  // 家具木
  wood: 0x4a3324,
  woodTop: 0x5e4530,
  // 金属:黄铜
  metal: 0x8a7550,
  // 窗框:深色金属
  frame: 0x14161b,
  // 窗帘:灰蓝
  curtain: 0x3d4350,
  // 树叶:低饱和的深绿
  leaf: 0x35533a,
  // 花盆:哑光深色陶
  pot: 0x2b2622,
  // 灯罩:暖黄
  lamp: 0xffcf96,
  // 书:低饱和
  book: [0x6b4632, 0x3d4a5c, 0x5e4a2e, 0x46384f, 0x35443c],
};

/** 房间的尺寸 —— ★ 不许动,房间物件按它摆 */
const ROOM = {
  wallZ: -1.5, sideX: 2.3, depth: 3.4, height: 3.3,
  railY: 1.02, deskTopY: 0.73,
};

/**
 * 四张程序纹理的分辨率。
 * ★ 这是手机上的账:四张一共约 2.5M 像素 ≈ 10MB 显存(+ mipmap)。
 *   当初的效果图是在电脑上按 2048² 看的,搬进手机必须降下来。
 */
const TEX = {
  floor: 1024,          // 铺 9m × 10m
  wall: 1024, wallH: 640,   // 铺 7.5m × 3.3m
  night: 1024, nightH: 640, // 铺 4.8m × 3.3m
  curt: 128, curtH: 256,    // 铺 0.34m × 3.24m
};

// ---------------------------------------------------------------------------
// 旧二次元那版留下的三件 —— 这一版用不上,但既然写过了就不删。
// 整份实现在同目录的 room.anime.bak.js 里,要那一版直接换文件回去。
// ---------------------------------------------------------------------------

/** 二次元风格的轮廓线(★ 这一版不用:描边是二次元的签名,实景里很脏) */
function addOutline(mesh, color = 0x1a1020, opacity = 0.5) {
  const edges = new THREE.EdgesGeometry(mesh.geometry);
  const line = new THREE.LineSegments(
    edges,
    new THREE.LineBasicMaterial({ color: color, transparent: true, opacity: opacity })
  );
  mesh.add(line);
  return line;
}

/** 二次元风格的材质(★ 这一版统一走 buildRoom 里的 getMaterial) */
function std(color, rough = 0.88, metal = 0.0, emissiveScale = 0.18) {
  return new THREE.MeshStandardMaterial({
    color, roughness: rough, metalness: metal,
    emissive: new THREE.Color(color).multiplyScalar(emissiveScale),
    side: THREE.DoubleSide,
  });
}

/** 二次元木纹材质(★ 同上,不用) */
function woodMat() {
  return new THREE.MeshStandardMaterial({
    color: C.wood, roughness: 0.78, metalness: 0.0,
    emissive: new THREE.Color(C.wood).multiplyScalar(0.20),
    side: THREE.DoubleSide,
  });
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

/** 造一块画布 */
function cv(w, h) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
}

/** 区间随机数 */
function rnd(a, b) {
  return a + Math.random() * (b - a);
}

/** `rgb(...)` 字符串 */
function rgb(r, g, b) {
  return 'rgb(' + Math.round(r) + ',' + Math.round(g) + ',' + Math.round(b) + ')';
}

/** `rgba(...)` 字符串 */
function rgba(r, g, b, a) {
  return 'rgba(' + r + ',' + g + ',' + b + ',' + a.toFixed(3) + ')';
}

/** canvas → three 贴图。`rx`/`ry` 是重复次数。 */
function mkTex(canvas, rx = 1, ry = 1) {
  const t = new THREE.CanvasTexture(canvas);
  t.colorSpace = THREE.SRGBColorSpace;
  t.wrapS = THREE.RepeatWrapping;
  t.wrapT = THREE.RepeatWrapping;
  t.repeat.set(rx, ry);
  t.anisotropy = 4;
  return t;
}

// ---------------------------------------------------------------------------
// 四张程序纹理
// ---------------------------------------------------------------------------

/**
 * 木地板:一条条板,每条带细木纹;板缝和板端竖缝压深。
 * 横竖方向:画布的行 → 世界 z(一块块往远处排),列 → 世界 x(板长方向)。
 *
 * ★ 2026-10-08 换成米色 —— 他看过整间效果图之后说「地板能不能换成米色地板」。
 *   原来是偏橙红的深木色(139/88/50 那一档),和深胡桃木的墙凑在一起太满、太暖。
 *   现在这档是浅暖灰米,板缝也从近黑改成灰米色,不然浅地上会是一条条黑线。
 */
function floorCanvas(S) {
  const c = cv(S, S);
  const g = c.getContext('2d');
  g.fillStyle = '#a1947a';
  g.fillRect(0, 0, S, S);

  const PL = 44;                 // 44 条板铺 9m ⇒ 每条约 20cm
  const ph = S / PL;

  for (let i = 0; i < PL; i++) {
    const t = rnd(0, 1);
    g.fillStyle = rgb(152 + t * 30, 142 + t * 26, 120 + t * 24);
    g.fillRect(0, i * ph, S, ph);

    // 细木纹:横贯整条板,带一点点起伏
    for (let k = 0; k < 30; k++) {
      const y = i * ph + rnd(0, ph);
      g.strokeStyle = rgba(104, 94, 74, rnd(0.05, 0.18));
      g.lineWidth = rnd(0.6, 1.7);
      g.beginPath();
      g.moveTo(0, y);
      for (let s = 1; s <= 6; s++) g.lineTo((S * s) / 6, y + rnd(-1.8, 1.8));
      g.stroke();
    }

    // 板缝(横)
    g.fillStyle = 'rgba(132,122,100,0.85)';
    g.fillRect(0, i * ph, S, 1.7);

    // 板端竖缝(每行错开,不然像瓷砖)
    g.fillStyle = 'rgba(132,122,100,0.70)';
    g.fillRect(((i * 0.37) % 1) * S, i * ph, 1.7, ph);
  }
  return c;
}

/**
 * 木墙:一片片竖条板。参数按米给,自己换算成条数。
 */
function wallCanvas(W, H, panelM, wallM) {
  const c = cv(W, H);
  const g = c.getContext('2d');
  g.fillStyle = '#3a2614';
  g.fillRect(0, 0, W, H);

  const n = Math.max(2, Math.round(wallM / panelM));
  const pw = W / n;

  for (let i = 0; i < n; i++) {
    const t = rnd(0, 1);
    g.fillStyle = rgb(90 + t * 34, 62 + t * 24, 38 + t * 16);
    g.fillRect(i * pw, 0, pw, H);

    // 竖木纹
    for (let k = 0; k < 40; k++) {
      const x = i * pw + rnd(0, pw);
      g.strokeStyle = rgba(26, 14, 6, rnd(0.05, 0.22));
      g.lineWidth = rnd(0.5, 1.4);
      g.beginPath();
      g.moveTo(x, 0);
      g.lineTo(x + rnd(-2.5, 2.5), H * 0.5);
      g.lineTo(x + rnd(-2.5, 2.5), H);
      g.stroke();
    }

    // 板缝(竖)
    g.fillStyle = 'rgba(20,10,4,0.9)';
    g.fillRect(i * pw, 0, 1.7, H);
  }
  return c;
}

/**
 * 落地夜景:月 + 星 + 云 + 三层城市 + 地平线暖霾 + 前景暗带。
 *
 * ★ 两个位置是照着「相机在哪儿」算出来的,不是随手放的:
 *   相机的可见竖幅是 x ∈ ±1.16m、y ∈ [-0.80, 3.32]m,
 *   而这块玻璃是 4.8m 宽 × 3.3m 高 ⇒
 *     月亮放在画布 (0.348W, 0.155H) → 世界 (-0.73m, 2.79m) —— 在画框里,偏左。
 *     地平线放在 0.72H → 世界 y≈0.92m —— 比相机(1.26m)略低,像站在高楼上往下看。
 *   画布 y=0 是画面上方(three 的贴图默认上下翻转)。
 */
function nightCanvas(W, H) {
  const c = cv(W, H);
  const g = c.getContext('2d');
  const HZ = H * 0.72;

  // ---- 天空
  const sky = g.createLinearGradient(0, 0, 0, HZ);
  sky.addColorStop(0.0, '#050a1e');
  sky.addColorStop(0.42, '#0f2049');
  sky.addColorStop(0.76, '#26406f');
  sky.addColorStop(1.0, '#5b6280');
  g.fillStyle = sky;
  g.fillRect(0, 0, W, HZ + 1);

  // ---- 星(越高越亮,越靠地平线越被霾吃掉)
  for (let i = 0; i < 460; i++) {
    const x = rnd(0, W);
    const y = rnd(0, HZ * 0.82);
    const a = rnd(0.18, 0.9) * (1 - y / (HZ * 0.82)) + 0.1;
    g.fillStyle = rgba(226, 238, 255, a);
    g.beginPath();
    g.arc(x, y, rnd(0.5, 1.5), 0, Math.PI * 2);
    g.fill();
  }

  // ---- 云带(三层同心椭圆叠出柔边,不用渐变对象)
  for (let i = 0; i < 7; i++) {
    const x = rnd(-W * 0.1, W * 1.1);
    const y = rnd(H * 0.06, HZ * 0.7);
    const rw = rnd(W * 0.12, W * 0.3);
    const rh = rnd(H * 0.012, H * 0.035);
    for (let ring = 3; ring >= 1; ring--) {
      g.fillStyle = rgba(150, 175, 215, 0.05 * ring);
      g.beginPath();
      g.ellipse(x, y, (rw * ring) / 3, (rh * ring) / 3, 0, 0, Math.PI * 2);
      g.fill();
    }
  }

  // ---- 月亮
  const MX = W * 0.348, MY = H * 0.155, MR = H * 0.072;
  const halo = g.createRadialGradient(MX, MY, MR * 0.6, MX, MY, MR * 5.2);
  halo.addColorStop(0, 'rgba(198,220,255,0.55)');
  halo.addColorStop(0.35, 'rgba(170,198,245,0.18)');
  halo.addColorStop(1, 'rgba(150,180,235,0)');
  g.fillStyle = halo;
  g.beginPath();
  g.arc(MX, MY, MR * 5.2, 0, Math.PI * 2);
  g.fill();

  g.fillStyle = '#eaf1ff';
  g.beginPath();
  g.arc(MX, MY, MR, 0, Math.PI * 2);
  g.fill();

  // 月面斑
  for (let i = 0; i < 22; i++) {
    const a = rnd(0, Math.PI * 2);
    const d = Math.sqrt(Math.random()) * MR * 0.82;
    g.fillStyle = rgba(196, 210, 236, rnd(0.2, 0.5));
    g.beginPath();
    g.arc(MX + Math.cos(a) * d, MY + Math.sin(a) * d, rnd(MR * 0.05, MR * 0.16), 0, Math.PI * 2);
    g.fill();
  }

  // ---- 三层城市:远的那层淡、近的那层黑
  const layers = [
    { col: '#22345c', n: 50, h0: 0.03, h1: 0.12, lit: 0.07, warm: 0.82, s: 0.5 },
    { col: '#182644', n: 38, h0: 0.08, h1: 0.27, lit: 0.13, warm: 0.78, s: 0.8 },
    { col: '#0c1424', n: 26, h0: 0.05, h1: 0.16, lit: 0.10, warm: 0.70, s: 1.15 },
  ];
  for (const L of layers) {
    const step = W / L.n;
    for (let i = 0; i < L.n; i++) {
      const bw = step * rnd(0.6, 0.98);
      const bxx = i * step + rnd(0, Math.max(0, step - bw));
      const bh = H * rnd(L.h0, L.h1);
      const by = HZ - bh;
      g.fillStyle = L.col;
      g.fillRect(bxx, by, bw, bh);

      // 亮窗
      const cols = Math.max(1, Math.floor(bw / (7 * L.s)));
      const rows = Math.max(1, Math.floor(bh / (9 * L.s)));
      for (let cc = 0; cc < cols; cc++) {
        for (let rr = 0; rr < rows; rr++) {
          if (Math.random() > L.lit) continue;
          g.fillStyle = Math.random() < L.warm
            ? rgba(255, 206, 140, rnd(0.35, 0.95))
            : rgba(180, 220, 255, rnd(0.3, 0.8));
          g.fillRect(bxx + 2.5 * L.s + cc * 7 * L.s, by + 3 * L.s + rr * 9 * L.s,
            3.2 * L.s, 4 * L.s);
        }
      }

      // 高塔顶上的红点
      if (bh > H * 0.22 && Math.random() < 0.5) {
        g.fillStyle = 'rgba(255,80,70,0.85)';
        g.beginPath();
        g.arc(bxx + bw / 2, by - 2, 1.6, 0, Math.PI * 2);
        g.fill();
      }
    }
  }

  // ---- 地平线暖霾
  const haze = g.createLinearGradient(0, HZ - H * 0.08, 0, HZ + H * 0.02);
  haze.addColorStop(0, 'rgba(255,190,120,0)');
  haze.addColorStop(0.72, 'rgba(255,186,116,0.20)');
  haze.addColorStop(1, 'rgba(255,170,100,0.05)');
  g.fillStyle = haze;
  g.fillRect(0, HZ - H * 0.08, W, H * 0.1);

  // ---- 地平线以下的暗带(楼下的街面,只剩零星灯火)
  const ground = g.createLinearGradient(0, HZ, 0, H);
  ground.addColorStop(0, 'rgba(7,11,19,0.92)');
  ground.addColorStop(0.35, '#070b13');
  ground.addColorStop(1, '#04060b');
  g.fillStyle = ground;
  g.fillRect(0, HZ, W, H - HZ);
  for (let i = 0; i < 70; i++) {
    g.fillStyle = rgba(255, 214, 160, rnd(0.06, 0.3));
    g.fillRect(rnd(0, W), rnd(HZ + 2, H), rnd(1, 2.6), rnd(1, 2));
  }

  return c;
}

/** 灰蓝窗帘布:竖褶皱,靠明暗交替立起来 */
function curtainCanvas(W, H) {
  const c = cv(W, H);
  const g = c.getContext('2d');
  g.fillStyle = '#3d4350';
  g.fillRect(0, 0, W, H);

  const n = 8;
  const fw = W / n;
  for (let i = 0; i < n; i++) {
    const x = i * fw;
    const lg = g.createLinearGradient(x, 0, x + fw, 0);
    lg.addColorStop(0.0, 'rgba(18,20,26,0.55)');
    lg.addColorStop(0.35, 'rgba(126,136,154,0.42)');
    lg.addColorStop(0.62, 'rgba(96,104,120,0.16)');
    lg.addColorStop(1.0, 'rgba(18,20,26,0.55)');
    g.fillStyle = lg;
    g.fillRect(x, 0, fw, H);
  }
  return c;
}

// ---------------------------------------------------------------------------
// 几个造几何的小工具
// ---------------------------------------------------------------------------

/** 造一个盒子 */
function box(w, h, d, mat, x, y, z) {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat);
  m.position.set(x, y, z);
  return m;
}

/** 造一片朝指定方向的平面。`ry` 是绕 Y 转的角度。 */
function plane(w, h, mat, x, y, z, ry = 0) {
  const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat);
  m.position.set(x, y, z);
  m.rotation.y = ry;
  return m;
}

// ---------------------------------------------------------------------------

/**
 * 搭出整个房间。
 * ★ 返回 { root, lights } —— 几何和灯分开收,见文件头。
 */
export function buildRoom() {
  const root = new THREE.Group();
  root.name = 'room';
  const cache = new Map();

  /** 材质缓存 —— 同一组参数只造一份,省得几百个盒子各拿一份材质 */
  const getMaterial = (color, rough = 0.92, metal = 0.0, emissiveScale = 0.10) => {
    const k = color + '|' + rough + '|' + metal;
    if (!cache.has(k)) {
      cache.set(k, new THREE.MeshStandardMaterial({
        color, roughness: rough, metalness: metal,
        emissive: new THREE.Color(color).multiplyScalar(emissiveScale),
        side: THREE.DoubleSide,
      }));
    }
    return cache.get(k);
  };

  /**
   * 贴图材质。
   * ★ color 必须是白 —— 它和 map 相乘,一 tint 整张图就暗了。
   * ★ emissive 只给一点点暖色,免得背光的地方死黑一片。
   */
  const texMat = (map, rough, metal, emisHex, emisScale) =>
    new THREE.MeshStandardMaterial({
      map, roughness: rough, metalness: metal,
      emissive: new THREE.Color(emisHex).multiplyScalar(emisScale),
      side: THREE.DoubleSide,
    });

  // ---------------------------------------------------------------- 贴图
  const floorTex = mkTex(floorCanvas(TEX.floor));
  const wallTex = mkTex(wallCanvas(TEX.wall, TEX.wallH, 0.62, 7.5));
  const nightTex = mkTex(nightCanvas(TEX.night, TEX.nightH));
  const curtTex = mkTex(curtainCanvas(TEX.curt, TEX.curtH));

  // ---------------------------------------------------------------- 地板
  // ★ 铺到相机后面去了:相机在 z≈6.2,地板从 -3 一直铺到 +7,不然会看见地板断掉。
  const floor = new THREE.Mesh(
    new THREE.PlaneGeometry(9, 10),
    texMat(floorTex, 0.55, 0.0, 0x2a1a12, 0.05));
  floor.rotation.set(-Math.PI / 2, 0, 0);
  floor.position.set(0, 0, ROOM.wallZ + 3.5);
  root.add(floor);

  // ---------------------------------------------------------------- 左右两面墙:深胡桃木竖条板
  const wallMat = texMat(wallTex, 0.62, 0.0, 0x2a1a12, 0.05);
  for (const sx of [-1, 1]) {
    const w = new THREE.Mesh(new THREE.PlaneGeometry(7.5, ROOM.height), wallMat);
    w.position.set(sx * ROOM.sideX, ROOM.height / 2, ROOM.wallZ + 3.75);
    w.rotation.y = (sx * -Math.PI) / 2;
    root.add(w);
  }

  // ---------------------------------------------------------------- 天花板
  const ceil = new THREE.Mesh(new THREE.PlaneGeometry(9, 10), getMaterial(0x17120f, 0.95));
  ceil.rotation.set(Math.PI / 2, 0, 0);
  ceil.position.set(0, ROOM.height, ROOM.wallZ + 3.5);
  root.add(ceil);

  // ---------------------------------------------------------------- 踢脚线(两面侧墙)
  for (const sx of [-1, 1]) {
    root.add(box(0.045, 0.11, 7.5, getMaterial(C.trim, 0.55),
      sx * (ROOM.sideX - 0.028), 0.055, ROOM.wallZ + 3.75));
  }

  // ---------------------------------------------------------------- 后墙:整面落地夜景窗
  const GW = ROOM.sideX * 2 + 0.2;          // 4.8m
  const glass = new THREE.Mesh(
    new THREE.PlaneGeometry(GW, ROOM.height),
    // ★ fog:false —— 后墙离相机 7.7m,场景雾会把夜景冲淡三成,窗是屋里最亮的东西,不能吃雾。
    // ★ toneMapped:false —— 渲染器开着 ACES 色调映射会把夜色压灰,窗光要按画出来的样子出去。
    new THREE.MeshBasicMaterial({ map: nightTex, fog: false, toneMapped: false }));
  glass.position.set(0, ROOM.height / 2, ROOM.wallZ + 0.01);
  glass.userData.isWindow = true;
  root.add(glass);

  // 窗框:深色金属。竖梃落在 x=±1.05 —— 正好贴着画框边缘,给窗户一点结构。
  const frameM = getMaterial(C.frame, 0.45, 0.55);
  const FZ = ROOM.wallZ + 0.04;
  root.add(box(GW + 0.10, 0.09, 0.09, frameM, 0, 0.045, FZ));
  root.add(box(GW + 0.10, 0.09, 0.09, frameM, 0, ROOM.height - 0.045, FZ));
  for (const sx of [-1, 1]) {
    root.add(box(0.09, ROOM.height, 0.09, frameM, sx * (GW / 2 - 0.045), ROOM.height / 2, FZ));
  }
  for (const mx of [-1.05, 1.05]) {
    root.add(box(0.055, ROOM.height, 0.07, frameM, mx, ROOM.height / 2, FZ + 0.01));
  }
  root.add(box(GW, 0.05, 0.07, frameM, 0, ROOM.height - 0.62, FZ + 0.01));

  // ---------------------------------------------------------------- 窗帘(两侧)
  const curtMat = texMat(curtTex, 0.92, 0.0, 0x1a1e26, 0.04);
  for (const sx of [-1, 1]) {
    const cu = new THREE.Mesh(
      new THREE.PlaneGeometry(0.34, ROOM.height - 0.06), curtMat);
    cu.position.set(sx * (ROOM.sideX - 0.19), (ROOM.height - 0.06) / 2 + 0.02, ROOM.wallZ + 0.17);
    root.add(cu);
  }

  // ---------------------------------------------------------------- 架子(右边)
  const SX = 1.32;
  const woodM = getMaterial(C.wood, 0.70);
  root.add(box(1.05, 0.035, 0.26, woodM, SX, 1.08, ROOM.wallZ + 0.15));
  root.add(box(1.05, 0.035, 0.26, woodM, SX, 1.62, ROOM.wallZ + 0.15));
  root.add(box(0.035, 0.60, 0.24, woodM, SX - 0.51, 1.35, ROOM.wallZ + 0.14));
  root.add(box(0.035, 0.60, 0.24, woodM, SX + 0.51, 1.35, ROOM.wallZ + 0.14));

  // 架上的书
  let bx = SX - 0.44;
  for (let i = 0; i < 5; i++) {
    const h = 0.17 + (i % 3) * 0.035;
    root.add(box(0.055, h, 0.17, getMaterial(C.book[i % C.book.length], 0.72),
      bx, 1.08 + 0.0175 + h / 2, ROOM.wallZ + 0.15));
    bx += 0.075 + (i % 2) * 0.012;
  }

  // ---------------------------------------------------------------- 桌(右)+ 边柜(左)
  // ★ 2026-10-08 他看过**真机取景**那张之后说「得去掉那两个柜子,太不好看了」——
  //   手机竖屏那个取景里,画面最下面正好横着这两件(左柜 + 右桌),一块块深木头把底边堵满。
  //   整段原样留着(没删),想摆回来把下面这些行的注释去掉即可。
  //   ROOM.deskTopY 那个数**留着没动** —— 它只被这一段用过,以后要往桌面上摆东西还能用。
  // const D = ROOM.deskTopY;
  // const woodTopM = getMaterial(C.woodTop, 0.52);

  // // 右桌
  // root.add(box(1.05, 0.045, 0.62, woodTopM, 0.67, D, 0.12));
  // root.add(box(1.08, 0.03, 0.05, getMaterial(C.trim, 0.48), 0.67, D - 0.035, 0.42));
  // for (const [lx, lz] of [[0.19, -0.16], [1.15, -0.16], [0.19, 0.40], [1.15, 0.40]]) {
  //   root.add(box(0.05, D - 0.02, 0.05, woodM, lx, (D - 0.02) / 2, lz));
  // }

  // // 左柜
  // root.add(box(1.00, 0.045, 0.52, woodTopM, -0.67, D, 0.05));
  // root.add(box(0.96, D - 0.06, 0.48, woodM, -0.67, (D - 0.06) / 2, 0.05));
  // root.add(box(0.88, 0.02, 0.03, getMaterial(C.trim, 0.48), -0.67, 0.36, 0.30));
  // root.add(box(0.16, 0.022, 0.022, getMaterial(C.metal, 0.32, 0.58), -0.67, 0.42, 0.31));

  // ---------------------------------------------------------------- 盆栽(左)
  // ★ 2026-10-08 他看过整间效果图之后说「那个盆栽去掉」—— 摘掉了。
  //   整段原样留着(没删),想摆回来把下面这几行的注释去掉即可。
  // const plant = new THREE.Group();
  // plant.position.set(-1.38, 0, -0.72);
  // const pot = new THREE.Mesh(
  //   new THREE.CylinderGeometry(0.145, 0.11, 0.26, 16), getMaterial(C.pot, 0.82));
  // pot.position.y = 0.13;
  // plant.add(pot);
  // const leafMat = getMaterial(C.leaf, 0.78);
  // for (let i = 0; i < 7; i++) {
  //   const a = (i / 7) * Math.PI * 2;
  //   const l = new THREE.Mesh(new THREE.ConeGeometry(0.055, 0.5 + (i % 3) * 0.13, 6), leafMat);
  //   l.position.set(Math.cos(a) * 0.09, 0.42 + (i % 3) * 0.06, Math.sin(a) * 0.09);
  //   l.rotation.set(Math.sin(a) * 0.42, 0, -Math.cos(a) * 0.42);
  //   plant.add(l);
  // }
  // root.add(plant);

  // ---------------------------------------------------------------- 落地灯(右)
  const lampG = new THREE.Group();
  lampG.position.set(1.38, 0, -0.66);
  const pole = new THREE.Mesh(
    new THREE.CylinderGeometry(0.018, 0.018, 1.34, 10), getMaterial(C.metal, 0.38, 0.52));
  pole.position.y = 0.67;
  lampG.add(pole);
  const foot = new THREE.Mesh(
    new THREE.CylinderGeometry(0.13, 0.15, 0.035, 16), getMaterial(C.metal, 0.42, 0.48));
  foot.position.y = 0.018;
  lampG.add(foot);
  const shade = new THREE.Mesh(
    new THREE.CylinderGeometry(0.10, 0.17, 0.19, 16, 1, true),
    new THREE.MeshStandardMaterial({
      color: C.lamp, emissive: C.lamp, emissiveIntensity: 0.92,
      roughness: 0.58, side: THREE.DoubleSide,
    }));
  shade.position.y = 1.38;
  lampG.add(shade);
  root.add(lampG);

  // ---------------------------------------------------------------- 灯(两盏,★ 和几何分开收)
  const lights = [];

  // 窗外的冷月光:从窗外斜进来,给她勾一道边
  const windowLight = new THREE.DirectionalLight(0x9fc2ff, 0.90);
  windowLight.position.set(-2.2, 2.9, -3.4);
  lights.push(windowLight);

  // 屋里落地灯的暖光
  const lampLight = new THREE.PointLight(0xffb066, 1.15, 5.0, 2.0);
  lampLight.position.set(1.38, 1.36, -0.6);
  lights.push(lampLight);

  return { root, lights };
}
