package com.example.touchpad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONObject
import java.net.URLEncoder

/**
 * **第二只手:这部手机自己。**
 *
 * 在这之前她只有一只手 —— 电脑那只。所以「设个闹钟」这种话她办不了,
 * 而闹钟跟电脑一点关系都没有。这只手补的就是这一类:**长在她自己身上的事。**
 *
 * ---
 *
 * ## 零新权限
 *
 * 这一整只手的每一个动作,用的都是 [AndroidManifest] 里**已经声明过的**能力。
 * 唯一加的是一个 `<queries>` 声明 —— 那不是权限,不给用户弹任何框,只是让
 * Android 11+ 允许我们**看见**已安装的应用(不声明的话 `getLaunchIntentForPackage`
 * 对每个包都静默返回 null,表现是「她没有装任何应用」)。
 *
 * ## ★ 诚实边界(写在这里,也写进回执)
 *
 *   - **她点不了手机自己的屏幕。** 那要 AccessibilityService,是另一轮的事。
 *     所以这里没有「点手机上的按钮」这种工具 —— 不是忘了,是**没有就是没有**。
 *   - **闹钟和计时器是直接设上的,不弹界面** —— 两个都带 `AlarmClock.EXTRA_SKIP_UI`。
 *     所以回执里那句「闹钟设好了」是**真话**,不是「请系统打开了」。★ 这里以前写反过:
 *     描述里说「要用户按确认才算设上」,而代码是跳过界面的 —— 那会让模型不敢说「设好了」,
 *     反过来让用户**去找一个根本不会出现的确认框**。
 *   - **拨号盘和「打开设置」会弹到系统界面上。** 那是系统自己的窗口,不是她的。
 *     回执里说「已经请系统打开了」而不是「办好了」—— 用户没按那一下就不算办完。
 *   - 后台拉 Activity 靠 `SYSTEM_ALERT_WINDOW` 豁免。**没开那个权限时系统会静默不弹**
 *     (不报错、不抛异常,就是没动静)。所以 [visible] 这个标志很重要:她就在屏幕上时
 *     我们敢说「打开吧」,她不在时得提前告诉用户「可能弹不出来」。
 *
 * ## 纯逻辑在 [SelfHandMath]
 *
 * 校验和字符串处理那些**会出错但不崩**的规则全在那边(纯函数、纯 JVM 可测)。
 * 这个文件只负责「真的去按系统那个按钮」。
 */
object SelfHand {

    const val ID = "self"
    const val NAME = "这部手机"
    const val VERSION = "1"

    /**
     * 她的声音。由 ConMarnActivity 在 TTS 就绪时挂上,离开时摘掉。
     *
     * ★ 用回调不用直接持有 TextToSpeech,是因为 TTS 在**她的房间**那个界面里,
     * 而那间房可能压根没开。没开的时候正确的话是「她现在不在房间里,说不了话」——
     * 不是「说不了话」这四个字的机械错误。**回执要说人话,而且要说实话。**
     */
    @Volatile
    var voice: ((String) -> Unit)? = null

    /** 她的房间现在在屏幕上吗。见类注释:后台拉 Activity 会被系统静默拦掉。 */
    @Volatile
    var visible: Boolean = false

    private var appContext: Context? = null

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    // ------------------------------------------------------------------
    // 工具表 —— 这就是「自述」的那一半
    // ------------------------------------------------------------------

    private fun p(name: String, desc: String) = HandParam(name, desc)

    private fun t(
        name: String, desc: String, params: List<HandParam>, required: List<String>,
    ) = HandTool(name, desc, params, required)

