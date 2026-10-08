package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 经验库:「这种界面该怎么找」的随身手册。
 *
 * ## 它解决的是什么
 *
 * 电脑端找不到东西时,现在会回一份**观察**(结构,不含屏幕内容),比如
 * `{kind:"blind", window:"微信", ocr_lines:36}` —— 「这界面认得出字,但没有目标」。
 *
 * 观察只是材料,还得有人回答「那接下来怎么办」。这件事分两半:
 *   - **策略**(用哪个动词)没有确定答案,代码穷举不完,该交给见多识广的一方;
 *   - **机制**(坐标、边界、红线)有确定答案,必须由代码兜死。
 *
 * 这个类就是策略那一半的**快版**:会的东西记下来,下次直接查表。查比想可靠得多 ——
 * 尤其对手机上的 4B,让它「想」出「先 Ctrl+F 再输名字」十次有八次想不出来,
 * 但让它「查」一条已经写好的策略,是查表,不会错。
 *
 * 慢版是微调(见 memory: ruoxi-training-roadmap),要攒够样本再说,不在这一轮。
 *
 * ## 置信度:用出来的,不是存下来的
 *
 * 老师(云端大模型)会教错。**一次存进去就永远照做是危险的**,所以每条经验带计数,
 * 「执行结果」本身就是标注:
 *
 *     置信度 = (wins + 1) / (uses + 2)      // 拉普拉斯平滑,一次没用过 = 0.5
 *
 * - 预置教材(seed)是人写的、已被验证 → 初始 uses=10/wins=9,置信度 ≈ 0.83;
 * - 老师教的(teacher)→ 初始 0/wins=0,置信度 0.5,**必须真用成功几次才升**。
 *
 * 于是有了自我纠正:预置教材在某个应用上不灵 → 失败记一笔 → 置信度往下掉 →
 * 掉到老师那条下面,下次就换它先上。**不需要任何手工调参**。
 *
 * 两条硬规矩:
 * 1. **置信度只决定「先试哪个」,永不决定「能不能做」。** 安全边界(点击不越界、
 *    type 要确认、破坏性操作要点名)永远是代码硬拦 —— 低置信度不能让它放行任何东西。
 * 2. **不采信老师自报的置信度。** llm 自报数字会编,只认实际用出来的胜率。
 *
 * 同 kind 下可以有多条经验共存、按置信度竞争 —— 这顺手就实现了「窗口级精化」
 * (微信→search 上位、某个列表→scroll 上位),不必手工加 key 粒度。
 */
object ExperienceStore {

    /**
     * 允许出现的动词。**封闭集合** —— 老师也只能从这里挑,出了圈的一律丢掉。
     *
     * 故意做得这么窄:网络那头是个会自由发挥的模型,而这边是**要真动手的代码**。
     * 让它能凭空说一个动词出来,就等于把「能干什么」的决定权交给了一段无法检验的文本。
     */
    val VERBS = listOf("scroll", "search", "visual")

    private const val MAX_PER_KIND = 8      // 同一个 kind 下最多留几条,多了淘汰最低的
    private const val MAX_VERBS = 4         // 一条策略最多几个动词,防止老师给一长串

    data class Exp(
        val kind: String,
        val verbs: List<String>,
        val reason: String,
        val source: String,        // "seed" = 预置教材,"teacher" = 云端老师教的
        var uses: Int,
        var wins: Int,
    ) {
        /** 置信度 = 用出来的胜率(拉普拉斯平滑)。只影响顺序,不影响安全边界。 */
        val confidence: Double get() = (wins + 1.0) / (uses + 2.0)

        /** 同一个 kind 下靠它去重:同一套动词序列算同一条,反复教只会加计数、不会繁殖。 */
        val id: String get() = verbs.joinToString(">")

        fun toJson(): JSONObject = JSONObject()
            .put("kind", kind)
            .put("verbs", JSONArray(verbs))
            .put("reason", reason)
            .put("source", source)
            .put("uses", uses)
            .put("wins", wins)
    }

    private var file: File? = null
    private val items = mutableListOf<Exp>()

    // ------------------------------------------------------------------

    fun init(ctx: Context) {
        val dir = File(ctx.filesDir, "experience")
        dir.mkdirs()
        val f = File(dir, "experiences.json")
        file = f
        synchronized(this) {
            items.clear()
            val loaded = try {
                if (f.exists()) parse(f.readText(Charsets.UTF_8)) else emptyList()
            } catch (_: Exception) {
                // 文件坏了不能把功能一起带走 —— 退回家底(预置教材),下次写回去就修好了。
                emptyList()
            }
            if (loaded.isEmpty()) {
                items.addAll(seed())
                save()
            } else {
                items.addAll(loaded)
            }
        }
    }

