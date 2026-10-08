#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""server.py 协议自测(不弹窗、不真动鼠标:靠 monkeypatch 隔离)。"""
import base64
import io
import os
import socket
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# 控制台可能是 GBK(cp936),直接 print 中文会 UnicodeEncodeError 或者印成乱码
# (别的 test_*.py 都有这段,就这个漏了)。统一切到 UTF-8。
try:
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
except Exception:
    pass

import server


def last_event(events, kind):
    for e in reversed(events):
        if e["kind"] == kind:
            return e
    raise AssertionError(f"缺少 {kind} 事件")


def readline(f):
    return f.readline().strip()


def connect(port):
    s = __import__("socket").create_connection(("127.0.0.1", port), timeout=3)
    f = s.makefile("rw", encoding="utf-8", newline="\n")
    return f, s


# 1. DPAPI 往返
def test_dpapi():
    data = b"test-secret-1234567890"
    enc = server.dpapi_encrypt(data)
    dec = server.dpapi_decrypt(enc)
    assert dec == data, "DPAPI roundtrip failed"
    print("1. DPAPI 加密往返 OK")


# 2. TOTP + 锁定
def test_totp_lockout():
    secret = server.generate_secret()
    code = server.totp_code(secret)
    assert server.verify_totp(secret, code), "TOTP verify failed"
    # 找一个必然错误的码
    wrong = None
    for attempt in range(10000):
        w = f"{attempt:06d}"
        if not server.verify_totp(secret, w):
            wrong = w
            break
    assert wrong is not None
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0
    for i in range(3):
        status, info = server.check_totp(secret, wrong)
        if i < 2:
            assert status == "bad", f"第 {i + 1} 次应 bad, got {status}"
        else:
            assert status == "locked", f"第 3 次应 locked, got {status}"
    status, info = server.check_totp(secret, code)  # 锁定中,即便正确也拒绝
    assert status == "locked", f"expected locked, got {status}"
    print("2. TOTP + 3 次错锁定 OK")


# 3. 完整协议:首次配对 -> TOTP -> 控制模式;已配对;恢复码;旧恢复码作废;空闲超时
def test_full_flow():
    events = []
    server._emit = lambda kind, **kw: events.append({"kind": kind, **kw})  # 隔离,不弹窗不打印
    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    server.init_store()
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0

    port = 19527
    threading.Thread(target=server.serve, args=(port,), daemon=True).start()
    time.sleep(0.3)

    # ---- 首次配对 ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "SETUP"
    secret = last_event(events, "setup")["secret"]
    recovery = last_event(events, "setup")["recovery"]
    assert len(secret) == 32 and len(recovery) == 8, (secret, recovery)
    f.write(f"TOTP {server.totp_code(secret)}\n"); f.flush()
    assert readline(f) == "OK"
    assert server.get_secret() == secret
    # 控制模式:发一条合法 M 帧
    sk = server.derive_session_key(pin, secret)
    sig = server.sign_command(sk, "M 1 5 5 0 0")
    f.write(f"M 1 5 5 0 0 {sig}\n"); f.flush()
    time.sleep(0.2)
    f.close(); s.close()
    time.sleep(0.3)
    print("3a. 首次配对 + 控制模式 OK")

    # ---- 已配对 TOTP 认证 ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "TOTP_REQUIRED"
    f.write(f"TOTP {server.totp_code(secret)}\n"); f.flush()
    assert readline(f) == "OK"
    f.close(); s.close()
    time.sleep(0.3)
    print("3b. 已配对 TOTP 认证 OK")

    # ---- 恢复码重新配对(模拟手机丢失,本地无种子) ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "TOTP_REQUIRED"
    f.write(f"RECOVER {recovery}\n"); f.flush()  # 用一次性恢复码
    assert readline(f) == "SETUP"
    new_secret = last_event(events, "setup")["secret"]
    new_recovery = last_event(events, "setup")["recovery"]
    assert new_secret != secret and new_recovery != recovery
    f.write(f"TOTP {server.totp_code(new_secret)}\n"); f.flush()
    assert readline(f) == "OK"
    assert server.get_secret() == new_secret
    f.close(); s.close()
    time.sleep(0.3)
    print("3c. 恢复码重新配对 OK")

    # ---- 旧恢复码应已作废 ----
    f, s = connect(port)
    assert readline(f) == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    f.write(f"PIN {pin}\n"); f.flush()
    assert readline(f) == "TOTP_REQUIRED"
    f.write(f"RECOVER {recovery}\n"); f.flush()  # 旧恢复码
    assert readline(f) == "ERR bad recovery"
    f.close(); s.close()
    time.sleep(0.3)
    print("3d. 旧恢复码作废 OK")

    # ---- 空闲超时(把超时降到 1 秒测) ----
    # ⚠️ 产品默认是**不断开**的(IDLE_TIMEOUT = None,按用户要求关掉了空闲断连);
    # 这里只是把这个值临时压到 1 秒,好把控制循环里那条 socket.timeout 分支走一遍。
    # **测完必须还原** —— test_streaming 跑在这个函数后面,共用同一个模块级变量,
    # 留着 1 秒会让它刚开流就被自己的读超时掐掉。
    server.IDLE_TIMEOUT = 1
    try:
        f, s = connect(port)
        assert readline(f) == "PIN_REQUIRED"
        pin = last_event(events, "pairing")["pin"]
        f.write(f"PIN {pin}\n"); f.flush()
        assert readline(f) == "TOTP_REQUIRED"
        f.write(f"TOTP {server.totp_code(new_secret)}\n"); f.flush()
        assert readline(f) == "OK"

        # ★ 这里踩过一次,记下来:认证通过后服务端还会**追发几行握手**
        # (MEDIA_TOKEN / RESUME / FILE_TOKEN,见 server.py 认证通过那一段),
        # 而老写法是
        #     assert f.readline() == "" or f.readline() == ""
        # 它把这两行握手当成了「没断开」的证据 —— 挂的是测试,不是服务端。
        # 而且失败时它一个字都不说,只丢一句 AssertionError,得靠猜才知道服务端回了什么。
        # 现在:一直读到 EOF,中途收到什么都记下来;等不到 EOF 就把收到的东西原样报出来。
        # 顺带把「认证通过必须发 RESUME 续连 token」这条协议也钉住。
        got = []
        closed = False
        s.settimeout(5)
        try:
            while True:
                line = readline(f)
                if line == "":
                    closed = True
                    break
                got.append(line.split(" ")[0])
        except (socket.timeout, TimeoutError):
            pass
        assert closed, "空闲 1 秒后连接没断开,期间收到 %r" % got
        assert "RESUME" in got, "认证通过后应发 RESUME 续连 token,实际收到 %r" % got
        f.close(); s.close()
    finally:
        server.IDLE_TIMEOUT = None
    print("3e. 空闲超时自动断开 OK")

    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    print("全部通过!")