    val tools: List<HandTool> = listOf(
        // ★ 名字里必须带 `phone`。电脑那只手上也有个 `open_app` —— 叫同一个名字的话,
        // 用户的「打开微信」会在两条路之间随机走,而且**走错了不报错**(真打开了,
        // 只是在另一台机器上)。工具名重名是「猜错手」最便宜的一种预防。
        t(
            "open_phone_app", "打开**手机上**的应用(不是电脑上的)",
            listOf(p("name", "应用名,用手机桌面上看得见的名字,如「微信」「相机」")),
            listOf("name")
        ),
        t(
            "open_url", "用浏览器打开一个网址(只认 http/https)",
            listOf(p("url", "完整网址,如 https://www.bing.com")),
            listOf("url")
        ),
        t(
            "web_search", "上网搜一个词(会用浏览器打开搜索结果)",
            listOf(p("query", "要搜的词")),
            listOf("query")
        ),
        t(
            "set_alarm", "设一个闹钟。★ 直接设上,不弹界面(不用用户按确认)",
            listOf(
                p("hour", "几点,0~23 的整数,如 7"),
                p("minute", "几分,0~59 的整数,如 30"),
                p("label", "闹钟的名字,可不填,如「起床」"),
            ),
            listOf("hour", "minute")
        ),
        t(
            "set_timer", "设一个倒计时。★ 同样直接设上,不弹界面",
            listOf(
                p("seconds", "多少秒,如 300 表示 5 分钟"),
                p("label", "计时器的名字,可不填"),
            ),
            listOf("seconds")
        ),
        t(
            "open_settings", "打开系统设置的某一页",
            listOf(
                p(
                    "page",
                    "哪一页,填这些词之一:wifi、bluetooth、apps、battery、display、" +
                        "sound、storage、location、airplane、date、accessibility、" +
                        "developer。不确定就填 app"
                )
            ),
            listOf("page")
        ),
        t(
            "copy", "把一段文字放进手机剪贴板(方便用户粘到别处)",
            listOf(p("text", "要复制的文字")),
            listOf("text")
        ),
        t(
            "dial", "打开拨号盘并填好号码。★ **不会拨出去**,要用户自己按那一下",
            listOf(p("number", "号码,如 10086")),
            listOf("number")
        ),
        t(
            "vibrate", "震一下手机(她在口袋里碰你一下)",
            listOf(p("ms", "震多少毫秒,200 左右就够")),
            listOf("ms")
        ),
        t(
            "say", "用她的声音念一句话(她不在房间里时念不了)",
            listOf(p("text", "要念的话")),
            listOf("text")
        ),
    )

    /** 组装成一只手,进 [HandRegistry]。`fetchedAtMs` 用当前时间 —— 这是**代码里**的表,永远新鲜。 */
    fun hand(nowMs: Long): Hand = Hand(
        id = ID, kind = HandMath.KIND_SELF, name = NAME, version = VERSION,
        fetchedAtMs = nowMs, tools = tools,
        // ★ 一只都不藏。藏起来的工具在「自述」里不出现,而这里没有见不得光的原语。
        hidden = emptyList(),
        // 手机上这些动作互不抢东西(开个应用不禁用剪贴板),所以**声明成不占资源**。
        // 注意这和「没声明」是两回事:没声明 = 独占,那会让两件事白白排成一队。
        locks = tools.associate { it.name to emptyList<String>() },
    )

    fun has(tool: String): Boolean = tools.any { it.name == tool }

    // ------------------------------------------------------------------
    // 执行 —— 「诚实回答做成了没有」的那一半
    // ------------------------------------------------------------------

    /**
     * 跑一个工具。**永远返回一个带 `ok` 的对象,永远不抛。**
     *
     * 抛异常会把「这个工具做不到」和「程序坏了」混成同一种东西,而 [AiAgent] 那边
     * 对两者的处置完全不同(前者该告诉模型换个办法,后者该报故障)。
     *
     * @param a 参数。值可能是数字/布尔(模型不一定老老实实给字符串),所以一律走
     *          [str] 取,不直接 `optString` —— 那个碰到 JSON 数字会给你 "7.0"。
     */
    fun exec(tool: String, a: JSONObject): JSONObject {
        val ctx = appContext
            ?: return err("我还没准备好(手机这只手没初始化),这次没能执行。")
        return try {
            when (tool) {
                "open_phone_app" -> openApp(ctx, str(a, "name"))
                "open_url" -> openUrl(ctx, str(a, "url"))
                "web_search" -> {
                    val q = str(a, "query")
                    if (q.isBlank()) err("没说搜什么。")
                    else openUrl(ctx, SelfHandMath.searchUrl(q), what = "搜索")
                }
                "set_alarm" -> setAlarm(ctx, a)
                "set_timer" -> setTimer(ctx, a)
                "open_settings" -> openSettings(ctx, str(a, "page"))
                "copy" -> copy(ctx, str(a, "text"))
                "dial" -> dial(ctx, str(a, "number"))
                "vibrate" -> vibrate(ctx, str(a, "ms"))
                "say" -> say(str(a, "text"))
                else -> err("我这只手上没有「$tool」这个工具。")
            }
        } catch (e: Exception) {
            // 系统抛的那些(ActivityNotFoundException 之类)到这里收口。
            // ★ 带上类型名:同一个「打不开」,是系统里没这个东西还是权限被拦,
            // 处置完全不同,而用户看到的只是一句「没打开」—— 那不够查。
            err("${tool} 没做成(${typeName(e)}:${e.message ?: "没说原因"})。")
        }
    }

