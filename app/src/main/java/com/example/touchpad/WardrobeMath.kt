package com.example.touchpad

/**
 * ★★ 「换装」的判定 —— **换人物形象、换房间布置**,靠往两个目录里丢文件。
 *
 * ## 用户 2026-10-05 选定的形态
 *
 * > 「**放文件就生效** —— 我把新的模型和新房间丢进去,重开一次就好 ——
 * >   **不用改代码、不用重新装 App**」
 *
 * 所以这条路的形状是:两个目录,丢进去,重开。
 * *   `/sdcard/Android/data/com.example.touchpad/files/person/` —— 人物
 * *   `/sdcard/Android/data/com.example.touchpad/files/room/`   —— 房间
 *
 * ## ★ 为什么判定必须在这一层(而不是在 serveAsset 里)
 *
 * `Wardrobe`(那个真去碰文件系统的)在整个项目里是**验不了**的 —— 它要一台手机。
 * 而这一轮(2026-10-05 下午)**手机是拔着的**,我一个字都验不了。
 *
 * 所以规矩照旧建在这个项目最硬的那条惯例上(`MoodMath` / `GreetMath` /
 * `EarSessionMath` / `HandMath` / `SpeechMath`):**能判定的事情全抽成零 Android 依赖的纯逻辑**,
 * 真 I/O 那一层薄到「只剩 open/read」。
 * **不能单测的代码在这里等于不可信**([WardrobeMathTest] 钉住下面每一条)。
 *
 * ## ★★ 这一节里最要紧的一条:白名单不是黑名单
 *
 * 外部目录里的文件会被 serve 进 `https://appassets.androidplatform.net/` 这个**源**,
 * 而那个源上挂着 `HerBridge`(Kotlin 侧的 `@JavascriptInterface`)。
 *
 * **一个 `.html` 或 `.js` 掉进去,就等于在自己的源里执行任意脚本、并且够得着那个桥。**
 * 那不是「换装」,那是这个 App 上开的一个后门 —— 而它长得和换装一模一样。
 *
 * 所以 [ALLOWED_EXTS] **只准列出「数据」**:模型、贴图、`.json`。
 * `.html` / `.js` / `.css` / `.svg` **一个都不在里面**,而且判据是**白名单**
 * (认不出来的一律不放行),不是「把危险的排掉」——
 * 后者漏一个就是上面那件事(`README.md` 里也是这么写给用户看的)。
 *
 * 这和项目既有的两条规矩同族:**宁可漏不可错**([FastPath] 那条)、**fail closed**
 * ([ApiMath.sendsOk] 那条)。
 */
internal object WardrobeMath {

    /** 人物模型认这几个扩展名。今天只有 `.vrm` —— 留成 List 是因为判定要按它写,不是预留。 */
    val MODEL_EXTS = listOf("vrm")

    /** 房间模型认这几个。`.glb` 是二进制、`.gltf` 是文本,**两个都收**。 */
    val SCENERY_EXTS = listOf("glb", "gltf")

    /**
     * ★★ 外部目录里的文件,**只有**这几种会被 serve。
     *
     * 判据是白名单:不在表里的一律当「不存在」,连读都不读。
     * 特别地 —— **`.html` / `.js` / `.css` / `.svg` 永远不在表里**,
     * 原因见文件头。**别往里加。**
     */
    val ALLOWED_EXTS: Set<String> = setOf(
        "vrm",
        "glb", "gltf", "bin",
        "png", "jpg", "jpeg", "webp",
        "json",
    )

    /** 目录里一个候选文件的样子。只留判定用得上的东西,这样它能纯逻辑单测。 */
    data class Candidate(
        val name: String,
        /** 字节数。0 当「坏文件」处理 —— 半截下载、写失败都是这个长相。 */
        val bytes: Long,
        /** 读得到吗。`canRead()` 为 false 和「不在」是**两件事**,要分开说。 */
        val readable: Boolean = true,
    )

    /** 一轮判定的结论。**不是布尔** —— 「没挑到」必须带一句人话原因,见 [None.why]。 */
    sealed class Pick {
        /**
         * 挑中了 [name]。
         *
         * ★ [alsoFound] 和 [broken] **必须报出来**:目录里躺两个 `.vrm` 时,
         * 用户以为换的是 B、实际用的是 A —— 这种错**不会以任何形式报错**,
         * 只会表现成「我换了它没变」。把「还有谁」写进日志是唯一能识破它的东西。
         */
        data class Found(
            val name: String,
            val alsoFound: List<String> = emptyList(),
            val broken: List<String> = emptyList(),
        ) : Pick()