# 4. 屏幕镜像 / 绝对点击 / 文本输入(用假帧,不真抓屏)
def test_streaming():
    events = []
    server._emit = lambda kind, **kw: events.append({"kind": kind, **kw})
    server.capture_frame = lambda: (b"\xff\xd8FAKEJPEG\xff\xd9", 1920, 1080)
    server.STREAM_FPS = 50
    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    server.init_store()
    server._fail_count = 0
    server._lock_count = 0
    server._lock_until = 0

    port = 19528
    threading.Thread(target=server.serve, args=(port,), daemon=True).start()
    time.sleep(0.3)

    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    rb = s.makefile("rb")

    def rd_line():
        return rb.readline().decode("utf-8").strip()

    def send(txt):
        s.sendall((txt + "\n").encode("utf-8"))

    # 首次配对
    assert rd_line() == "PIN_REQUIRED"
    pin = last_event(events, "pairing")["pin"]
    send(f"PIN {pin}")
    assert rd_line() == "SETUP"
    secret = last_event(events, "setup")["secret"]
    send(f"TOTP {server.totp_code(secret)}")
    assert rd_line() == "OK"
    sk = server.derive_session_key(pin, secret)

    # 开启屏幕镜像,收一帧
    #
    # ★ 和 3e 同一个坑:认证通过后服务端先追发几行握手(MEDIA_TOKEN / RESUME /
    # FILE_TOKEN),老代码以为 OK 之后第一行就是 FRAME,直接被 MEDIA_TOKEN 顶掉。
    # 改成一直读到 FRAME 头为止,中间收到什么都记下来 —— 顺便把「必须发 RESUME」
    # 这条协议也钉住。(光标回传的 CP 行也会混进来,所以不能只放行握手行。)
    send(f"V 1 1 {server.sign_command(sk, 'V 1 1')}")
    handshake = []
    header = rd_line()
    while not header.startswith("FRAME "):
        handshake.append(header.split(" ")[0])
        assert len(handshake) < 8, "开镜像后一直没等到 FRAME,只收到 %r" % handshake
        header = rd_line()
    assert "RESUME" in handshake, "认证通过后应发 RESUME 续连 token,实际 %r" % handshake
    _, length, w, h = header.split()
    length, w, h = int(length), int(w), int(h)
    assert (w, h) == (1920, 1080), (w, h)
    assert rb.read(length) == b"\xff\xd8FAKEJPEG\xff\xd9"

    # 绝对点击
    send(f"A 2 100 200 1 {server.sign_command(sk, 'A 2 100 200 1')}")
    # 文本输入(中文)
    b64 = base64.b64encode("你好".encode()).decode()
    send(f"T 3 {b64} {server.sign_command(sk, 'T 3 ' + b64)}")
    # 关闭镜像
    send(f"V 4 0 {server.sign_command(sk, 'V 4 0')}")
    time.sleep(0.3)

    rb.close()
    s.close()
    if os.path.exists(server.SECRET_PATH):
        os.remove(server.SECRET_PATH)
    print("4. 屏幕镜像/绝对点击/文本输入 OK")