    // ---- 各个动作 ----

    private fun openApp(ctx: Context, name: String): JSONObject {
        if (name.isBlank()) return err("没说打开哪个应用。")
        val labels = labelToPackage(ctx)
        if (labels.isEmpty()) {
            // ★ 「一个应用都没有」和「系统不让我看」是两件事,但这里对用户是同一句人话:
            // 反正她读不到。分开说只会让用户去查一个查不出来的东西。
            return err("我读不到手机上的应用列表(系统没让我看装了什么)。")
        }
        val all = labels.keys.toList()
        val label = SelfHandMath.matchLabel(name, all)
            // 别称表:小写查一次再原样查一次(「WeChat」/「wechat」用户都可能说)
            ?: SelfHandMath.matchLabel(
                SelfHandMath.ALIASES[name.trim().lowercase()]
                    ?: SelfHandMath.ALIASES[name.trim()].orEmpty(),
                all,
            )
        val pkg = label?.let { labels[it] }
            ?: return err("手机上没找到叫「$name」的应用。" + nearMiss(name, all))
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?: return err("「$label」装了,但它没有可以打开的界面(可能是系统组件)。")
        return start(ctx, intent, ok = "已经打开「$label」了。", subject = "打开「$label」")
    }

    private fun openUrl(ctx: Context, url: String, what: String = "网址"): JSONObject {
        if (url.isBlank()) return err("没说$what。")
        val good = SelfHandMath.urlOf(url)
            ?: return err("「$url」不是一个 http/https 网址,我不开这种链接(只认 http 和 https)。")
        // ★ **不指定浏览器包名** —— 让系统按用户自己的默认浏览器打开。
        // 我们不替他选 App,也不塞任何 extra。
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(good))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(ctx, intent, ok = "已经在浏览器里打开了。", subject = "打开$what")
    }

    private fun setAlarm(ctx: Context, a: JSONObject): JSONObject {
        val h = str(a, "hour"); val m = str(a, "minute")
        SelfHandMath.clockError(h, m)?.let { return err(it) }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h.trim().toInt())
            .putExtra(AlarmClock.EXTRA_MINUTES, m.trim().toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        str(a, "label").takeIf { it.isNotBlank() }?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        // ★ 跟用户说「几点」,不跟他说「7」—— 回执是给他看的。
        return start(
            ctx, intent,
            ok = "闹钟设好了:${h.trim().toInt()} 点 ${m.trim().toInt().toString().padStart(2, '0')} 分。",
            subject = "设闹钟"
        )
    }

    private fun setTimer(ctx: Context, a: JSONObject): JSONObject {
        val s = str(a, "seconds")
        SelfHandMath.timerError(s)?.let { return err(it) }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s.trim().toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        str(a, "label").takeIf { it.isNotBlank() }?.let {
            intent.putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        return start(ctx, intent, ok = "倒计时设好了,${SelfHandMath.humanSeconds(s)}后响。", subject = "设倒计时")
    }

    private fun openSettings(ctx: Context, page: String): JSONObject {
        val action = SelfHandMath.settingsAction(page)
        val intent = if (action.isEmpty()) Intent(Settings.ACTION_SETTINGS)
        else Intent(action)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = if (action.isEmpty())
            "「$page」这一页我不认识,给你开了设置首页 —— 你自己找一下。" else "设置打开了。"
        return start(ctx, intent, ok = ok, subject = "打开设置")
    }

    /**
     * 剪贴板**写**。
     *
     * ★ 回执里那句「没验货」是有依据的,不是客套:Android 10 起,不在前台又不是
     * 默认输入法的应用**读不到**剪贴板。所以我们写完**没法读回来验**。
     * 这个项目的规矩是不知道就说不知道(见 `type` 验货那轮的三态),不许把
     * 「我按了那个按钮」说成「字已经在剪贴板里了」。
     */
    private fun copy(ctx: Context, text: String): JSONObject {
        if (text.isEmpty()) return err("没说复制什么。")
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return err("这台手机的剪贴板我够不着。")
        cm.setPrimaryClip(ClipData.newPlainText("ConMarn", text))
        val verified = try {
            cm.primaryClip?.getItemAt(0)?.text?.toString() == text
        } catch (_: Exception) { false }
        return JSONObject().put("ok", true)
            // ★ 这句话分两态 —— 上面 KDoc 里那句「不许说成字已经在剪贴板里了」说的就是这儿。
            //   读回来对得上才敢说「放进去了」;读不到(不在前台)只能说「请系统放了」,
            //   和同一个文件里「请系统打开了」是同一个口径。
            .put(
                "done",
                if (verified) "已经放进手机剪贴板了,读回来验过,内容对得上。"
                else "已经请系统放进剪贴板了 —— 但我没验货,不敢打包票。"
            )
            .put(
                "next",
                if (verified) "验过了,内容对得上。让用户直接粘就行。"
                else "★ 没读到剪贴板所以**验不了货**:我没在前台时系统不让读。" +
                    "如果用户粘出来是空的,就是系统拦了 —— 别当成功,重来一次或者换个办法。"
            )
    }

    private fun dial(ctx: Context, number: String): JSONObject {
        if (number.isBlank()) return err("没说号码。")
        // ★ ACTION_DIAL 不是 ACTION_CALL:它只打开拨号盘把号码填好,**不拨**。
        // 打电话是不可逆的(对面会响),那一下必须由用户自己按。
        // 用 fromParts 而不是 "tel:" + encode:后者会把 `+86` 里的加号转成 %2B,
        // 拨号盘拿到就是个坏号码,而它不会报错,只会静静显示错的东西。
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number.trim(), null))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(
            ctx, intent,
            ok = "拨号盘打开了,号码填好了 —— **还没拨出去**,要用户自己按那一下。",
            subject = "打开拨号盘"
        )
    }

    private fun vibrate(ctx: Context, raw: String): JSONObject {
        val ms = SelfHandMath.vibrateMs(raw)
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return err("这台手机没有振动马达。")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
        return JSONObject().put("ok", true).put("done", "震了一下(${ms} 毫秒)。")
    }

    private fun say(text: String): JSONObject {
        if (text.isBlank()) return err("没说念什么。")
        val v = voice
            ?: return err("她现在不在房间里(那个界面没开着),念不了话。这不是坏了,是没开。")
        v(text)
        return JSONObject().put("ok", true).put("done", "念出来了。")
    }

    // ---- 共用的小零件 ----

    /**
     * 起一个系统界面。
     *
     * ★ 这里是整只手上**唯一说不准**的地方,所以回执的措辞要分开:
     *   - 她就在屏幕上 → 大概率弹得出来,照实说「打开了」。
     *   - 她不在 → Android 从后台拉 Activity 会被静默拦掉(不报错,就是没动静)。
     *     这时**必须提前说清**「可能弹不出来」,否则用户等着一个永远不会出现的界面,
     *     而回执说成功了 —— 那正是「会撒谎的手」。
     */
    private fun start(ctx: Context, intent: Intent, ok: String, subject: String): JSONObject {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        val r = JSONObject().put("ok", true).put("done", ok)
        if (!visible) {
            r.put(
                "next",
                "★ 她这时**不在屏幕上**,系统可能把这次$subject 拦掉了 —— " +
                    "如果用户说界面上没动静,就是这个原因(去设置里给她「显示在其他应用上层」权限),不是没做成。"
            )
        }
        return r
    }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    /**
     * 参数取值。**不许用 `optString`** —— 模型给 `{"hour": 7}` 时那是 JSON 数字,
     * 各实现对数字转字符串的处理不一致,有的给 "7",有的给 "7.0",
     * 而 "7.0".toInt() 会直接抛。自己走一遍,顺手把 `7.0` 这种尾巴削掉。
     */
    private fun str(a: JSONObject, key: String): String {
        val v = a.opt(key) ?: return ""
        if (v === JSONObject.NULL) return ""
        val s = v.toString().trim()
        return if (s.endsWith(".0") && s.dropLast(2).all { it.isDigit() }) s.dropLast(2) else s
    }

    private fun typeName(e: Exception) = e.javaClass.simpleName

    /** 「没找到」后面那半句:给几个像的,比干说一句「没有」有用得多。 */
    private fun nearMiss(name: String, labels: Collection<String>): String {
        val near = labels.filter { it.contains(name) || name.contains(it) }.take(5)
        return if (near.isEmpty()) "" else "你是不是想说:" + near.joinToString("、") + "?"
    }

    // ---- 已安装应用的表(懒建、缓存) ----

    @Volatile
    private var appCache: Map<String, String>? = null

    /**
     * 应用名 → 包名。**从系统问,不抄表。**
     *
     * ★ 抄一份「微信=com.tencent.mm」的表看着简单,但它会烂:换台手机装的应用不同、
     * OEM 的包名不同(ColorOS 的相册和相机都带 `com.oplus.` 前缀)、用户自己装的东西
     * 我们更不可能预知。**问系统一次,拿到的是这台机器上真实存在的东西。**
     *
     * ⚠ 这份表**不落盘**:装了新应用不能要等到重装 App 才认得。生命周期就这次进程。
     * 拉了之后缓存住 —— 列应用在有些机器上要几百毫秒,不能每次「打开微信」都拉一遍。
     */
    private fun labelToPackage(ctx: Context): Map<String, String> {
        appCache?.let { return it }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = LinkedHashMap<String, String>()
        val list = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        for (ri in list) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName) continue          // 她自己不该出现在菜单里
            val label = try {
                ri.loadLabel(pm).toString().trim()
            } catch (_: Exception) { continue }
            if (label.isEmpty()) continue
            // 同名(比如两个「相机」)时保留先出现的 —— 系统返回的顺序就是用户看到的顺序。
            out.putIfAbsent(label, pkg)
        }
        appCache = out
        return out
    }
}

