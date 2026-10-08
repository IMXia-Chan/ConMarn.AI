# THIRD_PARTY —— 第三方组件

ConMarn 自己以 [Apache-2.0](LICENSE) 授权。
它**用**了很多别人的东西,分三类,处理方式各不相同:

| 类别 | 在哪 | 仓库里有吗 |
| --- | --- | --- |
| **A. 原生库**(`.so`) | `app/src/main/jniLibs/arm64-v8a/` | ❌ **没有**(约 41.1 MB) |
| **B. 模型**(脑 / 耳 / 嗓 / 身体 / 房间) | 手机 `.../files/` 目录下 | ❌ **没有** |
| **C. 第三方源码与依赖** | `app/…/com/k2fsa/`、Gradle、npm、Python | ✅ **有**,逐个列在下面 |

> ★ 这份文件里的每一个许可证都是**查过的**(用 GitHub 的许可证接口读上游仓库原文),
> 不是照抄谁的说法。**llama.cpp 是 MIT,不是 Apache-2.0** —— 这一点很容易被想当然写错。

---

## A. 没进仓库的原生库(约 41.1 MB)

`app/src/main/jniLibs/arm64-v8a/` 下 11 个 `.so`,**一个都不在仓库里** ——
它们全是别人编出来的东西,而且加起来比仓库其余部分大一个数量级。
不放进来只有一个理由:**那是构建产物,不是源码。**(体积是附带的好处。)

