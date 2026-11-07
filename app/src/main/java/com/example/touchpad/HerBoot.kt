package com.example.touchpad

import android.content.Context
import android.content.Intent

/**
 * 「把她拉起来」的唯一入口 —— **不依赖任何 Activity**。
 *
 * ★ 为什么要单独有这个文件:全项目唯一拉起模型的地方原来长在 [MainActivity] 的
 *   onCreate 里(`mm.onState` 回调 → `warmUpLocalModel()`)。而用户平时点的多半是
 *   **她自己那个桌面图标**(→ [ConMarnActivity]),那条路只调 `AiAgentHolder.get()`,
 *   **从不预热**。于是她最常用的那扇门恰好是最慢的那扇:第一句话要走
 *   「连接被拒 → 按需拉脑(装载 ~12 秒)→ 重试 → 槽里没前缀 → 现场冷算」。
 *
 * 顺序不能换,三件事:
 *   1. [AiAgentHolder.get] 把 agent **建**出来;
 *   2. [ModelManager.start] 起脑(幂等,已经在跑就直接返回);
 *   3. 盯到 READY,再在后台跑一次 [AiAgent.warmUp] —— 那一步同时会还原槽位快照。
 *
 * ★ 为什么必须 `get()` 而不是 `peek()`:
 *   [ProactiveGreeting.maybeFire] 用的是 `AiAgentHolder.peek()`(**只取不建**,
 *   `ProactiveGreeting.kt:149`)。后台若是没人开过 App,agent 从未被创建 → `peek()`
 *   恒为 null → **她永远不开口**,而且不报任何错。把 agent 的创建权收到这里,
 *   这个口子就堵死了。
 *
 * ★ 为什么不接 [ModelManager.onState] 而用轮询:
 *   那是个**单个** var,已经被 MainActivity 占着(onState 只报脑的状态),抢过来
 *   会在两个界面之间打架。轮询只慢几百毫秒,而且一行既有代码都不用动。
 *
 * ★ 这里**也管「她一直在」的锚**(`her_life/alive`):开着就把 [HerLifeService] 补起来
 *   (见 [anchor])。理由不是「顺手」,是**实测出来的一个洞** —— 开机广播会被 ColorOS
 *   押后几分钟,而用户开 App 那一下不会拉起服务,那段时间里她是没人护着的。
 *   把锚挂在「把她拉起来」这个唯一入口上,不管她从哪条路来都跑不掉。
 */
object HerBoot {

    /** 等装载的上限。实测装载 ~12 秒,热降频时会久得多,给足余量。 */
    private const val READY_TIMEOUT_MS = 180_000L

    /** 轮询间隔。250ms 对「慢几百毫秒」这个代价来说够用了。 */
    private const val POLL_MS = 250L

    private val lock = Any()

    /**
     * 正在盯着装载的那个线程。还活着就不再起第二个 ——
     * 连点两下桌面图标会走两条 ensure,不该因此多跑一遍预热(那是 91 秒的 CPU 密集活)。
     */
    @Volatile private var job: Thread? = null