/**
 * 手机这只手里的**纯逻辑**:全是「会出错但不崩」的判断。
 *
 * 拆出来的理由跟 [MoodMath] / [HandMath] 一样 —— 这些规则错了不报异常,
 * 表现是「她开了个奇怪的链接」「闹钟设到 25 点」,真机上极难复现,
 * 必须能在纯 JVM 上钉住。
 */
internal object SelfHandMath {

    /**
     * 常见的口头叫法 → 系统里的正式名字。
     *
     * ★ 只收**别名**,不收「应用 → 包名」。包名一律问系统(见 `labelToPackage`)——
     * 抄包名的表会烂,抄别名的表不会:「威信」永远是「微信」的误写,
     * 跟这台机器装了什么没关系。
     */
    val ALIASES: Map<String, String> = mapOf(
        // 同音误写(用户的话是语音转文字,「威信」那一轮已经吃过一次)
        "威信" to "微信", "v信" to "微信", "wechat" to "微信",
        // 口语别称
        "企鹅" to "QQ", "b站" to "哔哩哔哩", "bilibili" to "哔哩哔哩",
        "网易云" to "网易云音乐", "网抑云" to "网易云音乐",
        "地图" to "高德地图", "导航" to "高德地图",
        "拍照" to "相机", "照相" to "相机", "照相机" to "相机",
        "设定" to "设置", "系统设置" to "设置",
        "闹钟" to "时钟", "定时器" to "时钟",
        "拨号" to "电话", "打电话" to "电话", "手机号" to "电话",
        "短信" to "信息", "消息" to "信息",
        "算一下" to "计算器",
        "上网" to "浏览器", "网页" to "浏览器",
        "照片" to "相册", "图库" to "相册", "图片" to "相册",
    )

