package com.example.touchpad

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 语音唤醒:喊一声「从漫」把她叫到 ConMarn 界面。
 *
 * ## 这一版换掉了原来那个,为什么
 *
 * 原来走的是 `android.speech.SpeechRecognizer`(**系统自带的**识别服务)。
 * 而这台 ColorOS 上**一个 `RecognitionService` 都没有** ——
 * `isRecognitionAvailable()` 恒为 false。也就是说:那个开关从写下那天起,
 * **一次都没有工作过**,只是显示得像能用。
 *
 * 现在换成**本地引擎**(sherpa-onnx 的关键词检测),模型文件就在手机上,
 * 不依赖系统里有没有那个服务。
 *
 * ## 唤醒词是「从漫」,不是「ConMarn」
 *
 * 它 2026-10-06 改的(原话:「唤醒词虽然是英文 Conmarn,但是还是改成从漫」)。
 * ★ **改这个字不是改一个字符串 —— 它连模型一起换了**:英文那个按子词切,
 * 中文这个按**拼音**切。所以现在是中文那套(见下面 [ENCODER] 那几个文件名)。
 *
 * ## 那三个参数不是抄来的默认值,是电脑上验过的那一组
 *
 * [KEYWORDS_SCORE] / [KEYWORDS_THRESHOLD] / [NUM_TRAILING_BLANKS] **必须**和电脑上
 * 那次实测用的**一模一样**。库自己的默认值是 `1.5 / 0.25 / 2`,而验过的是
 * `1.0 / 0.25 / 1` —— 差的那两个会让「喊十次中几次」变成另一个数,
 * 而**这件事在手机上完全看不出来**:不中就是不中,不报错、不记日志。
 * 电脑上那次是 9 段全中、10 句反例一句不中,换参数 = 那些数一个都不作数了。
 *
 * ## 两个「不说话」的坑,这一版要兜住
 *
 *  - **没喊中 = 彻底静默**。没有异常、没有日志,只表现成「她不理我」。
 *    所以命中的那一下**必须把模型听到的拼音写进日志** —— 那是事后唯一能分辨
 *    「没喊对」和「根本没听见」的东西。
 *  - **和她的耳朵抢麦**。耳朵([Ear])也在这个进程里,而麦只有一个。
 *    [roomInFront] 就是为这件事存在的,见那个字段的注释。
 *
 * ## 边界(诚实写在这儿,不藏着)
 *
 * ColorOS 把进程速冻时它自然不响 —— 那是机器的边界,不往下许诺。
 * **默认关**,在她房间里开(开着 = 麦克风常开,耗电换体验,他自己选)。
 */
object WakeWordManager {

    /** 和 [Ear] 用同一个前缀:耳朵这一摊的日志一条 grep 能看全。 */
    private const val TAG = "耳:"

    private const val PREF = "wake_word"
    private const val KEY_ON = "enabled"

    /** 对表的间隔。开着才转,是在问她「现在能不能开口」,不是在听。 */
    private const val POLL_MS = 2000L
    /** 命中之后隔这么久再挂回去 —— 给她跳出来、把房间铺满屏留出时间。 */
    private const val REARM_MS = 4000L
    /** 同一声别叫两次(命中那一瞬可能连着报几帧)。 */
    private const val HIT_COOLDOWN_MS = 3000L
    /** 解码连着出错这么多次就停手并回报,别无限空转烧电。 */
    private const val MAX_MISS = 5

    /**
     * 连着这么多秒一个非零样本都没收到,就**说一句**。
     *
     * ★★ 这是唯一能分清「静音」和「没听见」的仪器 —— 见 [loop] 里那一段。
     *   15 秒是「一段话的工夫」:真人不会连续 15 秒不发出任何声响(连呼吸都有底噪),
     *   所以到这一步基本就能断定是**系统没把麦克风给我们**。
     */
    private const val SILENT_GIVEUP_SEC = 15f

    // ------------------------------------------------------------------ 模型在哪

    /**
     * 模型目录:`asr/kws/`。
     *
     * ★★ **必须和 ASR 分开** —— 两边都有一个叫 `tokens.txt` 的文件,
     *   **同名不同物**(一个是整句识别的词表,一个是关键词的拼音表)。
     *   平铺在一起的话,后推上去的那份会把前一份覆盖掉,而症状是两个功能
     *   一起不工作、并且**都不报错**。
     */
    private const val KWS_DIR = "kws"

