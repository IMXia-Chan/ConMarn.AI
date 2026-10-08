package com.example.touchpad

import android.content.Context
import java.io.File

/**
 * 她手上的**名单** —— 现在有几只手、每只叫什么、会什么。
 *
 * 这是「很多很多只手」那句话的地基。今天名单里最多两三条(电脑、手机自己、
 * 云端),但形状是按「会有很多只」定的:**按 id 索引、可替换、可落盘、能手不在线**。
 *
 * ## 为什么落盘
 *
 * 不落盘的话,每次冷启动到连上电脑之前,她**看不见自己的手**。那几句
 * 「打开微信」在离线时会被答成「我没有手」。落盘的真正用处不在今天(今天只有
 * 一台电脑,连上就有),在于**手变多以后**:有的手(云端、手机自己)一直在,
 * 有的(某台电脑、某个传感器)时来时走,名单得比连接活得久。
 *
 * ## ★ 落盘那份是**可能过期的**,而且这件事要写在脸上
 *
 * 手不在线时,我们只能用上次问到的能力清单。那份可能已经过期。
 * [Hand.fetchedAtMs] 就是**诚实的账**,回执里由 [HandMath.staleNote] 说实话。
 * 不写这句的代价:脑照着三个月前的菜单点菜,得到「没这个工具」,然后开始瞎试 ——
 * 这正是这个项目反复吃过的那种错(「看不见」被当成「没有」的下一个马甲)。
 *
 * ## 纯逻辑在别处
 *
 * 合并/查找/过期判断这些**会出错但不崩**的规则全在 [HandMath](纯函数、纯 JVM 可测)。
 * 这里只做三件无聊事:持有、落盘、加锁。
 */
object HandRegistry {

    private var file: File? = null
    private val lock = Any()

    /** 内存里那一份。落盘只在这之上做投影。 */
    private var cache: List<Hand> = emptyList()

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            // 落盘那份坏了一条就丢一条(见 HandCodec.listFromJson);整份读不动
            // 就当没存过 —— 空名单是「还没问到过」,不是「她没有手」,这两句
            // 在别处(subject 的措辞)分得很清。
            try {
                val f = File(File(ctx.filesDir, "hands").apply { mkdirs() }, "hands.json")
                // ★ 先把落盘目标认下来,再去读:读坏了也要能写回去(把坏档案换掉)。
                file = f
                if (f.exists()) cache = HandCodec.listFromJson(f.readText())
            } catch (_: Exception) {
                // 连目录都建不出来:那就没有落盘能力,内存里那份留着照用。
                // 名单是锦上添花,不该让任何流程为它报错。
            }
        }
        // 云端那只手没有「落盘名单」这一步 —— 它的清单就是 `api/tools.json`
        // 本身(面板里管的那份),`init` 只是把上下文交给它。
        // ★ 放在 synchronized 外面:CloudHand.init 里会去读文件,不该占着这把锁。
        CloudHand.init(ctx)
    }

    /**
     * 全部的手。★ 代码里写死的那只(手机自己)在这一步并进来,**不在落盘那份里**。
     *
     * 为什么不让它落盘:它的工具表是**跟着 APK 走的**。存一份到盘上,升级之后
     * 盘上是旧表、代码里是新表,两条同 id 摞着 —— `find` 拿到先出现的那个,
     * 于是「代码里加了工具,她手机上还是老的」,而且不报任何错。
     * 现在这个写法里,盘上**永远不会**有 `self` 那一条(见 [HandMath.withSelf])。
     */
    fun all(): List<Hand> = synchronized(lock) {
        val now = System.currentTimeMillis()
        // 盘上那份**永远不该**有云端手(我们从没写过它们)。滤一道是防「以后哪天写进去了」:
        // 两条同 id 摞着时 `find` 只拿先出现的那个,症状是「面板里改了,她那儿没变」,
        // 而且不报错 —— 和 `self` 那条是同一个病,所以用同一个治法。
        val disk = cache.filterNot { it.id.startsWith(CloudHand.ID_PREFIX) }
        HandMath.withSelf(disk, SelfHand.hand(now)) + CloudHand.hands()
    }

    fun get(id: String): Hand? = HandMath.find(all(), id)

    /**
     * 常驻的那台电脑。今天只有一台,所以「第一只 kind=windows-pc 的手」够用。
     *
     * 多台电脑那天这里要改成「当前连着的那台」—— 但那时候**连接**这个概念本身
     * 也得跟着变(得知道每条连接对应哪只手),所以现在别急着编。
     */
    fun pcHand(): Hand? = synchronized(lock) {
        cache.firstOrNull { it.kind == HandMath.KIND_PC }
    }

    /** 新信息进来。合并规则(尤其「发现应答不许覆盖工具表」)在 [HandMath.merged]。 */
    fun upsert(hand: Hand) {
        val next: List<Hand>
        synchronized(lock) {
            next = HandMath.merged(cache, hand)
            cache = next
        }
        persist(next)
    }

    fun remove(id: String) {
        val next: List<Hand>
        synchronized(lock) {
            next = HandMath.without(cache, id)
            cache = next
        }
        persist(next)
    }

    private fun persist(list: List<Hand>) {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(HandCodec.listToJson(list))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())   // 偶发 rename 不过,兜一手(同 MoodStore)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