| `.so` | 字节(作者的构建) | 来自 | 许可证 |
| --- | ---: | --- | --- |
| `libllama.so` | 4,021,320 | [llama.cpp](https://github.com/ggml-org/llama.cpp) | **MIT** |
| `libllama-common.so` | 4,398,840 | 同上 | **MIT** |
| `libllama-server-impl.so` | 2,782,352 | 同上(`llama-server` 的实现都在这里) | **MIT** |
| `libmtmd.so` | 1,359,656 | 同上(多模态) | **MIT** |
| `libggml.so` | 125,832 | 同上 | **MIT** |
| `libggml-base.so` | 1,189,072 | 同上 | **MIT** |
| `libggml-cpu.so` | 974,552 | 同上 | **MIT** |
| `libllamaserver.so` | 4,872 | 同上 —— ★ 它**只有 4.8 KB 不是残缺**:`llama-server` 本来就是个瘦启动器,实现全在 `libllama-server-impl.so` 里 | **MIT** |
| `libomp.so` | 1,229,304 | **Android NDK 自带的 LLVM OpenMP 运行时** | Apache-2.0 WITH LLVM-exception |
| `libonnxruntime.so` | 22,249,560 | [Microsoft onnxruntime](https://github.com/microsoft/onnxruntime) | **MIT** |
| `libsherpa-onnx-jni.so` | 4,771,760 | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | **Apache-2.0** |

**合计 43,107,120 字节(约 41.1 MiB)。**

### 怎么自己弄到

- **sherpa-onnx 那两个** —— 仓库里就有脚本:
  ```bash
  tools/fetch-sherpa.sh
  ```
  它下载官方 Android 包(锁死 `v1.13.8`)、校验、抽出
  `libsherpa-onnx-jni.so` + `libonnxruntime.so`,**顺手把那 22 个 Kotlin 文件也 vendor 进来**(见下面 C 节)。

- **llama.cpp 那八个** —— 用 NDK 自己编一份 Android 版。注意两条:
  1. **必须开 `dotprod` / `i8mm`**(官方默认那份在手机 CPU 上慢 2.5 倍);
  2. `.so` 必须**以 `lib*.so` 的名字躺在 `jniLibs/arm64-v8a/` 里** ——
     Android 10+ 只允许从 `nativeLibraryDir` 执行文件(app 私有目录被 SELinux 的 W^X 挡住,`chmod +x` 也没用)。
     `app/build.gradle.kts` 里的 `useLegacyPackaging = true` 就是为这件事:
     默认情况下 `.so` 是压缩着留在 APK 里的(直接 mmap 加载),**磁盘上根本没有文件,也就没有东西可以执行**。

- **`libomp.so`** —— NDK 里有,直接拷。

> ⚠️ **`libomp.so` 只准有一份。** 如果哪天 llama.cpp 那份构建也想自带 OpenMP,
> 两份同名 `.so` 会互相覆盖,而症状是**运行到一半炸**,不是构建失败。

---

## B. 没进仓库的模型

体积大,而且**各有各的许可证** —— 所以一个都不放。
清单(放哪、去哪拿、什么都不放会怎样)见 [README 第四节](README.md#四模型清单与去哪拿)。

这里只补一条 README 里不重复的话:
**模型和代码是分开授权的。** 你拉下这个仓库,拿到的是 Apache-2.0 的代码,
**不包含任何模型权重**;那些权重怎么用,取决于**它们的**许可证,和本仓库无关。

---

## C. 躺在仓库里的第三方东西

这一节才是 Apache-2.0 真正要管的部分 —— **它们和本项目的代码混在一起被分发。**

### C1 · sherpa-onnx 的 Kotlin API(22 个文件)

```
app/src/main/java/com/k2fsa/sherpa/onnx/*.kt
```

- 上游:[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) `kotlin-api/`,锁死 `v1.13.8`
- 许可证:**Apache-2.0**,`Copyright (c) 2023 Xiaomi Corporation`
- ★ **原样整目录 vendor,不手挑** —— 手挑漏一个引用,要**等运行期才炸**;
  全拿一共约 132 KB,编译代价可以忽略。改哪个文件都会让升级变得危险。

> ★★ **包名 `com.k2fsa.sherpa.onnx` 一个字都不能改**(也**别顺手"整理"目录名**)——
> JNI 按**类名**注册 native 方法。改了 = 每个 `external fun` 抛 `UnsatisfiedLinkError`,
> 而且是在**第一次调用时**才抛。

**许可原文出处**:上游仓库根的 `LICENSE`(Apache-2.0)。
按 Apache-2.0 第 4 条,归属声明写在本仓库的 [NOTICE](NOTICE) 里。

### C2 · Android / Gradle 依赖

`app/build.gradle.kts`:

| 依赖 | 许可证 | 说明 |
| --- | --- | --- |
| `androidx.core:core-ktx:1.13.1` | **Apache-2.0** | Android Open Source Project |
| `com.journeyapps:zxing-android-embedded:4.3.0` | **Apache-2.0** | 扫码配对(代替手抄 32 位种子) |
| `junit:junit:4.13.2` | ★ **EPL-1.0** | **只在跑单测时用,不进 APK** |

- `gradle/wrapper/gradle-wrapper.jar` —— Gradle 官方 wrapper,**Apache-2.0**。
- Android Gradle Plugin 9.3.0 / Kotlin —— **Apache-2.0**。★ 但它**不在仓库里**,
  由 Gradle 按 `build.gradle.kts` 里的版本号从 Google/Maven Central 拉。

> ★ **ZXing 为什么要显式写 `androidx.core`**:它内部依赖 `androidx.core`(`ContextCompat` 等)。
> 不显式引入的话,构建能过、**扫码 Activity 一启动就崩**。这条注释留在 `build.gradle.kts` 里了。

### C3 · 前端依赖(`her-src/`,会**打进** `her.bundle.js`)

| 包 | 许可证 | 用途 |
| --- | --- | --- |
| [`three`](https://github.com/mrdoob/three.js) | **MIT** | 渲染 |
| [`@pixiv/three-vrm`](https://github.com/pixiv/three-vrm) | **MIT** | VRM 加载 + 表情 / 口型 / 弹簧骨 |
| [`esbuild`](https://github.com/evanw/esbuild) | **MIT** | ★ **打包工具**,不进产物 |

★ **前两个是真的进了产物** —— `her.bundle.js` 里含它们的编译结果,
所以按 MIT 的要求,**它们的版权声明要跟着走**。

**已核实**(在 `app/src/main/assets/her/her.bundle.js` 里 grep 出来的,就这三条):

```
Copyright (c) 2019-2026 pixiv Inc.      ← three-vrm
Copyright 2010-2025 Three.js Authors   ← three.js
MIT License
```

`esbuild` 默认会把 `/*! … */` 这类 legal comment **收集起来放到产物末尾**,
所以 `--minify` **没有**把它们删掉。

> ⚠️ **如果你改了打包参数,记得回来 grep 一次这三行还在不在。**
> `--legal-comments=none` 会把它们抹掉 —— 而那个错误**不会报错、不会崩**,
> 只是产物里悄悄少了必须带的东西(见 `tools/build-her.sh`)。

### C4 · 电脑端的 Python 依赖(**全部可选**)

`pc-server/` **没有 `requirements.txt`,也不是硬依赖** ——
下面这些都在 `try: import … except ImportError` 里,**缺了只是那个功能降级,服务照常跑**。

| 包 | 许可证 | 少了会怎样 |
| --- | --- | --- |
| [`numpy`](https://github.com/numpy/numpy) | **BSD-3-Clause** | 屏幕镜像 / 虚拟摄像头不可用 |
| [`Pillow`](https://github.com/python-pillow/Pillow) | **MIT-CMU**(HPND) | 同上 |
| [`segno`](https://github.com/heuer/segno) | **BSD-3-Clause** | 二维码不显示 → 退化成手动抄种子 |
| [`send2trash`](https://github.com/arsenetar/send2trash) | **BSD-3-Clause** | 删除动作退化成直接删(不再进回收站) |
| [`sounddevice`](https://github.com/spatialaudio/python-sounddevice) | **MIT** | 虚拟麦克风不可用 |
| [`pyvirtualcam`](https://github.com/letmaik/pyvirtualcam) | ★★ **GPL-2.0** | 虚拟摄像头不可用 |
| `tkinter` | **PSF**(Python 自带) | 桌面悬浮窗画不出来 |

#### ★★ 关于 `pyvirtualcam`(GPL-2.0)—— 这条要说白

**它是这份清单里唯一的 copyleft 依赖**,所以单独讲清楚:

1. **它没有被分发** —— 本仓库既不含它的源码,也不含它的二进制。
   你 `pip install pyvirtualcam` 是从上游自己拿的。
2. **它是可选依赖,而且只被一个文件用到**(`pc-server/media.py`),
   那个 import 还包在 `try/except` 里。不装它,服务照常起,**只有"把手机画面变成虚拟摄像头"这一格不可用**。
3. ★ **它只在"手机 → 电脑虚拟摄像头"这条路上出现**,和她的脑 / 耳 / 手**全都没有关系**。
4. ★ **如果你要把这个项目再分发成闭源产品,请先自己想清楚这一格** ——
   这是 GPL-2.0,不是宽松许可。**最省事的做法是别装它,或者用别的方案替掉 `media.py` 里那一小块。**
   (说清楚是免得以后有人踩坑,不是为了吓人 —— 只要你不分发 pyvirtualcam 本身,
   今天这样用是没有问题的。)

---

## D. 项目自身的素材

这些是**项目自己的文件**,不是第三方代码,但**来源不等于"没有版权问题"**,
所以列在这里让作者自己确认:

| 文件 | 是什么 |
| --- | --- |
| `app/src/main/res/mipmap-*/ic_conmarn*.png` | App 图标 —— 粉底 + 一个白色的「C」(5 个 dpi 目录 × 前景 / 合成方 / 合成圆) |
| `app/src/main/res/drawable-nodpi/conmarn_avatar.png` | 悬浮球那个圆钮上的头像 —— 同一张「C」 |
| `app/src/main/res/raw/splash_chime.mp3` | 开屏音效(现已被停用的开屏用) |
| `pc-server/mouse.ico` · 仓库根的 `ruoxi-terminal.ico` | 图标文件,**代码里零引用** |
| `NOTICE` 里没有列到的其余一切 | —— |

> ★ **这一版里没有任何真人面孔。**
> 以前那三张脸(悬浮球头像 / 一张没人用的竖版 / 图标前景)是**同一张真人照片**,
> 开源时**整组换成了上面这套占位图标**(纯色 + 一个字母),那张竖版连文件都删了。
> 所以你现在**不需要**再为它们确认授权。
>
> ★★ **但这条防线是"我们换掉了",不是"结构上换不进来"** ——
> 谁往那几个文件名里塞一张人脸回去,这张表就重新变成一个问题。
> 公开发布之前如果动过这几个 PNG,**回头看一眼这一节还成不成立。**

---

## 没查到的

按「没查到就写没查到」的规矩:

- 上面 A 节那 11 个 `.so` 里,**`libllamaserver.so` 是上游产物还是本项目自己编的启动器** ——
  从字节数(4,872)和它在 llama.cpp 构建里的位置看像是上游那个 `llama-server`,
  但**没有逐字节核对过**。
- `libomp.so` 的**具体 NDK 版本** —— 没记。
- `numpy` / `Pillow` 在 GitHub 上被标成 `NOASSERTION`(不是 `BSD-3-Clause` / `MIT`),
  原因是它们的 `LICENSE` 文件里**还附了若干 bundled 组件**的许可证。
  ★ 上面写的 **BSD-3-Clause / MIT-CMU 是它们的**主体**许可,不是全文**;
  要抠字眼请读上游 `LICENSE` 原文。
