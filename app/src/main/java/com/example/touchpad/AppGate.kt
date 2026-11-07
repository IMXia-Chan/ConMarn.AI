package com.example.touchpad

import android.app.Activity
import android.content.Context

/**
 * **公开源码中的占位实现（不包含指纹锁）。**
 *
 * 完整的应用锁与危险动作二次验证实现不随公开仓库发布，理由见 README
 * 「公开源码不包含什么」一节。为了让源码保持可编译、App 可运行，
 * 这里只保留与调用方一致的 API 外壳：
 *
 * - 关不掉的「开」是假的安全感，所以这里 [isOn] 恒为 false、[available] 恒为 false；
 * - [ask] / [askBlocking] 直接放行，不弹任何系统验证框；
 * - 真机发布版由完整源码构建，包含真正的锁。
 */
object AppGate {

    fun init(ctx: Context) {}

    fun isOn(ctx: Context): Boolean = false

    fun setOn(ctx: Context, on: Boolean) {}

    fun available(ctx: Context): Boolean = false

    fun needsUnlock(ctx: Context): Boolean = false

    fun needsHard(ctx: Context): Boolean = false

    fun markUnlocked() {}

    fun ask(
        activity: Activity,
        title: String,
        why: String,
        onPass: () -> Unit,
        onFail: () -> Unit,
    ) {
        onPass()
    }

    fun askBlocking(activity: Activity, title: String, why: String, seconds: Long = 60): Boolean = true
}