    /**
     * 用户说的名字 → 系统里那个 label。→ 找不到给 null。
     *
     * 顺序是**从最严到最松**:完全一样 → 忽略大小写 → 开头 → 包含。
     *
     * ★ 松的那两步**只在没有歧义时才认**。手机上装两个名字都带「音乐」的应用
     * (QQ音乐、网易云音乐)是常态 —— 这时替模型赌一个的代价是**打开了错的应用**,
     * 而它自己完全不知道开错了,后面每一步都建在这上面。老实返回 null,
     * 让回执把候选列出来给模型挑,多花一轮但不会做错事。
     *
     * 「开头」那步取**最短**的那个:说「QQ」时命中的有「QQ」和「QQ音乐」,
     * 该给的是「QQ」—— 名字短的那个更像用户真正在说的那个。
     */
    fun matchLabel(given: String, labels: List<String>): String? {
        val g = given.trim()
        if (g.isEmpty()) return null
        labels.firstOrNull { it == g }?.let { return it }
        labels.firstOrNull { it.equals(g, ignoreCase = true) }?.let { return it }
        val sw = labels.filter { it.startsWith(g, ignoreCase = true) }
        if (sw.isNotEmpty()) {
            val best = sw.minByOrNull { it.length }!!
            // 两个一样长又都以它开头 = 真的分不出来 → 交给模型。
            return if (sw.count { it.length == best.length } == 1) best else null
        }
        val loose = labels.filter { it.contains(g, ignoreCase = true) }
        return if (loose.size == 1) loose[0] else null
    }

