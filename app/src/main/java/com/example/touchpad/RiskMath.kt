package com.example.touchpad

/**
 * **公开源码中的占位实现（不包含风险判定）。**
 *
 * 完整的风险判据、关键词名单与对应自测不随公开仓库发布，理由见 README
 * 「公开源码不包含什么」一节。为了让调用方不用改、源码保持可编译，
 * 这里只保留同名的入口与枚举：
 *
 * - [taskTier] 恒为 [Tier.NORMAL]、[matchedWord] 恒为 null —— 公开版不做
 *   「这一轮是不是高危」的判定；
 * - [needsTap] 恒为 false —— 高危档下也不会额外弹确认框；
 * - [tapNext] 仍按跨端回执协议判：电脑回 `needs_confirm` 才弹框，问过一遍就不再问。
 *   这一段是协议状态机，不含任何风险名单。
 *
 * 真机发布版由完整源码构建，包含真正的判定。
 */
internal object RiskMath {

    /** 这一轮的风险档。公开版恒为 [Tier.NORMAL]。 */
    enum class Tier { NORMAL, HIGH }

    /**
     * 电脑回执里「这一步得人来点」的标记。
     *
     * ★ 它是一份**跨端契约**：`pc-server` 回的就是这个字面量。
     *   两边拼不成一模一样的字，这道闸就**永远不响**，而且不报错。
     */
    internal const val NEEDS_CONFIRM = "needs_confirm"

    /** 这一句用户指令属于哪一档。公开版恒回 [Tier.NORMAL]。 */
    internal fun taskTier(userText: String): Tier = Tier.NORMAL

    /** 命中的是哪个词。公开版恒回 null（不匹配任何名单）。 */
    internal fun matchedWord(userText: String): String? = null

    /** 这个动作在高危档下要不要他点一下。公开版恒回 false。 */
    internal fun needsTap(tier: Tier, tool: String): Boolean = false

    /** 他要点的那一下，标题上写什么。 */
    internal fun tapTitle(tool: String): String = "确认"

    /** 为什么这一轮一路都要点头。公开版不会走到这里。 */
    internal fun whyHighTier(word: String): String =
        "这一轮里她每动一次鼠标键盘，我都会先问你一下"

    /** 拿到一条电脑回执之后，手机侧该做什么。 */
    enum class Tap {
        /** 电脑没提这回事 —— 原样交回去。 */
        PASS,

        /** 电脑说这一步要人点一下，而**还没问过** → 弹框；他点头就带着凭证重发一次。 */
        ASK_THEN_RETRY,

        /** 问过了、他也点了头，电脑还是说做不了 → **不许再问**，把原话交回去。 */
        GIVE_UP,
    }

    /**
     * @param blockedBy 电脑回执里的 `blocked_by`，没有就传空串。
     * @param confirmedSent 这一次动作**已经带着「他点过头」的凭证发过一轮了吗**。
     */
    internal fun tapNext(blockedBy: String, confirmedSent: Boolean): Tap = when {
        blockedBy != NEEDS_CONFIRM -> Tap.PASS
        confirmedSent -> Tap.GIVE_UP
        else -> Tap.ASK_THEN_RETRY
    }
}
