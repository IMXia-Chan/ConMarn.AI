package com.example.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 她的长期记忆的纯规则测试 —— 不碰 Android / org.json。
 *
 * 为什么值得钉:这层写错的表现**不是崩溃**,是「她突然一本正经地管他叫别的」
 * 或者「把一句普通话当成名字记了三年」。真机上要等好几天才看得出来,
 * 而且那时候错的记忆已经落盘了 —— 只能靠这里拦住。
 */
class UserLexiconTest {

    // ------------------------------------------------------------------
    // looksLikeName:整个抽取里最要紧的一道闸
    // ------------------------------------------------------------------

    @Test
    fun `像名字的放行`() {
        listOf("小明", "老王", "阿May", "老板", "哥").forEach {
            assertTrue("「$it」该被当成名字", LexiconMath.looksLikeName(it))
        }
    }

    @Test
    fun `不像名字的拦住`() {
        // 「我叫你别乱动」「别叫我」「你是谁」—— 这些含「我叫 / 叫我」但不是名字
        listOf("你别乱动", "你", "我", "不要", "没空", "吗", "了吧", "").forEach {
            assertFalse("「$it」不该被当成名字", LexiconMath.looksLikeName(it))
        }
    }

    // ------------------------------------------------------------------
    // extract:他说了什么关于自己的事
    // ------------------------------------------------------------------

    @Test
    fun `以后叫我X`() {
        val f = LexiconMath.extract("以后叫我小明")
        assertEquals(listOf("小明"), f.call)
        assertTrue(f.avoid.isEmpty())
    }

    @Test
    fun `你可以叫我X_也可以是我叫X`() {
        assertEquals(listOf("老王"), LexiconMath.extract("你可以叫我老王").call)
        assertEquals(listOf("阿May"), LexiconMath.extract("我叫阿May").call)
    }

    @Test
    fun `别叫我X_是禁止不是称呼`() {
        val f = LexiconMath.extract("别叫我老板")
        assertEquals(listOf("老板"), f.avoid)
        assertTrue("禁止的叫法不能再被当成允许的", f.call.isEmpty())
    }

    @Test
    fun `句中的叫我_不是他在给自己起名`() {
        // ★ 这条是真机上最贵的一种错:漏了「你可以叫我老王」只是少记一条,
        //   收了「我妈叫我吃饭」他会从此被她叫「吃饭」。两个方向都得钉住。
        assertTrue("「我妈叫我吃饭」里的「叫我」是别人叫他", LexiconMath.extract("我妈叫我吃饭").call.isEmpty())
        assertTrue(LexiconMath.extract("他叫我做事").call.isEmpty())
        assertTrue(LexiconMath.extract("老板叫我加班").call.isEmpty())
    }

    @Test
    fun `就叫我X_也算`() {
        assertEquals(listOf("老王"), LexiconMath.extract("就叫我老王").call)
    }

    @Test
    fun `句中的我叫不算自我介绍`() {
        // 「我叫你别乱动」——「我叫」在句子中间,后面跟的是「你别乱动」,不是名字
        val f = LexiconMath.extract("我叫你别乱动")
        assertTrue("句中「我叫」不该被当自我介绍", f.call.isEmpty())
    }

    @Test
    fun `我是学生这类抽不出来_这是有意的`() {
        // 宁可不记得,也不能让她一本正经管他叫「学生」——见 UserLexicon 的注释
        val f = LexiconMath.extract("我是个学生,平时写代码")
        assertTrue(f.call.isEmpty())
        assertTrue(f.avoid.isEmpty())
    }

    @Test
    fun `不是A是B_既是称呼纠正也是同音字`() {
        val f = LexiconMath.extract("不是威信,是微信")
        assertEquals("微信", f.fixes["威信"])
    }

    @Test
    fun `纠正里的好词得不含禁词才算数`() {
        // good 侧本身不合格(null/空/含「你」)就不学 —— 学坏一条比少学一条糟
        val f = LexiconMath.extract("不是微信,是你")
        assertTrue("「你」不是个词,不该学进去", f.fixes.isEmpty())
    }

    @Test
    fun `自己纠正自己不算`() {
        assertTrue(LexiconMath.extract("不是微信,是微信").fixes.isEmpty())
    }

    @Test
    fun `一句话能同时抽三样`() {
        val f = LexiconMath.extract("以后叫我明哥,不是威信,是微信")
        assertEquals(listOf("明哥"), f.call)
        assertEquals("微信", f.fixes["威信"])
    }

    // ------------------------------------------------------------------
    // correct:同音字替换
    // ------------------------------------------------------------------

    @Test
    fun `同音字换掉`() {
        val out = LexiconMath.correct("打开威信给老王发条消息", mapOf("威信" to "微信"))
        assertEquals("打开微信给老王发条消息", out)
    }

    @Test
    fun `没有映射就原样返回`() {
        assertEquals("随便一句话", LexiconMath.correct("随便一句话", emptyMap()))
    }

    @Test
    fun `不做链式传播`() {
        // A→B,B→C 同时存在时,结果只能是 B —— 「A 变成 C」是他从没纠正过的事
        val out = LexiconMath.correct("A", linkedMapOf("A" to "B", "B" to "C"))
        assertEquals("B", out)
    }

    @Test
    fun `长词优先`() {
        // 「微信」不该先把「微信号」切走半截
        val out = LexiconMath.correct("加个微信号", linkedMapOf("微信" to "威信", "微信号" to "微信"))
        assertEquals("加个微信", out)
    }

    // ------------------------------------------------------------------
    // block:拼给模型看的那块
    // ------------------------------------------------------------------

    @Test
    fun `什么都没有就不出块`() {
        assertNull(LexiconMath.block(emptyList(), emptyList(), emptyMap(), emptyMap()))
    }

    @Test
    fun `块里带上称呼和禁用`() {
        val b = LexiconMath.block(listOf("小明"), listOf("老板"), emptyMap(), emptyMap())!!
        assertTrue(b.contains("小明"))
        assertTrue(b.contains("老板"))
        // 必须说清这不是他这一轮说的 —— 否则模型会当成新消息去回应
        assertTrue(b.contains("以前攒下来的"))
    }

    @Test
    fun `常用应用按次数排_只报前几个`() {
        val apps = mapOf("微信" to 9, "Edge" to 3, "记事本" to 1, "计算器" to 8, "画图" to 2, "设置" to 1)
        val b = LexiconMath.block(emptyList(), emptyList(), apps, emptyMap())!!
        assertTrue(b.indexOf("微信") < b.indexOf("计算器"))
        assertTrue("只报前 5 个,最次的「设置」不该出现", !b.contains("设置"))
    }
}