def test_no_secrets_in_log():
    """★ 盘上那条路:凭证和号码都不许出现在**写下去的那一行**里(2026-10-06)。

    ★ 验的是**真的那个写入口**(`_log_line`),只是把落点临时换成一个临时文件 ——
      因为要看的就是「过完那道闸之后,落盘的字节长什么样」。
      **看回执不算数**:同 `test_click_ui.py`「被拦的那一下一次都没点」那条纪律,
      证据必须来自真东西。
    """
    import tempfile

    real = server.LOG_PATH
    fd, tmp = tempfile.mkstemp(suffix=".log")
    os.close(fd)
    server.LOG_PATH = tmp
    try:
        # ── 一、凭证:配对码 / 长期种子 / 一次性恢复码 ──
        # 这三样「谁拿到,谁就能连上这台电脑、然后动你的鼠标键盘」——
        # 但它们**必须原样进 GUI**(配对码要显示给他看,种子要写进 secret.json)。
        # 所以拆成两半:**GUI 拿原值,日志拿占位**。
        pin, sec, rec = "482913", "SEEDSECRET0123456789", "RECOVERY-abcdef0123456789"

        only_pin = server._emit_log_fields({"pin": pin})
        assert pin not in str(only_pin), only_pin

        fields = server._emit_log_fields({"secret": sec, "recovery": rec, "port": 9527})
        assert sec not in str(fields) and rec not in str(fields), fields
        # ★ 但不能整条静默消失 —— 别的字段一个都不许动,而且要说得出「这里本来有东西」
        assert fields["port"] == 9527, "只该换掉凭证,别的字段不许动: %r" % (fields,)
        assert any("凭证" in str(v) for v in fields.values()), \
            "凭证被换掉了,却没留一句「这里本来有东西」: %r" % (fields,)

        # ── 二、走真写入口:有形状的值 ──
        server._log_line('[ai] type -> {"text": "我的号 13812345678"}')
        with open(tmp, encoding="utf-8") as fp:
            body = fp.read()
        assert "13812345678" not in body, "号码不该出现在日志里:\n%s" % body
        assert "[手机号]" in body, "该遮的没遮:\n%s" % body

        # ── 二·五、★★ 一道**说实话的**边界线:光靠日志那道闸挡不住凭证 ──
        #
        # 「有形状的能找出来;而一个纯字母数字的密码/配对码,**进过文字之后,任何正则
        #   都找不回来**」—— 这条是这一轮的前提,不是免责声明(用户 2026-10-06 拍的板)。
        #
        # 一个 6 位配对码和 `11565`(点击耗时)、`482913`(任何编号)在正则眼里**长得一模一样**,
        # 所以 `_redact_log` **不可能**、也**不应该**去猜它 —— 猜的代价是日志里所有数字全变星号,
        # 而这个项目是靠日志活着的。
        #
        # ★ 所以**真正的防线在源头那一格**:凭证根本不走 `_log_line`,
        #   它只走 `_emit_log_fields`(上面第一节钉的就是它)。
        # ★ 这里把这条边界**钉成一条会红的断言**,而不是写成一句注释 ——
        #   哪天有人给日志加了「六位数字也遮」的规则,这条会当场红,
        #   逼他回来看一眼「是补上了,还是把日志毁了」。
        #   (同 `test_redact.py` 里那条「装了就该红」的手法。)
        server._log_line("配对 pin=%s" % pin)
        with open(tmp, encoding="utf-8") as fp:
            body2 = fp.read()
        assert pin in body2, (
            "★ 日志那道闸现在**不遮**六位配对码(它没有形状)。\n"
            "  这不是 bug 是设计 —— 但你要是刚给它加了形状判据,"
            "先想清楚日志里那些正常的六位数会变成什么。")
        # ★ 而种子/恢复码更长、更结构化,但**同样没有形状**(字母数字混排),
        #   照样漏 —— 同一条道理,不重复钉。
        assert sec not in str(server._emit_log_fields({"secret": sec}))  # 源头那一格是好的

        # ── 三、★ 反控(重点):证据行一个字都不许动 ──
        #   这条日志全项目天天要看(`[ai] click_ui -> ok=True 11565ms` 就是「她点到了」的证据)——
        #   闸要是这里紧张一下,以后日志里全是标记,而这个项目是靠日志活着的。
        before = open(tmp, encoding="utf-8").read()
        keep = "[ai] click_ui -> ok=True 11565ms clicked_in=记事本"
        server._log_line(keep)
        after = open(tmp, encoding="utf-8").read()
        added = after[len(before):]
        assert added.strip().endswith(keep), "证据行被改动了: %r" % added
        # 只该多出「时间戳 + 那一行 + 换行」,别的一个字符都没动
        assert len(added.strip()) == len(keep) + 9, repr(added)

        print("5. 凭证/号码不进日志 OK")
    finally:
        server.LOG_PATH = real
        try:
            os.remove(tmp)
        except OSError:
            pass


if __name__ == "__main__":
    test_dpapi()
    test_totp_lockout()
    test_full_flow()
    test_streaming()
    test_no_secrets_in_log()
