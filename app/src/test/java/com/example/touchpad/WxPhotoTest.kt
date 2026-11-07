package com.example.touchpad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗外换天的**门槛**：什么天气才值得把整张背景换掉。
 *
 * 他 2026-11-07 连着改了两回口：先要「按天气换整套照片」，收窄成「只在明显变天时换」，
 * 又补一句「晴天和阴天还是得区分一下」。这里钉的就是最后那条线：
 * **阴、雨、雪、雷、雾换；晴、多云不换**（用他自己配置的那张）。
 *
 * 这一条判错的症状是「外面没下雨，屋里换了张照片」—— 不报错、不崩，
 * 只是他某天晚上抬头一看会问「背景咋改了？」。所以它值得被钉住。
 */
class WxPhotoTest {

    @Test
    fun 阴雨雪雷雾要换() {
        assertTrue(WxPhoto.swaps(SceneWeather.OVERCAST))
        assertTrue(WxPhoto.swaps(SceneWeather.RAIN))
        assertTrue(WxPhoto.swaps(SceneWeather.SNOW))
        assertTrue(WxPhoto.swaps(SceneWeather.THUNDER))
        assertTrue(WxPhoto.swaps(SceneWeather.FOG))
    }

    @Test
    fun 晴天多云不换() {
        // 晴和多云合成一档：都是「外面亮着」，都留他自己那张。
        assertFalse(WxPhoto.swaps(SceneWeather.CLEAR))
        assertFalse(WxPhoto.swaps(SceneWeather.PARTLY))
    }

    @Test
    fun 认不出来就不换() {
        // 空串：天气没取到的时候可能会走到这儿。
        assertFalse(WxPhoto.swaps(""))
        // 大小写不一样也不认 —— 代号全是代码里自己拼的，不会有大写。
        assertFalse(WxPhoto.swaps("Rain"))
        // 将来多出来的代号：宁可不动。
        assertFalse(WxPhoto.swaps("hail"))
    }
}