        /** 一个能用的都没有。[why] 是人话,直接进日志。 */
        data class None(val why: String) : Pick()
    }

    /** 小写扩展名(不含点)。`a.VRM` 和 `a.vrm` 要认成同一种。 */
    fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * 这个文件名允不允许从外部目录 serve。
     *
     * ★ 另挡两条,和扩展名无关:
     * * **点开头的**:Android 自己会往目录里放 `.nomedia` 这类文件;它们不是用户丢的。
     * * **含路径分隔符的**:`a/../b.png` 这种要当场拒掉 —— 判定层拒掉,
     *   比指望下面那层拼接不出错可靠(不给自己开一条「随便读」的口子)。
     */
    fun allowedExternal(name: String): Boolean {
        if (name.isEmpty() || name.startsWith(".")) return false
        if (name.contains('/') || name.contains('\\')) return false
        return extOf(name) in ALLOWED_EXTS
    }

    /**
     * 一个目录里该用哪个文件。
     *
     * 判据按这个顺序,**每一条都对应一种「我换了它没变」的真实长相**:
     *
     * 1. **按扩展名筛**(认 [exts],大小写不敏感)—— 用户会丢进来 `新人物.vrm`,
     *    名字我们猜不到,所以**只认扩展名,不认文件名**。这正是「放文件就生效」;
     * 2. **点开头的不算** —— 那是 Android 自己放进去的,不是用户丢的;
     * 3. **读不到 / 0 字节的挑出去**,但**记在 [Pick.Found.broken] 里** ——
     *    坏文件不能静默顶掉一个好文件,也不能静默变空白;
     * 4. 剩下的**按名字排序取第一个**(确定性:同样的目录永远得同一个结论,
     *    否则「重启一次换一个人」这种事会变成玄学);
     * 5. 同目录里**还有别的** → 记进 [Pick.Found.alsoFound],让日志说出来。
     */
    fun pick(candidates: List<Candidate>, exts: List<String>): Pick {
        val want = exts.map { it.lowercase() }.toSet()
        val byExt = candidates
            .filter { !it.name.startsWith(".") && extOf(it.name) in want }

        if (byExt.isEmpty()) {
            return Pick.None("目录里没有 ${exts.joinToString(" / ") { ".$it" }} 文件")
        }

        val usable = byExt.filter { it.readable && it.bytes > 0L }.sortedBy { it.name }
        val broken = byExt.filter { !(it.readable && it.bytes > 0L) }
            .sortedBy { it.name }
            .map { "${it.name}(${if (!it.readable) "读不到" else "0 字节"})" }

        if (usable.isEmpty()) {
            // ★ 这一条是「明明放对了却说她没换」的头号长相:文件在、名字对、**是坏的**。
            //   不说出来的话,它和「你放错文件夹了」在屏幕上完全一样。
            return Pick.None(
                "找到了 ${byExt.size} 个,但一个都用不了:${broken.joinToString("、")}"
            )
        }

        return Pick.Found(
            name = usable.first().name,
            alsoFound = usable.drop(1).map { it.name },
            broken = broken,
        )
    }

    /**
     * 把一轮判定说成一句人话 —— **这行日志就是「我换了它怎么没变」的唯一分界线**。
     *
     * ★ 所以它**不在这里拼日志前缀**(那是 [Wardrobe] 的事),而是必须**把数量说全**:
     * 「用了 X」不够,「用了 X,另外还有 Y 没用」才是能查错的那句。
     */
    fun sentence(what: String, pick: Pick): String = when (pick) {
        is Pick.None -> "$what:没有可用的 —— ${pick.why}"
        is Pick.Found -> buildString {
            append("$what:用 ${pick.name}")
            if (pick.broken.isNotEmpty()) append(",跳过 ${pick.broken.joinToString("、")}")
            if (pick.alsoFound.isNotEmpty()) {
                append(",没用上 ${pick.alsoFound.joinToString("、")}(同目录只认排第一的那个)")
            }
        }
    }
}