    private const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    private const val TOKENS = "tokens.txt"
    private const val KEYWORDS = "keywords.txt"

    private val WANT = listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS)

    // ------------------------------------------------------------------ 那三个数

    /** 见文件头:这一组是电脑上验过的那一组,**不要照库的默认值改**。 */
    private const val KEYWORDS_SCORE = 1.0f
    private const val KEYWORDS_THRESHOLD = 0.25f
    private const val NUM_TRAILING_BLANKS = 1

    /** 她脑子里那份模型也在吃 CPU;这边给 2 个线程够它跟上实时了。 */
    private const val NUM_THREADS = 2

    // ------------------------------------------------------------------ 状态

    @Volatile private var enabled = false
    @Volatile private var running = false
    @Volatile private var miss = 0
    @Volatile private var lastFire = 0L
    @Volatile private var appCtx: Context? = null

    /**
     * 麦克风是不是正被别处用着(推流)。[MainActivity] 接线,本类不认识它。
     * ★ 留着不动:它是「推流优先」那条规矩的入口,和 [roomInFront] 是两件事。
     */
    @Volatile var micHook: (() -> Boolean)? = null

    /**
     * 她的房间是不是正在前台。
     *
     * ★★ 这是这个类和[耳朵][Ear]唯一的冲突点,**必须让**:
     *   麦只有一个。她的耳朵只在房间里跑(房间一退到后台就收工),
     *   所以「房间在前台」= 「麦克风该归她」。这一头要是死攥着不放,
     *   症状是他进了房间点 🎤,她回一句「**唤醒 正用着麦克风**」——
     *   听起来像坏了,其实是两处自己人抢。
     *
     * ★ 用 setter 立刻放麦,**不能等下一轮对表** —— 他进屋到按 🎤 之间
     *   可能一秒都不到,那会儿麦还在我们手上。
     *
     * ★ 诚实说一句代价:房间开着的时候喊「从漫」不会应。
     *   那不是退步 —— 之前那个开关**从来就没工作过**。
     */
    @Volatile
    var roomInFront: Boolean = false
        set(value) {
            field = value
            if (value) stopListening()
        }

    private var spotter: KeywordSpotter? = null
    private var mic: EarMic? = null
    private var worker: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ 模型在不在

    private fun kwsDir(ctx: Context) = File(File(ctx.getExternalFilesDir(null), "asr"), KWS_DIR)

    private fun hasAll(dir: File) = WANT.all { File(dir, it).isFile }

    /**
     * 找出模型在哪个目录。
     *
     * `kws/` 下面**平铺**是最省事的形状,但 `adb push` 一个解包出来的目录
     * (里面自带一层 `sherpa-onnx-kws-zipformer-…/`)是最自然的动作 ——
     * 所以两层都认。照 [Ear.locate] 的样子,**找不到就返回 `kws/` 本身**,
     * 让 [missingPieces] 去报缺了什么,不在这里替它猜路径。
     */
    private fun locate(ctx: Context): File {
        val root = kwsDir(ctx)
        if (hasAll(root)) return root
        val subs = root.listFiles { f -> f.isDirectory } ?: return root
        return subs.firstOrNull { hasAll(it) } ?: root
    }

    /**
     * 缺哪些文件。空 = 齐了。
     *
     * ★ 报的是**绝对路径** —— 他要知道的就是「往哪儿放」。
     */
    fun missingPieces(ctx: Context): List<String> {
        val dir = locate(ctx)
        val miss = WANT.filterNot { File(dir, it).isFile }.map { File(dir, it).absolutePath }
        if (miss.isEmpty()) return emptyList()
        return if (!dir.isDirectory) miss + listOf("(目录还不存在:${dir.absolutePath})") else miss
    }

    /**
     * 能不能用。
     *
     * ★ 这一条从「系统里有没有那个识别服务」换成了「**模型文件在不在**」——
     *   原来那个判据在这台机器上恒为 false,所以界面永远显示「还没装」,
     *   而现在它第一次真的在回答一个可以被修好的问题。
     */
    fun available(ctx: Context): Boolean = missingPieces(ctx).isEmpty()

    // ------------------------------------------------------------------ 开关

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    /** 有录音权限吗。没权限的时候后台拿到的也是静音,所以这道门必须在前面就关上。 */
    private fun canRecord(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 开关入口(持久化 + 立即生效)。**要 RECORD_AUDIO 运行时权限** ——
     * 调用方([ConMarnActivity])负责先请求权限、拿到结果再调这个。
     */
    fun setEnabled(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        appCtx = app
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        arm(app, on)
    }

    /**
     * App 回前台时对一下表:上次开着、这轮进程还在,就把监听续上。
     *
     * ★★ 它现在还有**第二个、更要紧的用处**:前台服务只能从前台上下文启动
     *   (Android 12+ 的规矩),而这里是全项目唯一一个「她进房间了」的时机。
     *   开机重启之后打开一次她的房间,就能把服务和监听一起重新锚住 ——
     *   没有这一步,重启之后那个开关会永远停在「开」而实际什么都不做。
     */
    fun applySetting(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        arm(app, isEnabled(ctx))
    }

    /**
     * 开关真正的落点 —— [setEnabled] / [applySetting] **都走这儿**。
     * 一处改盘、一处起停,不再有两份长得差不多的代码各自演进。
     *
     * ★★ 开着的那条路**必须把那个麦克风型前台服务一起挂上** ——
     *   它是「她退到后台还听不听得见」的唯一开关(见 [WakeService] 文件头)。
     *   只置内存不起服务 = 按钮显示「开」、人却喊不应,而且**一声不响**。
     *
     * ★ 幂等:[WakeService] 挂上之后会回头调一次 [applySetting] 把监听续上,
     *   那一趟再进 [WakeService.start] 会因为 `alive` 已经是 true 而直接返回。
     */
    private fun arm(ctx: Context, on: Boolean) {
        enabled = on
        main.removeCallbacks(poll)

        if (!on) {
            stopListening()
            WakeService.stop(ctx)
            return
        }

        if (!canRecord(ctx)) {
            // ★ 没权限就**什么都不做**,不是「先开着等权限」——
            //   那样盘上是 true、屏幕上写着「开」,而录到的全是零。收手并说清楚。
            abort(ctx, "没有录音权限", "还没有麦克风的权限,唤醒打不开")
            return
        }

        WakeService.start(ctx)
        // ★ 服务从启动到 onStartCommand 有一小段路,但**不在这里等它** ——
        //   它回来时会自己调一次 [applySetting] 把监听续上。这一句是「服务本来就在跑」
        //   那最常见的情况:立刻就能听。
        startListening(ctx)
        main.postDelayed(poll, POLL_MS)
    }

    /**
     * 收手:开关收回「关」、**连盘一起改**、停监听、**收掉那条常驻通知**。
     *
     * ★ 为什么要有这么一个统一的地方:自动收手原本散在**三处**(模型缺件 / 模型起不来 /
     *   连着解码失败),三份几乎一样的代码各自写盘。它们里漏掉任何一件事的后果都是
     *   「按钮显示开着,而它其实一次都没听过」—— 真机踩过。
     * ★★ 而从 2026-10-08 起它们还必须**一起把 [WakeService] 那条通知收掉**:
     *   一条写着「语音唤醒开着」的常驻牌子挂在那儿,而她已经不在听了 —— 那也是谎。
     */
    private fun abort(ctx: Context, reason: String, toastMsg: String?) {
        enabled = false
        main.removeCallbacks(poll)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, false).apply()
        ModelManager.get(ctx).trace("$TAG $reason")
        if (toastMsg != null) toast(ctx, toastMsg)
        stopListening()
        WakeService.stop(ctx)
    }

    /**
     * 前台服务没起来 —— [WakeService] 自己报上来的。
     *
     * ★ 起点是那句「**起不来就绝不装着起得来**」:服务没挂上 = 后台那段时间听到的是静音
     *   = 喊她不应,而**一声不响**。所以当场把开关收回「关」,和他的按钮说同一句话。
     */
    internal fun onServiceFailed(ctx: Context, why: String = "startForeground 失败") {
        abort(ctx, "唤醒前台服务没起来($why) —— 后台听不到", "唤醒没起得来($why),先把它关了")
    }

    // ------------------------------------------------------------------ 对表

    /**
     * 隔一会儿问她一次「现在能不能开口」。
     *
     * ★ 为什么要这么个东西,而不是失败就 `postDelayed` 重试一次:
     *   挡在前面的是**三件会自己变好的事** —— 房间关了、推流停了、
     *   模型刚推上去。它们没有一个会来通知我们。对表是唯一说得通的做法,
     *   而且只在开关**开着**的时候转(关着的时候一条消息都没有)。
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!enabled) return
            val ctx = appCtx
            if (ctx != null && !running) startListening(ctx)
            main.postDelayed(this, POLL_MS)
        }
    }

    // ------------------------------------------------------------------ 开听 / 收工

    private fun startListening(ctx: Context) {
        if (running || !enabled) return

        val miss = missingPieces(ctx)
        if (miss.isNotEmpty()) {
            abort(ctx, "唤醒词还没装 —— 缺:${miss.joinToString("、")}",
                "唤醒词还没装(缺 ${miss.size} 个文件),先把开关关上了")
            return
        }

        // 房间在前台 / 推流占着麦 → 这一轮不开口。**不算失败**,下一次对表再看。
        if (roomInFront) return
        if (micHook?.invoke() == true) return

        val sp = try {
            ensureSpotter(ctx)
        } catch (t: Throwable) {
            // ★ 抓 Throwable:模型文件坏掉 / native 库没装好,两种都是 Error 那一侧。
            abort(ctx, "唤醒模型起不来 ${t.javaClass.name}: ${t.message} —— 唤醒整个不可用",
                "唤醒模型起不来,先把开关关了 —— 原因记在日志里了")
            return
        }

        val queue = LinkedBlockingQueue<FloatArray>()
        val m = EarMic(
            MicGate.WAKE,
            onChunk = { queue.offer(it) },
            onError = { why ->
                // 麦真的坏了(拔了/被系统收回)。交给 loop 那一侧的收尾去停,这里只记一笔。
                ModelManager.get(ctx).trace("$TAG 唤醒的麦克风断了:$why")
            },
        )
        val err = m.start()
        if (err != null) {
            // 被占 / 参数不支持。**不报错也不关开关** —— 让下一次对表再试。
            ModelManager.get(ctx).trace("$TAG 唤醒这一轮没拿到麦克风:$err")
            return
        }

        val stream = sp.createStream()
        mic = m
        running = true
        worker = Thread({ loop(ctx, sp, stream, queue) }, "wake-kws").apply {
            isDaemon = true
            start()
        }
        ModelManager.get(ctx).trace("$TAG 唤醒在听了(阈值 $KEYWORDS_THRESHOLD,score $KEYWORDS_SCORE)")
    }

    /**
     * 收工。**任何线程都能调,幂等。**
     *
     * ★ 这里**不释放 [spotter]** —— 它是整个进程共用的那份(约 5MB),
     *   反复起停时重建一次要好几百毫秒,而重新挂回去是常态。
     *   真正要还的是**麦克风**和那条 [OnlineStream](后者在 [loop] 的 finally 里还)。
     */
    @Synchronized
    private fun stopListening() {
        if (!running && mic == null) return
        running = false
        val m = mic
        mic = null
        worker = null
        try { m?.stop() } catch (_: Throwable) {}
    }

    private fun ensureSpotter(ctx: Context): KeywordSpotter {
        spotter?.let { return it }
        val dir = locate(ctx)
        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = EarMic.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, ENCODER).absolutePath,
                    decoder = File(dir, DECODER).absolutePath,
                    joiner = File(dir, JOINER).absolutePath,
                ),
                tokens = File(dir, TOKENS).absolutePath,
                numThreads = NUM_THREADS,
                // ★ 官方那个例子把它写成 "zipformer2";留空是没验过的形状。
                modelType = "zipformer2",
            ),
            keywordsFile = File(dir, KEYWORDS).absolutePath,
            keywordsScore = KEYWORDS_SCORE,
            keywordsThreshold = KEYWORDS_THRESHOLD,
            numTrailingBlanks = NUM_TRAILING_BLANKS,
        )
        val t0 = System.currentTimeMillis()
        val sp = KeywordSpotter(config = cfg)
        spotter = sp
        ModelManager.get(ctx).trace("$TAG 唤醒模型就绪(${System.currentTimeMillis() - t0}ms)")
        return sp
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 解码线程。**它只做一件事:把声音喂进去,把命中捞出来。**
     *
     * ★ `getResult` 一次就把关键词**和**它听到的那串拼音一起给回来,
     *   所以「先读 tokens 再读结果」那个顺序坑(Python 那边有)**在这里不存在**。
     */
    private fun loop(
        ctx: Context,
        sp: KeywordSpotter,
        stream: OnlineStream,
        queue: LinkedBlockingQueue<FloatArray>,
    ) {
        val batch = ArrayList<FloatArray>(32)
        // ★ 静音计时。见下面那一段:整段听下来一个非零样本都没有 = 系统没把麦克风给我们。
        var zeroSec = 0f
        var silenceSaid = false
        try {
            while (running) {
                batch.clear()
                val first = try {
                    queue.poll(30, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                if (first != null) {
                    batch.add(first)
                    while (true) batch.add(queue.poll() ?: break)
                }
                if (!running) return
                if (batch.isEmpty()) continue

                for (c in batch) {
                    stream.acceptWaveform(c, EarMic.SAMPLE_RATE)

                    // ★★ 数「系统到底有没有把声音给我们」。
                    //
                    //   这一版唤醒默认**一直在后台听**,而后台能不能拿到麦克风取决于
                    //   那个前台服务在不在(见 [WakeService] 文件头)。它没生效的时候,
                    //   `AudioRecord` **不报错、不抛异常,只喂一耳朵零** ——
                    //   于是症状是「喊她不应」,和「没喊对」「阈值太紧」**长得一模一样**,
                    //   而且在屏幕上完全看不出来。
                    //
                    //   判据是**精确的零**:真的麦克风连呼吸底噪都有,至少会带上 ±1 LSB 的抖动。
                    //   连续 [SILENT_GIVEUP_SEC] 秒一个非零都没有,那就不是安静,是没给。
                    //
                    //   ★ 每次 [startListening] 只报一次(`silenceSaid`),不然会刷屏;
                    //     中间只要有一个非零样本就把计时清零。
                    if (!silenceSaid) {
                        if ((c.minOrNull() ?: 0f) == 0f && (c.maxOrNull() ?: 0f) == 0f) {
                            zeroSec += c.size.toFloat() / EarMic.SAMPLE_RATE
                            if (zeroSec >= SILENT_GIVEUP_SEC) {
                                silenceSaid = true
                                ModelManager.get(ctx).trace(
                                    "$TAG 连着 ${SILENT_GIVEUP_SEC.toInt()} 秒一个非零样本都没有 —— " +
                                        "系统没把麦克风给后台的她(前台服务没生效?)"
                                )
                            }
                        } else {
                            zeroSec = 0f
                        }
                    }
                }
                while (sp.isReady(stream)) sp.decode(stream)
                miss = 0

                val r = sp.getResult(stream)
                if (r.keyword.isNotEmpty()) {
                    // ★★ 命中的那一下,把她听到的拼音记下来。
                    //    「没喊对」和「根本没听见」在真机上长得一模一样 ——
                    //    这是事后唯一能分辨的一条线。
                    ModelManager.get(ctx).trace(
                        "$TAG 唤醒命中「${r.keyword}」模型听到的拼音=[${r.tokens.joinToString(" ")}]"
                    )
                    sp.reset(stream)
                    hit(ctx)
                }
            }
        } catch (t: Throwable) {
            ModelManager.get(ctx).trace("$TAG 唤醒解码出错 ${t.javaClass.name}: ${t.message}")
            if (++miss >= MAX_MISS) {
                abort(ctx, "唤醒连着 $MAX_MISS 次解码出错,先关了",
                    "语音唤醒连着出错,先关了。去 ConMarn 界面再开。")
            }
        } finally {
            try { stream.release() } catch (_: Throwable) {}
            // 正常收工时 running 已经是 false;出错退出时它还立着 —— 那就交给对表重来。
            if (running) main.post { stopListening() }
        }
    }

    /** 喊中了。**先把麦克风放开**,再去找她 —— 她跳出来之后房间里那套要立刻能拿到麦。 */
    private fun hit(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFire < HIT_COOLDOWN_MS) return
        lastFire = now
        stopListening()
        main.post {
            toast(ctx, "我在。")
            try {
                ctx.startActivity(
                    Intent(ctx, ConMarnActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                // 后台拉界面的豁免不是 100%:最坏就是只应一声不跳界面,别崩
            }
            // 隔几秒再挂回去。房间开了的话,对表里 [roomInFront] 那一关会继续让它等着,
            // 等她退出来才重新开始听。
            main.removeCallbacks(poll)
            main.postDelayed(poll, REARM_MS)
        }
    }

    private fun toast(ctx: Context, s: String) =
        main.post { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show() }
}
