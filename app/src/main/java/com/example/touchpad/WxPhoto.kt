package com.example.touchpad

import android.content.Context
import java.io.File

/**
 * 窗外换天 —— 按天气挑一张（或一段）**整张的背景**。
 *
 * ## 怎么定的
 *
 * 他 2026-11-07 连着改了三次，最后落在这样一句上：
 * 「背景还是用我现在这个，因为现在这个好看」「实在不行我再生成图片给你，然后你自己换就行，
 * 甚至生成视频都行」。所以：
 *
 * - **平时永远是 `room.json` 里那张**（`room2c.jpg`），一个字都不动；
 * - 只有当 `room/` 里躺着一张对得上的天景图/视频时，才**临时整张换掉**；
 * - 找不到就退回那张，绝不留在上一场雨的画面上。
 *
 * ★ 不是「在照片上叠一层雨」。他要的不是贴膜：窗框、窗帘、屋里那盏灯都要原样，
 *   所以换的是**整张**，而不是往上面盖。
 *
 * ## 命名
 *
 * 文件放进 `room/`（和 `room2c.jpg` 同一个文件夹），按这个顺序找，先命中先用：
 *
 * ```
 * wx-<天光>-<天气>      wx-night-rain.mp4    ← 最准
 * wx-<天气>             wx-rain.mp4          ← 不分白天黑夜
 * wx-<天光>             wx-night.mp4         ← 只分白天黑夜
 * ```
 *
 * 天光三档 `day` / `dusk` / `night`（按真实太阳高度角算，见 [SunMath]）；
 * 天气七种 `clear` / `partly` / `overcast` / `fog` / `rain` / `snow` / `thunder`。
 * 扩展名 `jpg` / `jpeg` / `png` / `webp`（图）或 `mp4` / `webm`（视频）。
 *
 * ★ **可以只放两三张**，不用凑齐 21 张。没放的那档就还是他现在这张。
 */
internal object WxPhoto {

    /** 和 `setBackdropImage` / `setBackdropVideo` 的白名单一致，也对得上 serve 那张 MIME 表。 */
    private val EXTS = listOf("jpg", "jpeg", "png", "webp", "mp4", "webm")

    /**
     * **哪些天气才值得整张换掉。**
     *
     * ★ 2026-11-07 他连着改了两回口：先是「按天气换整套照片」，看过那张乌云夜景之后
     *   收窄成「只在明显变天时换」，紧接着又补了一句「晴天和阴天还是得区分一下」。
     *
     * 所以现在的界线是：**晴 / 多云不换，其余都换**。
     * - 晴、多云 → 他自己配置的那张（`room2c.jpg`）。那张本来就好看，白天黑夜都由它兜着。
     * - 阴、雨、雪、雷、雾 → 换成对应的天景图。
     *
     * 晴和多云合成一档（都不换）是有意的：多云和晴在画上的差别小到不值得换整张照片，
     * 而**晴和阴**是抬头一眼就看得出来的两种天，必须分开。
     *
     * 认不出来的（空串、将来多出来的代号）一律**不换** —— 宁可不动。
     */
    fun swaps(kind: String): Boolean = when (kind) {
        SceneWeather.OVERCAST, SceneWeather.RAIN, SceneWeather.SNOW,
        SceneWeather.THUNDER, SceneWeather.FOG -> true
        else -> false
    }

    /**
     * 挑一张最贴切的天景，返回**文件名**（不是路径）。没有就返回 null。
     *
     * 只在 `room/` 里找 —— `person/` 是她的贴图目录，天景放那儿没人找得到。
     * 空文件当没放：传了一半的图进去只会得到一块黑。
     */
    fun pick(ctx: Context, phase: String, kind: String): String? {
        // ★ 先问一句「这种天气值不值得换」([swaps])。不值得就当场返回 null,
        //   连目录都不去碰 —— 晴天的每一拍都去列一遍 room/ 是白费。
        if (!swaps(kind)) return null

        val dir = try {
            Wardrobe.roomDir(ctx)
        } catch (_: Exception) {
            null
        } ?: return null

        val order = listOf("wx-$phase-$kind", "wx-$kind", "wx-$phase")
        for (base in order) {
            for (ext in EXTS) {
                val f = try {
                    File(dir, "$base.$ext")
                } catch (_: Exception) {
                    null
                } ?: continue
                if (f.isFile && f.length() > 0L) return f.name
            }
        }
        return null
    }
}