    /**
     * 只认 http/https。→ 规矩的网址,或 null。
     *
     * ★ 这个白名单是**安全边界**,不是格式检查。`Intent.ACTION_VIEW` 对
     * `file://`、`content://`、`javascript:` 一样照单全收 —— 而那个 URL 是
     * **模型临时拼出来的**。给她一个「什么都能打开」的入口,等于给了一条
     * 「读这台手机上的任意文件」的路子。所以反着来:不认识的 scheme 一律不开。
     *
     * 顺便补全裸域名(`www.bing.com` → `https://www.bing.com`)——
     * 模型经常漏掉协议头,而漏了协议头不等于它想说别的。
     */
    fun urlOf(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.contains("://")) {
            val scheme = s.substringBefore("://").lowercase()
            return if (scheme == "http" || scheme == "https") s else null
        }
        // 没有协议头:只有长得像域名才补 https,别的一律拒(`file:` 这类没有 `://`)
        if (s.contains(':') && !s.contains('.')) return null
        return if (s.startsWith("www.") || Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+").containsMatchIn(s)) {
            "https://$s"
        } else null
    }

    /** 搜索用**固定模板**,只有关键词是变量 —— 跟云端那只手「URL 写死、模型只能填参数」同一条规矩。 */
    fun searchUrl(query: String): String =
        "https://www.baidu.com/s?wd=" + URLEncoder.encode(query.trim(), "UTF-8")

    /** 闹钟的时分校验。→ null 表示没问题。 */
    fun clockError(hour: String, minute: String): String? {
        val h = hour.trim().toIntOrNull()
            ?: return "「$hour」不是一个小时数,我应该收到 0~23 的整数,比如 7。"
        val m = minute.trim().toIntOrNull()
            ?: return "「$minute」不是一个分钟数,我应该收到 0~59 的整数,比如 30。"
        if (h !in 0..23) return "小时要在 0~23 之间,「$h」不在里面。"
        if (m !in 0..59) return "分钟要在 0~59 之间,「$m」不在里面。"
        return null
    }

    /** 倒计时秒数校验。上限 24 小时 —— 再长就不是倒计时是日程了。 */
    fun timerError(seconds: String): String? {
        val s = seconds.trim().toIntOrNull()
            ?: return "「$seconds」不是一个秒数,我应该收到整数,比如 300。"
        if (s <= 0) return "倒计时的秒数要大于 0,「$s」不是。"
        if (s > 86_400) return "倒计时最长 24 小时,「$s」秒太长了。"
        return null
    }

    /** 秒 → 人话。「300」对用户来说没有「5 分钟」好懂。 */
    fun humanSeconds(seconds: String): String {
        val s = seconds.trim().toIntOrNull() ?: return seconds
        return when {
            s % 3600 == 0 -> "${s / 3600} 小时"
            s >= 3600 -> "${s / 3600} 小时 ${(s % 3600) / 60} 分钟"
            s % 60 == 0 -> "${s / 60} 分钟"
            s > 60 -> "${s / 60} 分 ${s % 60} 秒"
            else -> "$s 秒"
        }
    }

    /**
     * 设置页的名字 → 那个 action。→ **空串表示不认识**。
     *
     * 不认识时**不猜**:猜错页比开到首页更糟(用户按「电池」进去看到的是网络设置,
     * 会以为手机坏了)。退到设置首页,并在回执里直说不认识。
     */
    fun settingsAction(page: String): String {
        return when (page.trim().lowercase()) {
            "wifi", "无线", "网络" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth", "蓝牙" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "app", "apps", "应用", "应用管理" -> Settings.ACTION_APPLICATION_SETTINGS
            "battery", "电池", "省电" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display", "显示", "屏幕" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "声音", "音量" -> Settings.ACTION_SOUND_SETTINGS
            "storage", "存储", "内存" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "location", "定位", "位置" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane", "飞行模式" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "date", "时间", "日期" -> Settings.ACTION_DATE_SETTINGS
            "accessibility", "无障碍" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "developer", "开发者" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            else -> ""
        }
    }

    /**
     * 震多久。**夹在 50~2000 毫秒之间**。
     *
     * 模型给的数可能是 5(感觉不到,她会以为成功了)或者 60000(一分钟长震,
     * 用户会以为手机坏了)。夹一下,而且**回执里报的是真震的时长**,不是它要的时长。
     */
    fun vibrateMs(raw: String): Long {
        val v = raw.trim().toLongOrNull() ?: 200L
        return v.coerceIn(50L, 2000L)
    }
}
