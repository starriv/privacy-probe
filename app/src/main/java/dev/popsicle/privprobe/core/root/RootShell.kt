package dev.popsicle.privprobe.core.root

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 唯一的 root 执行入口。
 *
 * 协议（P1-8 修复）：
 * - 命令串行执行（per-command 同步）；每条命令带**随机 nonce**，结束哨兵
 *   `__PR_END__<nonce> <退出码>` 必须整行匹配，命令输出无法伪造哨兵。
 * - 每条命令由设备上的 `timeout -k 1s <t>s sh -c '<cmd>'` 包裹：超时由 shell 侧杀死
 *   子进程并照常打印哨兵，Kotlin 侧继续 drain 到哨兵；**普通超时不重建整条连接**。
 * - 发送命令前清空上一条命令的残余输出；连接 reset 前 join reader 线程。
 * - 输出上限 [MAX_OUTPUT_CHARS]，超出截断，避免全量 dump 打爆内存。
 *
 * 分通道（PERF P1-5）：默认 main 通道；timeout ≥ [HEAVY_TIMEOUT_THRESHOLD_MS] 的重命令
 * （如全量 appops 25s）走独立 heavy 通道，避免独占锁让 logcat/相机等轻探针排队。
 *
 * 可用性（P1-9/P1-10 修复）：
 * - 每个通道握手失败后缓存 `nextRetryAt = now + 30s`，期间快速失败；
 *   单次握手 2s 上限，提权弹窗导致超时后不再尝试其它 argv 变体。
 * - [exec] 成功刷新 availability；[openStream] 不信任缓存、必须确保真实连接存在。
 * - [requestInterrupt]/[close] 可让正在执行的长命令 200ms 内放弃，停止采集不必等 appops。
 */
object RootShell {

    private const val TAG = "RootShell"
    private const val EOF_MARK = "\u0000__PR_EOF__"
    private const val DEFAULT_TIMEOUT_MS = 8_000L
    private const val HANDSHAKE_TIMEOUT_MS = 2_000L
    private const val RETRY_BACKOFF_MS = 30_000L
    private const val END_PREFIX = "__PR_END__"
    private const val TIMEOUT_MARK = "__PR_TIMEOUT__"
    private const val DRAIN_GRACE_MS = 1_500L
    private const val READER_JOIN_MS = 400L
    private const val HEAVY_TIMEOUT_THRESHOLD_MS = 20_000L
    private const val MAX_OUTPUT_CHARS = 8 * 1024 * 1024
    private const val FORCE_HANDSHAKE_MIN_INTERVAL_MS = 4_000L

    data class Result(
        val ok: Boolean,
        val stdout: String,
        val stderr: String,
    ) {
        fun lines(): List<String> =
            stdout.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList()

        companion object {
            fun fail(stderr: String) = Result(false, "", stderr)
        }
    }

