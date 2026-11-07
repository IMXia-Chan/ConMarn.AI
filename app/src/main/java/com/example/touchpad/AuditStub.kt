package com.example.touchpad

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **公开源码中的占位实现（不包含动作分级与本地审计）。**
 *
 * 完整的动作分级表与审计台账实现不随公开仓库发布，理由见 README
 * 「公开源码不包含什么」一节。为了让源码保持可编译、界面可运行，
 * 这里只保留调用方需要的 API 外壳：不判级、不落盘、不开任何文件。
 */
enum class RiskLevel(val label: String) {
    GREEN("绿"),
    YELLOW("黄"),
    RED("红"),
}

/** 台账里的一条。公开版恒为空列表，这些字段只为界面编译而保留。 */
data class AuditEntry(
    val at: Long,
    val level: RiskLevel,
    val hand: String,
    val tool: String,
    val args: String,
    val ok: Boolean,
    val outcome: String,
)

object Audit {

    fun init(ctx: Context) {}

    fun record(hand: Hand, tool: String, args: JSONObject, result: JSONObject, task: String = "") {}

    fun count(): Int = 0

    fun recent(n: Int = 100): List<AuditEntry> = emptyList()

    fun clear(): Boolean = true

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun timeText(at: Long): String = fmt.format(Date(at))
}
