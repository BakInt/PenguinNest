package org.melodist.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 启动兜底与崩溃落盘。
 *
 * 背景：release 包的 proguard 规则里有
 * `-assumenosideeffects java.lang.Throwable { printStackTrace() }` 与 `Log { v, d, i }`，
 * 真机崩溃时既没有 logcat 堆栈也没有可读日志，用户只能反馈"打开就崩"。
 *
 * 本对象做两件事：
 * 1. [install] 注册默认 `UncaughtExceptionHandler`，把未捕获异常的完整堆栈写入
 *    `filesDir/crash-last.txt`（只保留最近一次），再交回原有 handler 终止进程；
 * 2. [record] 供启动链路里各处 `runCatching` 记录"被降级吞掉的启动异常"，写入
 *    `filesDir/startup-degraded.txt`（保留最近 [MAX_ENTRY_BYTES] 字节）。
 *
 * 两个文件都可以在「设置 → 关于 → 诊断日志」里直接查看，不需要连 adb。
 */
object CrashGuard {
    private const val TAG = "CrashGuard"
    private const val CRASH_FILE = "crash-last.txt"
    private const val STARTUP_FILE = "startup-degraded.txt"

    /** 「本次启动进行中」标记文件：走到首帧后删除；仍在则说明上次启动没走完就死了。 */
    private const val STARTUP_MARKER_FILE = "startup-in-progress"

    /** 启动降级日志的体积上限，超出后保留尾部（避免无限增长）。 */
    private const val MAX_ENTRY_BYTES = 64 * 1024

    /** 渲染 cause 链的最大深度，避免自引用/超长链拖死崩溃处理路径。 */
    private const val MAX_CAUSE_DEPTH = 8

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    /**
     * 注册崩溃落盘。幂等，可重复调用。
     *
     * 应尽早在 `Application.onCreate` 首行调用，这样连 Application 自身的初始化崩溃也能被记录。
     */
    fun install(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrash(app, thread, error)
            } catch (ignored: Throwable) {
                // 落盘失败绝不能干扰原有崩溃流程
                Log.w(TAG, "Failed to persist crash log", ignored)
            }
            // 交回系统默认 handler（会杀掉进程）；不委托会让进程卡在损坏状态。
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * 记录一处"启动降级"：某个初始化步骤抛异常被兜住，应用继续启动但该功能可能不可用。
     *
     * @param step 步骤标识，建议用 `类名.方法名`，例如 `PlaybackManager.init`
     * @param error 被兜住的异常
     */
    fun record(step: String, error: Throwable) {
        val app = appContext ?: return
        try {
            val text = buildString {
                appendLine("[$step] ${timestamp()} ${error.javaClass.name}: ${error.message}")
                appendLine(stackTraceOf(error))
                appendLine("---")
            }
            appendToFile(File(app.filesDir, STARTUP_FILE), text)
        } catch (ignored: Throwable) {
            Log.w(TAG, "Failed to record startup degradation: $step", ignored)
        }
    }

    /**
     * 标记「本次启动开始」。
     *
     * 与 [markStartupReady] 配对使用：两者之间的时间段就是"启动窗口"。若进程在窗口内死亡
     * （首帧还没画出来就崩），标记文件会残留到下次启动，[startupCrashReport] 据此把上次的
     * 崩溃栈直接摆到用户面前——**不需要连 adb 也能拿到崩溃原因**。
     */
    fun markStartupBegin(context: Context) {
        try {
            File(context.applicationContext.filesDir, STARTUP_MARKER_FILE).writeText(timestamp())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark startup begin", e)
        }
    }

    /** 标记「本次启动已成功画出首帧」，删除进行中标记。 */
    fun markStartupReady(context: Context) {
        try {
            File(context.applicationContext.filesDir, STARTUP_MARKER_FILE).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark startup ready", e)
        }
    }

