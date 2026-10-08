package com.example.touchpad

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 她的长期记忆 —— 跨会话记住「他是谁、他怎么说话」。
 *
 * 2026-10-03 用户点名要的:[[conmarn-fused-persona]] 里那条「长期记忆(跨会话称呼/习惯,
 * 和 ASR 纠错共用一套)」。和 [MoodStore] 是两种东西:**心情是此刻的状态,记忆是攒下来的资产**。
 *
 * ## 两条采集路线(想清楚「信谁」才写的)
 *
 * 1. **称呼 —— 只认明确信号。** 「叫我小明」「以后叫我小明」这类是**他亲口下的定义**,
 *    抽出来可以直接用。反过来「我是学生」这种**绝不敢抽**:抽错的代价是她一本正经地
 *    管他叫「学生」,而他会觉得她蠢 —— 记忆错了比没有记忆更糟。
 * 2. **习惯 —— 从行为里数,不从话里猜。** 他「常用什么」不靠解析句子(「我喜欢你」
 *    能抽出一个「你」来),而是数**他实际让 AI 开的应用**:open_app 成功一次记一笔。
 *    这是最诚实的信号 —— 他做的事比他说的话可信。
 *
 * ## 和 ASR 纠错共用一套
 *
 * 「不是威信,是微信」这句话同时是两件事:一个称呼纠正,和一条**同音字映射**。
 * 存进 [Store.fixes] 之后,语音输入的文字下次会被 [correct] 自动换掉 —— 他说错一次,
 * 以后就再也不用说第二次。这也是为什么这个文件和 ASR 共用一份词典而不是各存一套。
 *
 * ## 缓存纪律(和 [MoodStore] 同一条)
 *
 * 拼给模型的那块**必须缀在历史后面、新 user 消息前面**:SYSTEM_PROMPT + 工具表那截
 * 前缀是预热缓存的生命线,一个字都不能动([[ruoxi-prefix-budget-and-warmup]])。
 *
 * 纯规则拆在 [LexiconMath](不碰 org.json / Context),让 [UserLexiconTest] 纯 JVM 钉住 ——
 * 这层写错的表现是「她突然开始胡说他是谁」,真机上极难发现。
 */
object UserLexicon {

    private const val MAX_CALL = 3          // 他允许被叫的名字,最多记几个
    private const val MAX_FIXES = 30        // 同音字映射,别让它无限长
    private const val MAX_APPS = 40

    private var file: File? = null
    private val lock = Any()

    /** 一份记忆快照。list/map 都是不可变的,改就是整体换一份再落盘。 */
    data class Store(
        val call: List<String> = emptyList(),
        val avoid: List<String> = emptyList(),
        val apps: Map<String, Int> = emptyMap(),
        val fixes: Map<String, String> = emptyMap(),
    )

