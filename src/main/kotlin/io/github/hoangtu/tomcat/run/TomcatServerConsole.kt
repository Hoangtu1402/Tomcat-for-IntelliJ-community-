package io.github.hoangtu.tomcat.run

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Output of one Tomcat run, shown in the Services view next to the deployed artifacts (like IntelliJ IDEA Ultimate):
 * an "Output" tab with the process console plus one tab per enabled log file (Tomcat Localhost Log, Catalina Log, ...)
 * that follows the file in real time.
 *
 * The process output is captured from the very first line, so the console can be created later, lazily, on EDT.
 */
class TomcatServerOutput(
    private val project: Project,
    handler: ProcessHandler,
    private val logFiles: List<Pair<String, String>>, // (tab title, path pattern)
    private val logCharset: Charset,
) : Disposable {
    private val lock = Any()
    private val pending = ArrayList<Pair<String, ConsoleViewContentType>>()
    private var pendingSize = 0
    private var console: ConsoleView? = null
    private var component: JComponent? = null
    private val tails = mutableListOf<ScheduledFuture<*>>()
    private val logConsoles = mutableListOf<ConsoleView>()

    @org.jetbrains.annotations.TestOnly
    internal fun consolesForTests(): List<ConsoleView> = listOfNotNull(console) + logConsoles

    init {
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                append(event.text, ConsoleViewContentType.getConsoleViewType(outputType))
            }
        })
    }

    private fun append(text: String, type: ConsoleViewContentType) {
        synchronized(lock) {
            val c = console
            if (c != null) {
                c.print(text, type)
                return
            }
            pending += text to type
            pendingSize += text.length
            while (pendingSize > MAX_PENDING_CHARS && pending.size > 1) {
                pendingSize -= pending.removeAt(0).first.length
            }
        }
    }

    /** The tabbed console component (created on first use, must be called on EDT). */
    fun getComponent(): JComponent {
        component?.let { return it }
        val tabs = JBTabbedPane()
        val output = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(this, output)
        synchronized(lock) {
            pending.forEach { (text, type) -> output.print(text, type) }
            pending.clear()
            pendingSize = 0
            console = output
        }
        tabs.addTab("Output", output.component)
        for ((title, pattern) in logFiles) {
            val logConsole = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            Disposer.register(this, logConsole)
            logConsoles += logConsole
            tabs.addTab(title, logConsole.component)
            val tail = LogTail(pattern, logCharset) { logConsole.print(it, ConsoleViewContentType.NORMAL_OUTPUT) }
            tails += AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(tail::poll, 0, 500, TimeUnit.MILLISECONDS)
        }
        component = tabs
        return tabs
    }

    override fun dispose() {
        tails.forEach { it.cancel(false) }
        synchronized(lock) { console = null }
    }

    private companion object {
        const val MAX_PENDING_CHARS = 4 * 1024 * 1024
    }
}

/** Follows the newest file matching a pattern like `/path/logs/localhost.*.log` ("tail -f"). */
private class LogTail(pattern: String, private val charset: Charset, private val sink: (String) -> Unit) {
    private val dir: Path = Path.of(pattern).parent
    private val matcher = FileSystems.getDefault().getPathMatcher("glob:" + Path.of(pattern).fileName.toString())
    private var current: Path? = null
    private var offset = 0L

    fun poll() {
        try {
            if (!dir.isDirectory()) return
            val newest = dir.listDirectoryEntries().filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }
                .maxByOrNull { Files.getLastModifiedTime(it).toMillis() } ?: return
            if (newest != current) {
                current = newest
                offset = 0
            }
            val size = Files.size(newest)
            if (size < offset) offset = 0 // file was truncated / recreated
            if (size == offset) return
            RandomAccessFile(newest.toFile(), "r").use { file ->
                file.seek(offset)
                val bytes = ByteArray((size - offset).coerceAtMost(1024 * 1024).toInt())
                file.readFully(bytes)
                offset += bytes.size
                sink(String(bytes, charset))
            }
        } catch (_: Exception) {
            // the file may be rotated or locked; try again on the next poll
        }
    }
}