    /** [openStream] 返回的长驻 root 进程句柄。 */
    class Stream internal constructor(
        private val process: Process,
        private val onClose: (Stream) -> Unit,
    ) {
        val reader: BufferedReader = BufferedReader(InputStreamReader(process.inputStream))
        val isAlive: Boolean get() = process.isAlive

        fun close() {
            onClose(this)
            runCatching { process.destroy() }
            runCatching {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
    }

    private class Connection(
        val process: Process,
        val stdin: BufferedWriter,
        val queue: LinkedBlockingQueue<String>,
        val readerThread: Thread,
        val stderrThread: Thread,
        @Volatile var dead: Boolean = false,
    )

    /** 一条独立 root 长连接（main / heavy）。 */
    private class ChannelState(val name: String) {
        val lock = ReentrantLock()
        var conn: Connection? = null

        @Volatile
        var nextRetryAt = 0L

        @Volatile
        var timeoutSupported = true

        @Volatile
        var mode: String? = null
    }

    private class RawRun(val stdout: String, val dead: Boolean)

    private class Outcome(val result: Result, val connectionAlive: Boolean)

    private val mainChannel = ChannelState("main")
    private val heavyChannel = ChannelState("heavy")
    private val random = SecureRandom()
    private val openStreams = CopyOnWriteArrayList<Stream>()
    private val stderrTail = ArrayDeque<String>()
    private val stderrGuard = Any()

    @Volatile
    private var availability: Boolean? = null

    @Volatile
    var detail: String = "尚未检测 root"
        private set

    /** stop/refresh 时要求正在执行的命令尽快放弃，避免重命令独占锁导致停止等待。 */
    @Volatile
    private var interruptRequested = false

    /** 上次 openStream 强制握手时间，限制强制重试频率，避免无 root 时反复弹授权框。 */
    @Volatile
    private var lastForcedHandshakeAt = 0L

    // ------------------------------------------------------------------ 对外 API

    fun isAvailable(force: Boolean = false): Boolean {
        if (force) {
            mainChannel.nextRetryAt = 0L
            heavyChannel.nextRetryAt = 0L
        } else {
            val cached = availability
            if (cached == true) return true
            if (SystemClock.elapsedRealtime() < mainChannel.nextRetryAt) return false
        }
        val r = exec("id", HANDSHAKE_TIMEOUT_MS)
        val ok = r.ok && r.stdout.contains("uid=0(root)")
        availability = ok
        if (!ok) {
            mainChannel.nextRetryAt = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
            if (detail.isBlank()) detail = "root 不可用：${r.stderr.take(160).ifBlank { "su 无响应" }}"
        }
        return ok
    }

    /**
     * 串行执行一条 root 命令；返回退出码与输出。[exec] 成功会刷新 availability。
     * timeout ≥ 20s 的命令走 heavy 通道，不阻塞普通探针。
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, allowMainThread: Boolean = false): Result {
        if (!allowMainThread && Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "exec() refused on main thread: ${command.take(80)}")
            return Result.fail("exec() 不能在主线程调用")
        }
        val channel = if (timeoutMs >= HEAVY_TIMEOUT_THRESHOLD_MS) heavyChannel else mainChannel
        return channel.lock.withLock {
            if (!ensureConnectedLocked(channel)) {
                return Result.fail(stderrSnapshot().ifBlank { "root 连接不可用" })
            }
            val outcome = runCommandLocked(channel, command, timeoutMs)
            if (outcome.connectionAlive) {
                availability = true
                channel.nextRetryAt = 0L
            } else {
                channel.nextRetryAt = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
                if (channel === mainChannel) availability = false
                detail = "root 连接中断（${channel.name}）：${outcome.result.stderr.take(160)}"
            }
            outcome.result
        }
    }

    /** 便捷封装：失败返回 null。 */
    fun execLines(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<String>? {
        val r = exec(command, timeoutMs)
        return if (r.ok) r.lines() else null
    }

    /**
     * 开启一条 root 长驻流（如 logcat）。
     *
     * P1-9：不信任 availability 缓存，必须确保真实连接存在（否则 root 授权后 logcat 永久起不来）；
     * 带 [forceHandshake] 时强制握手，但限制 4s 一次，避免无 root 时反复触发授权弹窗。
     */
    fun openStream(command: String, forceHandshake: Boolean = true): Stream? {
        mainChannel.lock.withLock {
            if (forceHandshake) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastForcedHandshakeAt >= FORCE_HANDSHAKE_MIN_INTERVAL_MS) {
                    lastForcedHandshakeAt = now
                    mainChannel.nextRetryAt = 0L
                }
            }
            if (!ensureConnectedLocked(mainChannel)) return null
            availability = true
            mainChannel.nextRetryAt = 0L
        }
        return try {
            val p = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stream = Stream(p, { openStreams.remove(it) })
            openStreams.add(stream)
            Thread({
                runCatching {
                    BufferedReader(InputStreamReader(p.errorStream)).useLines { seq ->
                        seq.forEach { addStderr("stream[$command]: $it") }
                    }
                }
            }, "RootShell-stream-stderr").apply { isDaemon = true; start() }
            stream
        } catch (t: Throwable) {
            Log.w(TAG, "openStream failed: $command", t)
            null
        }
    }

    /** 关闭所有长驻流（logcat 重启 / 探针停止时调用，让被取消的探针立即退出）。 */
    fun closeStreams() {
        openStreams.toList().forEach { runCatching { it.close() } }
        openStreams.clear()
    }

    /**
     * 请求中断两个通道上正在执行的命令（停止采集/重连前调用）。
     * exec 轮询以 200ms 粒度检查标志并尽快释放 root 锁。
     */
    fun requestInterrupt() {
        interruptRequested = true
        runCatching { mainChannel.conn?.process?.destroy() }
        runCatching { heavyChannel.conn?.process?.destroy() }
    }

    /** 关闭长连接并终止所有长驻流；采集停止 / 重连前调用，避免残留 su 子进程。 */
    fun close() {
        interruptRequested = true
        closeStreams()
        mainChannel.lock.withLock { resetLocked(mainChannel) }
        heavyChannel.lock.withLock { resetLocked(heavyChannel) }
        availability = null
        mainChannel.nextRetryAt = 0L
        heavyChannel.nextRetryAt = 0L
        detail = "连接已关闭"
    }

    // ---------------------------------------------------------------- 连接管理

    private fun ensureConnectedLocked(channel: ChannelState): Boolean {
        channel.conn?.let { c -> if (c.process.isAlive && !c.dead) return true }
        resetLocked(channel)
        if (SystemClock.elapsedRealtime() < channel.nextRetryAt) return false

        val attempts = listOf(
            arrayOf("su", "-c", "sh"),
            arrayOf("su", "-c", "sh", "-"),
            arrayOf("su"),
        )
        for (argv in attempts) {
            val p = try {
                ProcessBuilder(*argv).redirectErrorStream(false).start()
            } catch (t: Throwable) {
                Log.w(TAG, "spawn ${argv.joinToString(" ")} failed", t)
                continue
            }
            installConnectionLocked(channel, p)
            val handshake = runRawLocked(channel, HANDSHAKE_COMMAND, HANDSHAKE_TIMEOUT_MS)
            if (handshake == null) {
                // 握手超时：大概率是提权弹窗未处理，换 argv 变体只会重复弹窗 → 直接失败退避
                resetLocked(channel)
                break
            }
            if (handshake.dead) {
                resetLocked(channel)
                continue
            }
            if (handshake.stdout.contains("uid=0(root)")) {
                channel.timeoutSupported = handshake.stdout.contains("$TIMEOUT_MARK 1")
                channel.mode = argv.joinToString(" ")
                channel.nextRetryAt = 0L
                interruptRequested = false
                availability = true
                if (channel === mainChannel) {
                    val ctx = Regex("""context=(\S+)""").find(handshake.stdout)?.groupValues?.get(1)
                    detail = "uid=0(root)，context=${ctx ?: "unknown"}"
                }
                Log.i(
                    TAG,
                    "root shell connected (${channel.name}) via ${channel.mode}, timeout=${channel.timeoutSupported}",
                )
                return true
            }
            // 连上了但不是 root（被拒绝）：尝试其它变体没有意义
            resetLocked(channel)
            break
        }
        channel.nextRetryAt = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS
        availability = false
        val stderr = stderrSnapshot()
        detail = "无法建立 root 连接：${stderr.ifBlank { "su 不可用或无授权" }.take(200)}"
        Log.w(TAG, "root handshake failed (${channel.name}): ${stderr.take(160)}")
        return false
    }

    private fun installConnectionLocked(channel: ChannelState, process: Process) {
        val queue = LinkedBlockingQueue<String>()
        val out = BufferedWriter(OutputStreamWriter(process.outputStream))
        val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
        val stderrReader = BufferedReader(InputStreamReader(process.errorStream))
        val reader = Thread({
            try {
                while (true) {
                    val line = stdoutReader.readLine() ?: break
                    queue.put(line)
                }
            } catch (_: Throwable) {
                // 进程被杀死 / 管道关闭
            } finally {
                runCatching { queue.put(EOF_MARK) }
            }
        }, "RootShell-${channel.name}-stdout").apply { isDaemon = true; start() }
        val stderr = Thread({
            runCatching {
                while (true) {
                    val line = stderrReader.readLine() ?: break
                    addStderr(line)
                }
            }
        }, "RootShell-${channel.name}-stderr").apply { isDaemon = true; start() }
        channel.conn = Connection(process, out, queue, reader, stderr)
    }

    // -------------------------------------------------------------- 命令执行

    private fun runCommandLocked(channel: ChannelState, command: String, timeoutMs: Long): Outcome {
        val c = channel.conn
            ?: return Outcome(Result.fail("root 连接未建立"), false)
        if (!c.process.isAlive || c.dead) {
            resetLocked(channel)
            return Outcome(Result.fail("root 进程已退出"), false)
        }
        val sanitized = command.replace("\n", " ; ")
        val useTimeout = channel.timeoutSupported
        val shellBody = if (useTimeout) {
            val seconds = String.format(Locale.US, "%.2f", (timeoutMs - 600L).coerceAtLeast(500L) / 1000.0)
            "timeout -k 1s ${seconds}s sh -c ${shellQuote(sanitized)}"
        } else {
            sanitized
        }
        val nonce = newNonce()
        val endPrefix = "$END_PREFIX$nonce "
        drainQueueLocked(c)
        try {
            // 先 echo 空行：命令最后一行没有换行时也不会把哨兵拼到同一行
            c.stdin.write("$shellBody ; __pr_rc=\$?; echo ; echo \"$END_PREFIX$nonce \$__pr_rc\"\n")
            c.stdin.flush()
        } catch (t: Throwable) {
            resetLocked(channel)
            return Outcome(Result.fail("写入 root 命令失败: ${t.message}"), false)
        }

        val grace = if (useTimeout) DRAIN_GRACE_MS else 250L
        val deadline = SystemClock.elapsedRealtime() + timeoutMs + grace
        val sb = StringBuilder(8 * 1024)
        var code: Int? = null
        var dead = false
        while (true) {
            if (interruptRequested) {
                resetLocked(channel)
                return Outcome(Result.fail("命令被中断"), false)
            }
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain <= 0) break
            val line = try {
                // 200ms 粒度检查中断标志，停止采集时无需等待整条命令完成
                c.queue.poll(minOf(remain, 200L), TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } ?: continue
            if (line == EOF_MARK) {
                dead = true
                break
            }
            if (line.startsWith(endPrefix)) {
                val rest = line.substring(endPrefix.length).trim()
                val parsed = if (rest.isNotEmpty() && rest.all { it.isDigit() || it == '-' }) rest.toIntOrNull() else null
                if (parsed != null) {
                    code = parsed
                    break
                }
            }
            if (sb.length < MAX_OUTPUT_CHARS) sb.append(line).append('\n')
        }

        if (code != null) {
            val timedOut = code == 124
            return Outcome(
                Result(
                    ok = code == 0,
                    stdout = sb.toString(),
                    stderr = if (timedOut) "命令超时($timeoutMs ms)，已由 shell 侧 kill" else stderrSnapshot(),
                ),
                true,
            )
        }

        // 没等到哨兵：连接确实失效 / shell 卡死，只能重建连接（普通超时走不到这里）
        resetLocked(channel)
        val reason = when {
            dead -> "root 进程意外退出"
            useTimeout -> "命令在 shell 侧超时后仍未返回哨兵"
            else -> "命令超时(${timeoutMs}ms)"
        }
        Log.w(TAG, "command lost sync (${channel.name}), reset: $reason; cmd=${sanitized.take(100)}")
        return Outcome(
            Result(
                ok = false,
                stdout = sb.toString(),
                stderr = reason,
            ),
            false,
        )
    }

    /** 握手专用：不包 timeout（此时还不知道设备是否支持 timeout），带随机哨兵。 */
    private fun runRawLocked(channel: ChannelState, command: String, timeoutMs: Long): RawRun? {
        val c = channel.conn ?: return null
        val nonce = newNonce()
        val endPrefix = "$END_PREFIX$nonce "
        drainQueueLocked(c)
        try {
            c.stdin.write("$command ; __pr_rc=\$?; echo ; echo \"$END_PREFIX$nonce \$__pr_rc\"\n")
            c.stdin.flush()
        } catch (t: Throwable) {
            c.dead = true
            return RawRun("", true)
        }
        val sb = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remain = deadline - SystemClock.elapsedRealtime()
            if (remain <= 0) return null
            val line = try {
                c.queue.poll(remain, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } ?: continue
            if (line == EOF_MARK) {
                c.dead = true
                return RawRun(sb.toString(), true)
            }
            if (line.startsWith(endPrefix)) {
                val rest = line.substring(endPrefix.length).trim()
                if (rest.isNotEmpty() && rest.all { it.isDigit() || it == '-' }) {
                    return RawRun(sb.toString(), false)
                }
            }
            sb.append(line).append('\n')
        }
    }

    private fun drainQueueLocked(c: Connection) {
        while (c.queue.poll() != null) {
            // 丢弃上一条命令（尤其超时命令）留下的残余输出，保证 per-command 同步
        }
    }

    private fun resetLocked(channel: ChannelState) {
        val c = channel.conn ?: return
        channel.conn = null
        runCatching { c.stdin.close() }
        runCatching { c.process.destroy() }
        runCatching { if (c.process.isAlive) c.process.destroyForcibly() }
        // reset 前 join reader 线程，避免旧 reader 继续往新命令的队列写数据
        c.readerThread.interrupt()
        runCatching { c.readerThread.join(READER_JOIN_MS) }
        runCatching { c.stderrThread.join(100) }
        c.queue.clear()
    }

    // -------------------------------------------------------------------- 工具

    private fun newNonce(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
    }

    /** POSIX 单引号转义：把命令整体交给内层 `sh -c`。 */
    private fun shellQuote(command: String): String =
        "'" + command.replace("'", "'\\''") + "'"

    private fun addStderr(line: String) {
        synchronized(stderrGuard) {
            stderrTail.addLast(line)
            while (stderrTail.size > 40) stderrTail.removeFirst()
        }
    }

    private fun stderrSnapshot(): String = synchronized(stderrGuard) {
        stderrTail.toList().takeLast(8).joinToString("\n")
    }

    private const val HANDSHAKE_COMMAND =
        "id ; command -v timeout >/dev/null 2>&1 && echo \"__PR_TIMEOUT__ 1\" || echo \"__PR_TIMEOUT__ 0\""
}