    fun init(ctx: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = File(ctx.filesDir, "lexicon").apply { mkdirs() }
            val f = File(dir, "lexicon.json")
            if (!f.exists()) write(f, Store())
            file = f
        }
    }

    /**
     * 他刚说了一句话 —— 抽事实、合并、落盘。**静默、不抛**:记忆是锦上添花,
     * 不该让任何一轮任务为它报错。
     */
    fun observe(userText: String) {
        val f = file ?: return
        val facts = LexiconMath.extract(userText)
        if (facts.call.isEmpty() && facts.avoid.isEmpty() && facts.fixes.isEmpty()) return
        synchronized(lock) {
            var s = read(f)
            // 禁止的叫法要同时从「允许」里划掉 —— 「别叫我老板」之后还在喊老板最尴尬
            val call = (facts.call + s.call)
                .filterNot { it in facts.avoid || it in s.avoid }
                .distinct()
                .take(MAX_CALL)
            val fixes = LinkedHashMap(s.fixes)
            facts.fixes.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) fixes[k] = v }
            s = s.copy(
                call = call,
                avoid = (s.avoid + facts.avoid).distinct().take(MAX_CALL),
                // toList() 再 takeLast:Map.entries 是 Set,Set 上没有 takeLast,
                // 而 LinkedHashMap 的 entries 顺序就是插入顺序 —— 留下的正是最新的几条。
                fixes = fixes.entries.toList().takeLast(MAX_FIXES).associate { it.key to it.value },
            )
            write(f, s)
        }
    }

    /** 他让你开了个应用并且**真开成了** —— 记一笔。这是他「常用什么」最诚实的来源。 */
    fun noteApp(name: String) {
        val f = file ?: return
        val n = name.trim()
        if (n.isEmpty() || n.length > 24) return
        synchronized(lock) {
            val s = read(f)
            val apps = LinkedHashMap(s.apps)
            apps[n] = (apps[n] ?: 0) + 1
            val kept = apps.entries.sortedByDescending { it.value }.take(MAX_APPS)
                .associate { it.key to it.value }
            write(f, s.copy(apps = kept))
        }
    }

    /**
     * 语音转出来的文字过一遍同音字映射 —— 他纠正过一次的错,不该让他再纠正第二次。
     * 没有记忆/没有命中时**原样返回**。
     */
    fun correct(text: String): String {
        val f = file ?: return text
        val fixes = synchronized(lock) { read(f).fixes }
        return if (fixes.isEmpty()) text else LexiconMath.correct(text, fixes)
    }

    /** 拼给模型看的那块。没攒下东西时返回 null(调用方安静跳过)。 */
    fun block(): String? {
        val f = file ?: return null
        val s = synchronized(lock) { read(f) }
        return LexiconMath.block(s.call, s.avoid, s.apps, s.fixes)
    }

    // ---- 落盘(原子写,和 MoodStore / ExperienceStore 同一套) ----

    private fun read(f: File): Store = try {
        val o = JSONObject(f.readText())
        Store(
            call = o.optJSONArray("call").toStringList(),
            avoid = o.optJSONArray("avoid").toStringList(),
            apps = o.optJSONObject("apps").toStringIntMap(),
            fixes = o.optJSONObject("fixes").toStringMap(),
        )
    } catch (_: Exception) {
        Store()      // 坏了就重开一页,不抛
    }

    private fun write(f: File, s: Store) {
        try {
            val apps = JSONObject()
            s.apps.forEach { (k, v) -> apps.put(k, v) }
            val fixes = JSONObject()
            s.fixes.forEach { (k, v) -> fixes.put(k, v) }
            val o = JSONObject()
                .put("call", JSONArray(s.call))
                .put("avoid", JSONArray(s.avoid))
                .put("apps", apps)
                .put("fixes", fixes)
                .put("savedAt", System.currentTimeMillis() / 1000)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())   // Windows 上偶发 rename 不过,兜一手
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        val a = this ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        val o = this ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        o.keys().forEach { k -> m[k] = o.optString(k) }
        return m
    }

    private fun JSONObject?.toStringIntMap(): Map<String, Int> {
        val o = this ?: return emptyMap()
        val m = LinkedHashMap<String, Int>()
        o.keys().forEach { k -> m[k] = o.optInt(k) }
        return m
    }
}

/**
 * 记忆的纯规则。**没有 Android / org.json 依赖** —— 这层写错的表现是
 * 「她突然开始胡说他是谁」,而不是崩溃,最需要测试钉住。
 */
internal object LexiconMath {

    /** 一句话里能抽出来的东西。三样都可能为空。 */
    data class Facts(
        val call: List<String> = emptyList(),
        val avoid: List<String> = emptyList(),
        val fixes: Map<String, String> = emptyMap(),
    )

    /**
     * 名字的守门人。**这是整个抽取里最要紧的一行**:
     * 「我叫你别乱动」也含「我叫」,但「你别乱动」显然不是名字 ——
     * 宁可漏掉一个真名字,也不能让她管他叫「你别乱动」。
     */
    private val NOT_IN_NAME = listOf(
        "你", "我", "他", "她", "别", "不", "没", "吗", "呢", "吧", "啊", "呀",
        "的", "了", "要", "会", "能", "想", "是", "把", "给", "和", "就",
    )

    internal fun looksLikeName(s: String): Boolean =
        s.isNotBlank() && s.length <= 8 && NOT_IN_NAME.none { s.contains(it) }

