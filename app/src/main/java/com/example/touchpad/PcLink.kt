package com.example.touchpad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 「把电脑那条线接上」—— **从任何一个入口发起,只有这一条路**。
 *
 * ## 为什么要有这个文件
 *
 * 2026-10-05 用户报:「**明明是连着电脑,agent 说没有**」。
 *
 * 查下来的账是这样的:自动连上次那台电脑那段代码(`autoConnectLast`)是
 * [MainActivity] 的一个**私有方法**,只在 `MainActivity.onCreate` 里、且只在
 * `savedInstanceState == null` 时调一次。而 2026-10-04 身份合并之后,
 * **她的房间([ConMarnActivity])成了桌面唯一的入口** —— 它从来不建 MainActivity。
 *
 * 于是那条路上:**一次连接都不会发起**。她的脑子在手机上跑得好好的,她会答话、
 * 会聊天,只有一件做不了 —— 电脑上的活。而那在她嘴里是一句
 * 「电脑没连上,我先只陪你聊天」,听起来像「电脑那边出问题了」,
 * 实际是**手机这边压根没试过**。
 *
 * ★ 和 [HerBoot] 是同一条教训,只是换了个器官:
 *   **凡是「她还在不在」「线通不通」这种进程级的事实,都不该挂在某一个
 *   Activity 的寿命上。** 挂在上面的时候它不会报错,只会静默地少一块能力。
 *
 * ## 为什么带限流
 *
 * [TouchpadClient.connect] 的**第一句就是 `disconnect()`** —— 它假定调用方
 * 已经想清楚了。而现在有两个入口会各自动连一次(房间、控制屏),
 * 一前一后隔几秒就能把它们打成「连上 → 掐掉 → 再连」的抖动,
 * 表现是「一会儿有一会儿没有」,而且**每次抖动都要重新走一遍配对/续连**。
 *
 * 所以这里拿一个时间闸挡住:**短时间内只发起一次**。它不是优化,是正确性 ——
 * 少了它,「从房间点进电脑页」这种最普通的操作就会自己把自己踢下线。
 * ⚠️ 闸门是按**发起时刻**算的,不是按结果 —— 连不上时的重试由用户再点一次,
 *    比自动重试风暴好(`ruoxi-lan-discovery-unicast` 那轮吃过广播风暴的亏)。
 */
object PcLink {

    /**
     * Android 16(API 36)起访问局域网要的运行时权限。
     *
     * ★ 回调码**不能和别的 Activity 撞** —— [ConMarnActivity] 已经占了
     *   104/105/106([REQ_WAKE]/[REQ_NOTIF]/[REQ_EAR]),这里取 107。
     */
    const val REQ_LOCAL_NET = 107

    /** 存「上次那台电脑」的口径 —— 和 [MainActivity] 用的是同一个文件、同一个键。 */
    private const val PREFS = "touchpad"
    private const val KEY_IP = "ip"
    private const val DEFAULT_PORT = 9527

    /** 两次自动连接之间至少隔这么久(毫秒)。见文件头「为什么带限流」。 */
    private const val MIN_GAP_MS = 6_000L

    @Volatile
    private var lastAttemptAtMs = 0L

    /** 存下来的那台电脑。没存过、或存坏了都返回 null。 */
    fun lastAddr(ctx: Context): Pair<String, Int>? {
        val stored = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_IP, null)?.trim()
        } catch (_: Exception) {
            null
        } ?: return null
        if (stored.isEmpty()) return null
        // 端口是可选的:`connectTo` 只在**非默认端口**时才写成 `ip:port`。
        val idx = stored.lastIndexOf(':')
        return if (idx > 0) {
            val port = stored.substring(idx + 1).toIntOrNull() ?: DEFAULT_PORT
            stored.substring(0, idx) to port
        } else {
            stored to DEFAULT_PORT
        }
    }

    /** 还缺「本地网络」权限吗。API < 36 恒为 false(那时没有这个权限)。 */
    fun needsLocalNetPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return try {
            ctx.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 连上次那台电脑。
     *
     * @return true = **已经发起**了这次连接;false = 什么都没做(已经连着 /
     *         没存过地址 / 缺权限 / 6 秒内刚试过)。
     *
     * ★ 返回 false 的四种原因**故意不区分**给调用方 —— 调用方要做的只有一件事:
     *   回去继续画界面。真正需要看原因的场合(缺权限)由
     *   [needsLocalNetPermission] 单独问,那是**要不要弹授权框**的判据,不是连接结果。
     */
    fun connectLast(ctx: Context): Boolean {
        val client = try { TouchpadClient.get(ctx) } catch (_: Exception) { return false }
        if (client.isConnected()) return false
        if (needsLocalNetPermission(ctx)) return false

        val now = System.currentTimeMillis()
        if (now - lastAttemptAtMs < MIN_GAP_MS) return false
        val (ip, port) = lastAddr(ctx) ?: return false
        lastAttemptAtMs = now

        // ★ 和 [MainActivity.connectTo] 一样,把地址回写一次 —— 这样
        //   「上次那台」永远是**最近真的试过**的那台,而不是别的什么。
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_IP, if (port == DEFAULT_PORT) ip else "$ip:$port")
                .apply()
        } catch (_: Exception) {
        }

        return try {
            client.connect(ip, port)
            true
        } catch (_: Exception) {
            false
        }
    }
}
