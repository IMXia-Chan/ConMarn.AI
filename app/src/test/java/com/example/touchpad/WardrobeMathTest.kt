package com.example.touchpad

import com.example.touchpad.WardrobeMath.Candidate
import com.example.touchpad.WardrobeMath.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换装判定的钉子。
 *
 * ★ 为什么这组测试**必须**存在(2026-10-05 下午):
 *
 * 这一轮里**手机是拔着的** —— 换装那条路在真机上一格都验不了。所以「判定对不对」
 * 只能在这儿咬住。**没被这几条钉住的换装行为,一律等于没做。**
 *
 * 而这些用例挑的**不是**边界条件的趣味,是**每一种「我换了它没变」的真实长相**:
 *
 * | 用户干了什么 | 屏幕上看到什么 | 该说什么 |
 * |---|---|---|
 * | 放了个 0 字节的坏文件 | 没变 | 「找到了 1 个,但一个都用不了:xx.vrm(0 字节)」|
 * | 放了两个,以为用的是 B | 用的是 A | 「用 A,没用上 B」|
 * | 放了个 `.html` | (绝不能生效) | 白名单挡掉 **且不留任何日志以外的痕迹** |
 * | 文件名是中文 / 大写扩展名 | 没变 | 照样认得出来 |
 */
class WardrobeMathTest {

    private fun c(name: String, bytes: Long = 1024L, readable: Boolean = true) =
        Candidate(name, bytes, readable)

    // ---------------------------------------------------------------- 扩展名

    @Test
    fun `扩展名 大小写和点点都要认对`() {
        assertEquals("vrm", WardrobeMath.extOf("a.vrm"))
        assertEquals("vrm", WardrobeMath.extOf("A.VRM"))
        assertEquals("gltf", WardrobeMath.extOf("房间.gltf"))
        // 没有点、点在开头(隐藏文件)、点在末尾 —— 三种都算「没有扩展名」
        assertEquals("", WardrobeMath.extOf("README"))
        assertEquals("", WardrobeMath.extOf(".nomedia"))
        assertEquals("", WardrobeMath.extOf("a."))
        // 多个点取最后一个
        assertEquals("vrm", WardrobeMath.extOf("my.model.v2.vrm"))
    }

    // ---------------------------------------------------------------- 白名单

    /**
     * ★★ 这一组是**安全**判据,不是功能判据。别删。
     *
     * 外部目录的文件会被 serve 进 `https://appassets.androidplatform.net/`,
     * 而 `HerBridge` 就挂在那个源上。**一个 `.js` 掉进去 = 在自己的源里执行任意脚本。**
     */
    @Test
    fun `数据文件放行`() {
        for (n in listOf("a.vrm", "room.glb", "room.gltf", "buf.bin",
                         "floor.png", "wall.JPG", "x.jpeg", "y.webp", "room.json")) {
            assertTrue("$n 该放行", WardrobeMath.allowedExternal(n))
        }
    }

    @Test
    fun `可执行的那几种一律不放行`() {
        // 这四种掉进外部目录,就等于在这个 App 自己的源里注入了代码。
        for (n in listOf("evil.html", "evil.js", "evil.css", "evil.svg",
                         "EVIL.HTML", "evil.mjs", "evil.htm")) {
            assertFalse("★ $n 绝不能放行 —— 那是往自己的源里放脚本", WardrobeMath.allowedExternal(n))
        }
    }

    @Test
    fun `认不出来的扩展名不放行`() {
        // 白名单不是黑名单:没列出来的一律当「不存在」,连读都不读。
        for (n in listOf("a.apk", "a.dex", "a.sh", "a.exe", "a", "a.", "")) {
            assertFalse("$n 该拦下", WardrobeMath.allowedExternal(n))
        }
    }

    @Test
    fun `隐藏文件和不带路径的名字要挡掉`() {
        assertFalse(WardrobeMath.allowedExternal(".nomedia"))
        assertFalse(WardrobeMath.allowedExternal(".hidden.vrm"))
        // 带路径分隔符的当场拒 —— 判定层拒掉,比指望下面那层拼接不出错可靠
        assertFalse(WardrobeMath.allowedExternal("a/../b.png"))
        assertFalse(WardrobeMath.allowedExternal("..\\b.png"))
    }

    // ---------------------------------------------------------------- pick

    @Test
    fun `空目录说没有`() {
        val p = WardrobeMath.pick(emptyList(), WardrobeMath.MODEL_EXTS)
        assertTrue(p is Pick.None)
        assertTrue((p as Pick.None).why.contains(".vrm"))
    }

