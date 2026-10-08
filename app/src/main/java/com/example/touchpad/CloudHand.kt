package com.example.touchpad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端那只手:把 [ApiStore] 里的**声明**变成 [Hand](给模型看的菜单),
 * 再把 `use_hand` 的调用变成一次真正的 HTTP 请求。
 *
 * ## 一只用途 = 一只手
 *
 * 天气是一只手,地图路线是一只手。`list_hands` 报出来就是
 * 「云服务「天气」,2 个工具」。**面板里管的和这里执行的是同一份数据**
 * —— 不是两张表(见 [ApiModel] 文件头那条架构主张)。
 *
 * ## ★★ 三条不变量,全在这里落成代码
 *
 * 1. **模型改不了地址。** 它给的只有参数值;URL 是 [ApiMath.buildUrl] 用本地
 *    文件里的模板拼的。它没有机会让请求发到第二个主机名上。
 * 2. **只发文本,fail closed。** 两处拦:声明([HandTool.sends])和正文
 *    ([ApiMath.payloadDirtyReason])。**默认是不发**,不是「发出去看看」。
 * 3. **绝不撒谎。** 每次调用要么给出真数据,要么给出**人话原因**
 *    (没填 key / 缺哪个参数 / 接口回了但没有那一块)。不返回空数据假装成功。
 *
 * ## 和电脑那只手的区别(为什么它不该排队)
 *
 * 电脑那只手要抢鼠标、抢前台窗口 —— 那是**物理约束**,必须串行。
 * 云端这只手**什么都不占**,同时发十个请求也不会互相踩。
 * 所以它的 `locks` 是**空的**:天生可以并发,不该被排进队列。
 */
object CloudHand {

    const val ID_PREFIX = "api:"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    private var appContext: Context? = null