    /**
     * 上次启动是否"还没画出首帧就崩了"。
     *
     * 判定：`startup-in-progress` 标记仍在，且 `crash-last.txt` 的写入时间不早于该标记。
     *
     * @return 上次崩溃文本；不是"启动即崩"则返回 null
     */
    fun startupCrashReport(context: Context): String? {
        val dir = context.applicationContext.filesDir
        return try {
            val marker = File(dir, STARTUP_MARKER_FILE)
            if (!marker.isFile) return null
            val crash = File(dir, CRASH_FILE)
            if (!crash.isFile || crash.length() == 0L) return null
            if (crash.lastModified() < marker.lastModified()) return null
            readFile(crash)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read startup crash report", e)
            null
        }
    }

    /** 把异常渲染成可读堆栈文本（不依赖 `printStackTrace`，见 [stackTraceOf]）。 */
    fun renderThrowable(error: Throwable): String = stackTraceOf(error)

    /** 最近一次未捕获异常的堆栈；没有则返回 null。 */
    fun lastCrash(context: Context): String? = readFile(File(context.applicationContext.filesDir, CRASH_FILE))

    /** 启动降级记录；没有则返回 null。 */
    fun startupDegraded(context: Context): String? = readFile(File(context.applicationContext.filesDir, STARTUP_FILE))

    /** 汇总给「设置 → 关于 → 诊断日志」展示的文本；两者都没有时返回 null。 */
    fun readDiagnostics(context: Context): String? {
        val crash = lastCrash(context)
        val degraded = startupDegraded(context)
        if (crash.isNullOrBlank() && degraded.isNullOrBlank()) return null
        return buildString {
            if (!crash.isNullOrBlank()) {
                appendLine("===== 最近一次崩溃（crash-last.txt） =====")
                appendLine(crash.trimEnd())
                appendLine()
            }
            if (!degraded.isNullOrBlank()) {
                appendLine("===== 启动降级记录（startup-degraded.txt） =====")
                appendLine(degraded.trimEnd())
            }
        }.trimEnd()
    }

    /** 清空两个诊断文件。 */
    fun clearLogs(context: Context) {
        val dir = context.applicationContext.filesDir
        try {
            File(dir, CRASH_FILE).delete()
            File(dir, STARTUP_FILE).delete()
            File(dir, STARTUP_MARKER_FILE).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear diagnostics", e)
        }
    }

    private fun writeCrash(
        context: Context,
        thread: Thread,
        error: Throwable,
    ) {
        val text = buildString {
            appendLine("时间: ${timestamp()}")
            appendLine("线程: ${thread.name}")
            appendLine("进程: ${context.packageName}")
            appendLine()
            appendLine(stackTraceOf(error))
        }
        synchronized(lock) {
            File(context.filesDir, CRASH_FILE).writeText(text)
        }
    }

    private fun appendToFile(
        file: File,
        text: String,
    ) {
        synchronized(lock) {
            file.appendText(text)
            if (file.length() > MAX_ENTRY_BYTES) {
                val tail = file.readText().takeLast(MAX_ENTRY_BYTES / 2)
                file.writeText("（较早的记录已截断）\n$tail")
            }
        }
    }

    private fun readFile(file: File): String? =
        try {
            if (file.isFile && file.length() > 0L) file.readText() else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ${file.name}", e)
            null
        }

    /**
     * 手工渲染堆栈，**不能用** [Throwable.printStackTrace]：
     * release 的 proguard 规则含 `-assumenosideeffects java.lang.Throwable { printStackTrace() }`，
     * R8 会把该调用整条删掉，落盘的文件就只剩表头。
     */
    private fun stackTraceOf(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (depth == 0) {
                appendLine("${current.javaClass.name}: ${current.message}")
            } else {
                appendLine("Caused by: ${current.javaClass.name}: ${current.message}")
            }
            current.stackTrace.forEach { element -> appendLine("\tat $element") }
            current = if (current.cause === current) null else current.cause
            depth++
        }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