    @Test
    fun `只认扩展名 不认文件名`() {
        // 「放文件就生效」的全部意思:他丢进来的一定叫「新人物.vrm」这种名字,我们猜不到。
        val p = WardrobeMath.pick(
            listOf(c("README.txt"), c("他新作.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        assertEquals(Pick.Found("他新作.vrm"), p)
    }

    @Test
    fun `大小写不同的扩展名照样认`() {
        val p = WardrobeMath.pick(listOf(c("NEW.VRM")), WardrobeMath.MODEL_EXTS)
        assertEquals(Pick.Found("NEW.VRM"), p)
    }

    @Test
    fun `放过一个不相关的文件不影响判定`() {
        val p = WardrobeMath.pick(
            listOf(c(".nomedia", 0), c("thumb.png"), c("ok.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        assertEquals(Pick.Found("ok.vrm"), p)
    }

    @Test
    fun `放两个要用排第一那个 并且把没用上的说出来`() {
        // ★ 这条治的是:**用户以为换的是 B,实际用的是 A**,而且它不报错。
        val p = WardrobeMath.pick(
            listOf(c("b.vrm"), c("a.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        assertEquals(Pick.Found("a.vrm", alsoFound = listOf("b.vrm")), p)
    }

    @Test
    fun `坏文件不许顶掉好文件 但要说出来`() {
        val p = WardrobeMath.pick(
            listOf(c("aa_broken.vrm", 0), c("zz_good.vrm")),
            WardrobeMath.MODEL_EXTS,
        )
        // 排第一的是坏的那个 —— 它必须被跳过,而不是让房间变空白
        assertEquals(Pick.Found("zz_good.vrm", broken = listOf("aa_broken.vrm(0 字节)")), p)
    }

    @Test
    fun `读不到和零字节要分开说`() {
        // 「不在」和「在但读不到」是两件事 —— SherpaVoice.filesPresent 就是在这上面撒过谎
        val p = WardrobeMath.pick(
            listOf(c("a.vrm", 100, readable = false), c("b.vrm", 0)),
            WardrobeMath.MODEL_EXTS,
        )
        assertTrue(p is Pick.None)
        val why = (p as Pick.None).why
        assertTrue("要说读不到:$why", why.contains("读不到"))
        assertTrue("要说 0 字节:$why", why.contains("0 字节"))
    }

    @Test
    fun `一个都用不了时要说清是哪个 而不是说没有`() {
        // ★ 这条是「明明放对了却说她没换」的头号长相。
        //   不说出来的话,它和「你放错文件夹了」在屏幕上完全一样。
        val p = WardrobeMath.pick(listOf(c("人物.vrm", 0)), WardrobeMath.MODEL_EXTS)
        assertTrue(p is Pick.None)
        val why = (p as Pick.None).why
        assertTrue("要带上文件名:$why", why.contains("人物.vrm"))
        assertTrue("不许只说「没有」:$why", !why.contains("目录里没有"))
    }

    @Test
    fun `排序要确定 同样的目录永远同一个结论`() {
        // 否则「重启一次换一个人」这种事会变成玄学
        val files = listOf(c("c.vrm"), c("a.vrm"), c("b.vrm"))
        val first = WardrobeMath.pick(files, WardrobeMath.MODEL_EXTS)
        repeat(5) {
            assertEquals(first, WardrobeMath.pick(files.shuffled(), WardrobeMath.MODEL_EXTS))
        }
    }

    @Test
    fun `多了扩展名时按给的顺序一起认`() {
        val p = WardrobeMath.pick(
            listOf(c("note.txt"), c("scene.gltf")),
            WardrobeMath.SCENERY_EXTS,
        )
        assertEquals(Pick.Found("scene.gltf"), p)
    }

    // ---------------------------------------------------------------- 日志那句话

    @Test
    fun `日志要把用谁 跳过了谁 没用上谁 全说出来`() {
        // ★ 这行日志是「我换了它怎么没变」的唯一分界线。它必须说得全。
        val got = WardrobeMath.sentence(
            "人物",
            Pick.Found("a.vrm", alsoFound = listOf("b.vrm"), broken = listOf("c.vrm(0 字节)")),
        )
        assertTrue(got, got.contains("用 a.vrm"))
        assertTrue(got, got.contains("跳过 c.vrm(0 字节)"))
        assertTrue(got, got.contains("没用上 b.vrm"))
    }

    @Test
    fun `没有可用的时日志要带上原因`() {
        val got = WardrobeMath.sentence("房间模型", Pick.None("目录里没有 .glb / .gltf 文件"))
        assertTrue(got, got.contains("房间模型"))
        assertTrue(got, got.contains("目录里没有"))
    }
}