    /**
     * 上下文由自己持有 —— 和 [SelfHand] 一样,**不**要求调用方传进来。
     *
     * 理由是 `use_hand` 那条路在 [AiAgent] 里,而 AiAgent 本身**不持有 Context**
     * (它是被 `AiAgentHolder.get(app)` 造出来的)。让每个调用点各自想办法拿
     * Context,迟早有人拿错一个(Activity 泄漏 / 拿到已经销毁的那个)。
     * 一个 `applicationContext` 存在这儿,谁都拿不错。
     */
    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        ApiStore.init(ctx)
    }

    private fun trace(msg: String) {
        try {
            appContext?.let { ModelManager.get(it).trace("[api] $msg") }
        } catch (_: Exception) {
        }
    }

    fun idOf(purposeId: String): String = ID_PREFIX + purposeId

    fun purposeIdOf(handId: String): String = handId.removePrefix(ID_PREFIX)

    /**
     * 组装给模型看的手。
     *
     * ★ [Hand.fetchedAtMs] 填「现在」,不是问到的时刻 —— 因为这份菜单是
     * 从**我们自己的文件**里读的,不是问别的机器要的。它**永远不过期**,
     * 所以不能让 [HandMath.staleNote] 跳出来说「这份清单是 N 小时前的」——
     * 那是**假警告**,模型会学会无视它,真过期那天也照样无视。
     */
    fun hands(): List<Hand> {
        val purposes = ApiStore.purposes()
        val decls = ApiStore.tools()
        val now = System.currentTimeMillis()
        return purposes.mapNotNull { p ->
            val mine = decls.filter { it.purpose == p.id }
            // 一个工具都没有的用途不摆出来 —— 空菜单只会让模型白点一次。
            if (mine.isEmpty()) return@mapNotNull null
            Hand(
                id = idOf(p.id),
                kind = HandMath.KIND_CLOUD,
                name = p.label,
                version = "1",
                fetchedAtMs = now,
                tools = mine.map { it.toHandTool() },
                // ★ 空 = 不占任何排他资源 = 天生可并发。理由见文件头。
                locks = mine.associate { it.name to emptyList<String>() },
            )
        }
    }

    // ------------------------------------------------------------------
    // 执行
    // ------------------------------------------------------------------

    fun exec(hand: Hand, toolName: String, args: JSONObject): JSONObject {
        val purposeId = purposeIdOf(hand.id)
        val purpose = ApiStore.purpose(purposeId)
            ?: return fail("找不到用途「$purposeId」(它可能被删掉了)。")

        val profile = ApiMath.active(purpose)
            ?: return fail("「${purpose.label}」现在用不了:${ApiMath.checkPurpose(purpose) ?: "这一档被关掉了"}")
        ApiMath.checkProfile(profile)?.let {
            return fail("「${purpose.label}」还差一步:$it")
        }

        val decl = ApiStore.tools().firstOrNull { it.purpose == purposeId && it.name == toolName }
            ?: return fail("「${purpose.label}」上没有「$toolName」这个工具。先 list_hands 看一眼。")

        // ---- 红线第一道:声明 ----
        if (!ApiMath.sendsOk(decl.sends)) {
            val bad = decl.sends.filter { it !in ApiMath.ALLOWED_SENDS }
            ApiStore.recordCall(purposeId, false, 0, 0, "声明里含 ${bad.joinToString("/")}")
            return fail("这条接口声明过会送「${bad.joinToString("/")}」出去,按规矩不发。")
        }

        val exec = decl.exec
        val given = toStringMap(args)
        ApiMath.missingArgs(decl.required, given).let {
            if (it.isNotEmpty()) return fail("还缺参数:${it.joinToString("、")}。")
        }

        // ★ 当前档的 key 从这儿注入成 `{key}`。于是「用户还没填 key」会**自动**
        //   变成 buildUrl 返回 null = 请求不发 —— 而不是发一个 `key=` 的空请求。
        //   **fail closed 是从数据结构里白来的,不是靠记得检查。**
        val withKey = given + ("key" to profile.apiKey)
        val url = ApiMath.buildUrl(profile.baseUrl, exec.path, exec.fixedQuery, exec.query, withKey)
            ?: return fail(
                if (profile.apiKey.isBlank()) "「${purpose.label}」还没填 key,去 API 控制中心填一个。"
                else "地址里有没填上的参数,这一条不能发。"
            )

        val body = buildBody(exec, withKey)

        // ---- 红线第二道:正文 ----
        // 声明是我们写的,但**拼进去的内容不是** —— 用户说「看看这个截图」,
        // 那张图的 base64 会出现在**参数值**里。这道只有查正文拦得住。
        ApiMath.payloadDirtyReason(url + (body ?: "")).let {
            if (it != null) {
                ApiStore.recordCall(purposeId, false, 0, 0, it)
                return fail(it)
            }
        }

        val safeUrl = ApiMath.redact(url, ApiStore.knownKeys())
        val t0 = System.currentTimeMillis()
        return try {
            val (status, text) = send(exec.method, url, body)
            val ms = (System.currentTimeMillis() - t0).toInt()
            if (status !in 200..299) {
                val why = "接口回了 HTTP $status:${ApiMath.ellipsis(text.trim(), 200)}"
                ApiStore.recordCall(purposeId, false, ms, status, why)
                trace("← $status ${ApiMath.ellipsis(safeUrl, 160)}")
                return fail("${purpose.label} 那边没给数据($why)")
            }
            val data = shape(text, exec)
            if (data == null) {
                // ★ 两种取法的「摘不到」是**两件不同的事**,不能共用一句话 ——
                //   网页那条里 exec.pick 是空的,照原话会拼出「里面没有「」这一块」,
                //   等于什么都没说,而模型只会原样转述这句废话给用户。
                val why = if (exec.extract == ApiMath.EXTRACT_TEXT)
                    "网页打开是打开了,但里面**没有能读的文字**(这个网站的正文是脚本现场画出来的)。换个能直接搜到答案的说法再试,或者告诉他这个我读不到。"
                else "接口回了 200,但里面没有「${exec.pick}」这一块 —— 它的返回格式可能变了。"
                ApiStore.recordCall(purposeId, false, ms, status, why)
                return fail(why)
            }
            // 成功也留一句 —— 但**只留形状,不留内容**(`pick` 的路径 + 摘出来多少字)。
            // 这也是 [ApiCall] 那条规矩:调试看的是「这一块取到没有」,不是他查了什么。
            ApiStore.recordCall(purposeId, true, ms, status, "「${exec.pick}」拿到 ${data.length} 字")
            trace("← 200 ${data.length}字 $ms ms ${ApiMath.ellipsis(safeUrl, 160)}")
            JSONObject().put("ok", true).put("data", data)
        } catch (e: Exception) {
            val ms = (System.currentTimeMillis() - t0).toInt()
            // ★ 异常信息里**可能带着 key**(高德把 key 放 query,而有些异常会回显 URL)。
            val why = ApiMath.redact("${e.javaClass.simpleName}: ${e.message}", ApiStore.knownKeys())
            ApiStore.recordCall(purposeId, false, ms, 0, why)
            trace("✗ $why")
            fail("连不上 ${purpose.label}:$why")
        }
    }

    /**
     * 面板上的「测连通」。
     *
     * ★ 只打一下 baseUrl,**不碰任何工具** —— 它回答的是「这个地址和这个 key 通不通」,
     * 不是「某个具体接口对不对」。混在一起的话,用户会以为「连通了 = 能用」。
     */
    fun ping(profile: ApiProfile): String {
        ApiMath.checkProfile(profile)?.let { return it }
        return try {
            val (status, _) = send("GET", profile.baseUrl, null)
            // 403/401 说明**通了但 key 不对** —— 这跟「连不上」是两件事,
            // 混成一句会让用户去查网络,而问题其实在 key 上。
            when (status) {
                in 200..299 -> "通了(HTTP $status)"
                401, 403 -> "连得上,但它不收这个 key(HTTP $status)"
                else -> "连得上,但它回了 HTTP $status"
            }
        } catch (e: Exception) {
            "连不上:" + ApiMath.redact("${e.javaClass.simpleName}: ${e.message}", listOf(profile.apiKey))
        }
    }

    // ------------------------------------------------------------------

    private fun send(method: String, url: String, body: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return status to text
        } finally {
            conn.disconnect()
        }
    }

    private fun buildBody(exec: ApiExec, args: Map<String, String>): String? {
        if (exec.bodyParams.isEmpty()) return null
        val o = JSONObject()
        // ★ 空值也发 —— body 里「没给」和「给了空串」在 JSON API 里常常是两回事,
        //   静默丢掉一个参数比发一个空值更难查。
        exec.bodyParams.forEach { o.put(it, args[it] ?: "") }
        return o.toString()
    }

    /**
     * 从响应里摘出给模型看的那一块。
     *
     * → null = **摘不到**(接口形状变了 / pick 路径写错了 / 网页是 JS 画的)。
     * ★ 这里绝不能「摘不到就整份给它」—— 天气一份原始响应 39 KB,
     * 塞进上下文会把它撑爆,而她会在最后一句话里突然失忆。
     */
    private fun shape(text: String, exec: ApiExec): String? {
        // ---- 网页:去标签取正文 ----
        if (exec.extract == ApiMath.EXTRACT_TEXT) {
            val body = ApiMath.htmlToText(text)
            // ★ 空正文**必须报错,不能当成功**。整页靠 JS 画出来的站点抓回来就是
            //   一片空白 —— 而「查到了但什么都没有」在模型那边和「这页是空的」
            //   长得一样,它会照自己的记忆编一个答案,我们这边一切正常。
            if (body.isBlank()) return null
            return ApiMath.ellipsis(body, exec.maxChars)
        }

        if (exec.pick.isBlank()) return ApiMath.ellipsis(text.trim(), exec.maxChars)
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: runCatching { JSONArray(text) as Any }.getOrNull()
            ?: return null
        val picked = walk(root, ApiMath.pathSegments(exec.pick)) ?: return null
        return ApiMath.ellipsis(picked.toString(), exec.maxChars)
    }

    private fun walk(root: Any?, segs: List<ApiMath.Seg>): Any? {
        var cur: Any? = root
        for (s in segs) {
            cur = when (s) {
                is ApiMath.Seg.Key -> (cur as? JSONObject)?.opt(s.name)
                is ApiMath.Seg.Idx -> (cur as? JSONArray)?.opt(s.i)
            }
            if (cur == null || cur === JSONObject.NULL) return null
        }
        return cur
    }

    private fun toStringMap(args: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        args.keys().forEach { k ->
            val v = args.opt(k)
            if (v != null && v !== JSONObject.NULL) out[k] = v.toString()
        }
        return out
    }

    /** 回执的形状和电脑那只手保持一致(`ok` + 人话)。 */
    private fun fail(why: String): JSONObject = JSONObject().put("ok", false).put("error", why)
}