    // 「叫我小明」「以后叫我小明」「你可以叫我小明」「我叫小明」「就叫我老王」
    //
    // ★ 这一条踩过两个坑,每个都对应一种「她开始胡说他是谁」:
    //
    //  1. **不能只在句首认「我叫」。** 那会让「你可以叫我老王」整个漏掉 ——
    //     「可以」前面是「你」,不是分隔符,正则从那里起不了头。可这偏偏是最自然的说法。
    //  2. **也不能放任句中的「叫我」。** 「我妈叫我吃饭」会把「吃饭」记成名字,
    //     从此她一本正经管他叫「吃饭」—— 教学费的是他。
    //
    // 所以开头的那个词法位置是**三选一**:句首/分隔符之后,或者前面正好缀着
    // 「可以 / 以后 / 之后 / 就 / 请 / 都 / 得」这类**明确的定义口气**。
    // 「我妈叫我吃饭」的「叫我」前是「妈」,三个都不沾 → 不收。
    // 宁可漏掉几个真名字,也不能收进来一个「吃饭」。
    private val CALL = Regex(
        """(?:^|[\s,，。.！!?？;；:：]|可以|以后|之后|就|请|都|得)(?:以后|之后|可以|就|请)?(?:叫我|我叫)\s*([^\s,，。.!！?？;；]{1,8})"""
    )
    // 「别叫我老板」「不要叫我老板」「不许叫我老板」
    private val AVOID = Regex("""(?:别|不要|不许|不准)叫我\s*([^\s,，。.!！?？;；]{1,8})""")
    // 「不是威信,是微信」「不叫王老师,叫王哥」—— 一条纠正 = 一个称呼 + 一条同音字映射
    private val FIX = Regex(
        """不(?:是|叫)\s*([^\s,，。.!！?？;；]{1,12})\s*[,，、]?\s*(?:是|叫)\s*([^\s,，。.!！?？;；]{1,12})"""
    )

    internal fun extract(text: String): Facts {
        val avoid = AVOID.findAll(text).map { it.groupValues[1] }.filter { looksLikeName(it) }.toList()
        val call = CALL.findAll(text).map { it.groupValues[1] }
            .filter { looksLikeName(it) }
            .filterNot { it in avoid }
            .toList()
        val fixes = LinkedHashMap<String, String>()
        FIX.findAll(text).forEach { m ->
            val bad = m.groupValues[1]
            val good = m.groupValues[2]
            // bad/good 完全一样,或 good 本身就是个禁词,都不收 —— 学坏一条比少学一条糟
            if (bad != good && good.isNotBlank() && looksLikeName(good)) fixes[bad] = good
        }
        return Facts(call.distinct(), avoid.distinct(), fixes)
    }

    /**
     * 同音字替换。**一次过,不链式** —— 换完的句子不会再被后面的规则扫第二遍。
     *
     * 为什么强调这个:逐个 `replace` 看起来一样,其实不 —— A→B、B→C 同时存在时,
     * 逐个替换会把「A」一路换成「C」,而他**从来没这么纠正过**。规则得可预测:
     * 一次纠正只走一步。(顺带也没有「A→B、B→A 互相弹」这类来回。)
     *
     * 长词优先:「微信号」不会先被「微信」切一刀,再拿剩下的去匹配别的。
     */
    internal fun correct(text: String, fixes: Map<String, String>): String {
        val keys = fixes.keys.filter { it.isNotEmpty() && fixes[it] != it }
            .sortedByDescending { it.length }
        if (keys.isEmpty()) return text
        val re = Regex(keys.joinToString("|") { Regex.escape(it) })
        return re.replace(text) { m -> fixes[m.value] ?: m.value }
    }

    /** 拼提示词的那块。什么都没有 → null(不许输出一个空的「我记得:」吓人)。 */
    internal fun block(
        call: List<String>,
        avoid: List<String>,
        apps: Map<String, Int>,
        fixes: Map<String, String>,
    ): String? {
        val lines = mutableListOf<String>()
        if (call.isNotEmpty()) lines += "他让你叫他:${call.joinToString("、")}。"
        if (avoid.isNotEmpty()) lines += "他明确说过**别**这么叫他:${avoid.joinToString("、")}。"
        val top = apps.entries.sortedByDescending { it.value }.take(5)
        if (top.isNotEmpty()) {
            lines += "他常让你开的应用:" + top.joinToString("、") { "${it.key}(${it.value}次)" } + "。"
        }
        if (fixes.isNotEmpty()) {
            val sample = fixes.entries.take(6).joinToString("、") { "「${it.key}」指「${it.value}」" }
            lines += "他纠正过的说法:$sample。"
        }
        if (lines.isEmpty()) return null
        return """【你记得的关于他的事 —— 这是**以前攒下来的**,不是他这一轮说的】
${lines.joinToString("\n")}
自然地用(叫他、听懂他的话),但别像在念档案,也别提「我记得数据库里有」这种话。
没把握的宁可不用 —— 记错了比不记得更让他失望。"""
    }
}