    /**
     * 预置教材。**冷启动就有东西可用** —— 没有云端 key、或第一次遇到某种界面时,
     * 「不知道」会退化成「什么也不做」,那是最差的结果;有几条常识性的顺序兜着,
     * 至少不会原地卡死。
     *
     * 初始 uses/wins 是**虚构的试用记录**(10 次 9 胜):它们表示「人写的、已验证」,
     * 置信度 0.83,高于老师初来时的 0.5。这不是作弊 —— 真实含义是「先用我的,
     * 除非我的在你这儿不灵」。不灵的话失败会计数,置信度自己会掉下去。
     */
    internal fun seed(): List<Exp> = listOf(
        Exp(
            "offscreen",
            listOf("scroll", "search", "visual"),
            "东西找到了,但它在屏幕外 —— 多半躺在可滚动区域的下面。" +
                "先滚一屏让它自己冒出来(滚是原地不动、最不打扰的试探);" +
                "滚不出来就用这个界面自己的搜索直接把它捞到眼前。",
            "seed", 10, 9
        ),
        Exp(
            "blind",
            listOf("search", "scroll", "visual"),
            "屏幕上有字,但没有目标那个词 —— 要么它埋在很深的列表里,要么这个界面是" +
                "自绘的(微信这一类),控件树压根不暴露。" +
                "两种情况都是**让应用自己去找**最快:打开它自己的搜索,把词喂进去。",
            "seed", 10, 9
        ),
        Exp(
            "empty",
            listOf("visual"),
            "一个字都没认出来 —— 这界面上的东西多半是图形、图标,不是文字。" +
                "按名字找和认字在这时候都是瞎的,只剩看图这条路。",
            "seed", 10, 8
        ),
    )

    // ------------------------------------------------------------------

    /**
     * 这个 kind 下所有可选的策略,**按置信度从高到低**。调用方依次试,成功即停。
     *
     * 返回列表而不是单条:第一条不灵时得有第二条顶上 —— 而「不灵」正是
     * 置信度排序该更新的信息。
     */
    @Synchronized
    fun candidates(kind: String): List<Exp> =
        items.filter { it.kind == kind }
            .sortedWith(compareByDescending<Exp> { it.confidence }.thenByDescending { it.uses })

    /** 这个 kind 有没有经验可查(没有就得去问老师)。 */
    @Synchronized
    fun has(kind: String): Boolean = items.any { it.kind == kind }

    /**
     * 老师教了一条。动词先过封闭集合这一关,出了圈的直接丢 —— 不能因为
     * 「是老师说的」就让它指挥代码干没见过的事。
     *
     * 已经有同一套动词序列就**不新增**,返回那条老的(反复教只会把它用起来)。
     */
    @Synchronized
    fun learn(kind: String, rawVerbs: List<String>, reason: String): Exp? {
        val verbs = rawVerbs.map { it.trim().lowercase() }
            .filter { it in VERBS }
            .distinct()
            .take(MAX_VERBS)
        if (verbs.isEmpty()) return null

        items.firstOrNull { it.kind == kind && it.id == verbs.joinToString(">") }?.let { return it }

        val e = Exp(kind, verbs, reason, "teacher", 0, 0)
        items.add(e)
        // 同 kind 太多了就淘汰置信度最低的那条(平手时留老的 —— 它至少有使用记录)。
        items.filter { it.kind == kind }
            .sortedWith(compareBy<Exp> { it.confidence }.thenBy { it.uses })
            .dropLast(MAX_PER_KIND)
            .forEach { items.remove(it) }
        save()
        return e
    }

    /**
     * 一条经验用完了,报账。**成功才算赢,失败也算用过** ——
     * 后者才是让不好的策略自己沉下去的那一半。
     */
    @Synchronized
    fun record(e: Exp, won: Boolean) {
        e.uses++
        if (won) e.wins++
        save()
    }

    /** 给设置界面看的一行字:库里有几条、其中最信得过的是什么。 */
    @Synchronized
    fun summary(): String {
        if (items.isEmpty()) return "还没有经验"
        val taught = items.count { it.source == "teacher" }
        val best = items.maxByOrNull { it.confidence } ?: return "还没有经验"
        return "${items.size} 条(老师教了 $taught 条)," +
            "最信得过的是「${best.kind}」→ ${best.verbs.joinToString(" → ")}" +
            "(%.0f%%,用过 ${best.uses} 次)".format(best.confidence * 100)
    }

    /** 全部条目,给设置界面列出明细。 */
    @Synchronized
    fun all(): List<Exp> = items.toList()

    // ------------------------------------------------------------------

    private fun parse(text: String): List<Exp> {
        val arr = JSONObject(text).optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<Exp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 用 AiAgent.jsonText 而不是 optString:JSON 里的 null 会被 optString 转成
            // 字面字符串 "null",于是「空值」悄悄变成一条叫 null 的动词(见 [AiAgent.jsonText])。
            val verbs = o.optJSONArray("verbs")?.let { a ->
                (0 until a.length()).mapNotNull { AiAgent.jsonText(a.opt(it))?.takeIf { s -> s.isNotEmpty() } }
            } ?: continue
            // 盘上存的也要过一遍封闭集合:文件是可以被手改的,
            // 而这条路径直通「真动手的代码」。别让一个手滑的字符串变成指令。
            val clean = verbs.filter { it in VERBS }.distinct()
            if (clean.isEmpty()) continue
            val kind = AiAgent.jsonText(o.opt("kind"))?.takeIf { it.isNotEmpty() } ?: continue
            out.add(
                Exp(
                    kind, clean,
                    o.optString("reason"), o.optString("source", "teacher"),
                    o.optInt("uses", 0).coerceAtLeast(0),
                    o.optInt("wins", 0).coerceAtLeast(0),
                )
            )
        }
        return out
    }

    /** 先写临时文件再改名 —— 中途被杀不至于留下半截 JSON(那会让整库读不出来)。 */
    private fun save() {
        val f = file ?: return
        try {
            val root = JSONObject()
                .put("version", 1)
                .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
            // 落盘失败绝不能影响主流程(和 BehaviorLogger 同一条规矩)。
        }
    }
}