    /**
     * 保证她起来了。**幂等,可以从任何地方随便调** ——
     * 房间、触控板、以后的开机广播和通知。
     */
    fun ensure(ctx: Context) {
        val app = ctx.applicationContext
        val mm = ModelManager.get(app)

        // ① 建 agent。必须在任何提前返回**之前** —— 就算脑起不来,
        //    回执路由和人格式的对话也要在(见类注释里那条静默失败的坑)。
        val agent = AiAgentHolder.get(app)

        // ② 起脑(幂等:已经在跑/正在起都会直接返回,不会起第二个 llama-server)
        mm.start()

        // ②′ 「一直在」开着,就把**锚**也打上 —— 见 [anchor] 的长注释。
        anchor(app, mm)

        // ③ 盯到 READY 再预热
        synchronized(lock) {
            if (job?.isAlive == true) {
                mm.trace("HerBoot:已有线程在盯(${mm.state}),不重复起")
                return
            }
            job = Thread({ watch(mm, agent) }, "ruoxi-boot").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 补打「她一直在」的锚 —— [HerLifeService] 必须是活的,否则进程没人护着。
     *
     * ★★ 为什么需要这一步(2026-10-04 真机实测发现的洞):
     *
     *   开机自启动**是成立的,但会被 ColorOS 押后**。实测那次重启:
     *   ```
     *   17:31:26  重启
     *   17:31:42  系统要投 BOOT_COMPLETED → 标记 not runnable
     *             (DEFER_BY_OPLUS / SUB_REASON: PRESSURE FROZEN)→ DEFERRED OFFLOAD
     *   17:32:16  用户点图标进 App —— ★ 但这一下并**没有**把服务拉起来
     *   17:36:19  那条被押后的 BOOT_COMPLETED 才到账 → 服务这才起
     *   ```
     *   中间那 4 分 03 秒里她**没有前台服务锚着**。用户开完 App 就锁屏揣兜里的话,
     *   她照样会被冻 —— 而那正是「一直在」要解决的问题。
     *
     * ★ 修法不是在开机那条路上加东西(BootReceiver 本身是对的、也验过),
     *   而是**让「把她拉起来」这件事本身就顺手把锚打上**:不管她是从开机广播、
     *   桌面图标、通知还是别的什么路来的,只要 `alive=true`,服务就该在。
     *
     * ★ 幂等靠 [HerLifeService.isRunning]。这一条同时挡住了**递归**:
     *   [HerLifeService.onStartCommand] 会在开头就把 `instance` 设上(`:46`),
     *   **然后**才调 [ensure](`:93`)—— 所以服务自己那条路进来时这里必然提前返回。
     *   (顺序是脆的:谁要是把 `instance = this` 挪到 `ensure` 后面,这里就会无限递归。)
     */
    private fun anchor(app: Context, mm: ModelManager) {
        if (!HerLife.isAlive(app)) return          // 用户关掉的,不复活
        if (HerLifeService.isRunning()) return     // 已经在跑,别惊动它(也是防递归那一条)

        try {
            app.startForegroundService(Intent(app, HerLifeService::class.java))
            mm.trace("HerBoot:「一直在」开着但服务没在跑,补起锚(多半是开机广播被 ColorOS 押后了)")
        } catch (e: Exception) {
            // Android 12+ 后台起前台服务会被拒。所有调用点都在前台(房间/触控板),
            // 所以正常不会走到这儿 —— 但真走到了要留痕,不能静默。
            mm.trace("HerBoot:补起锚被拒(${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /** 盯装载状态。只在 READY 时预热;起不来就安静退出,不弹错误(MainActivity 那边会弹)。 */
    private fun watch(mm: ModelManager, agent: AiAgent) {
        mm.trace("HerBoot:开始盯装载,state=${mm.state}")
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (mm.state) {
                ModelManager.State.READY -> {
                    warmUp(mm, agent)
                    return
                }
                ModelManager.State.FAILED,
                ModelManager.State.MISSING_MODEL -> {
                    // 模型没导入 / 进程起不来:静默退出。用户重新导入后再开一次她就好。
                    mm.trace("HerBoot:脑没起来(${mm.state}:${mm.lastMessage}),跳过预热")
                    return
                }
                else -> Thread.sleep(POLL_MS)
            }
        }
        mm.trace("HerBoot:等装载超时(${READY_TIMEOUT_MS}ms),state=${mm.state}")
    }

    /**
     * 后台跑一次预热 —— 给「系统提示 + 工具表」这截前缀付钱,顺便还原槽位快照。
     *
     * 原文照搬自 MainActivity.warmUpLocalModel():那一段本来就和界面无关,
     * 搬过来只是把「谁触发」从 Activity 换成任何人。
     */
    private fun warmUp(mm: ModelManager, agent: AiAgent) {
        Thread {
            val t0 = System.currentTimeMillis()
            val err = agent.warmUp()
            val cost = System.currentTimeMillis() - t0
            mm.trace(
                if (err == null) "预热完成 ${cost}ms(用户第一轮将命中前缀缓存)"
                else "预热跳过 ${cost}ms:$err"
            )
        }.apply { isDaemon = true; name = "ruoxi-warmup" }.start()
    }
}
