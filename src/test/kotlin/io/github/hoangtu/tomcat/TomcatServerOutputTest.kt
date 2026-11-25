package io.github.hoangtu.tomcat

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hoangtu.tomcat.run.TomcatServerOutput
import java.nio.file.Files

class TomcatServerOutputTest : BasePlatformTestCase() {
    private fun textOf(console: com.intellij.execution.ui.ConsoleView): String {
        val impl = console as ConsoleViewImpl
        impl.flushDeferredText()
        return impl.text
    }

    /** Windows paths: the glob must be split off without going through Path.of ("Illegal char <*>"). */
    fun testSplitLogPattern() {
        assertEquals("C:\\Users\\me\\tomcat\\logs" to "localhost.*.log",
            io.github.hoangtu.tomcat.run.splitLogPattern("C:\\Users\\me\\tomcat\\logs/localhost.*.log"))
        assertEquals("C:\\logs" to "catalina.*.log", io.github.hoangtu.tomcat.run.splitLogPattern("C:\\logs\\catalina.*.log"))
        assertEquals("/var/log/tomcat" to "localhost.*.log", io.github.hoangtu.tomcat.run.splitLogPattern("/var/log/tomcat/localhost.*.log"))
    }

    fun testOutputBeforeAndAfterConsoleCreationAndLogTail() {
        val logDir = Files.createTempDirectory("tomcat-logs")
        val handler = NopProcessHandler()
        val output = TomcatServerOutput(project, handler, listOf("Tomcat Localhost Log" to "$logDir/localhost.*.log"), Charsets.UTF_8)
        Disposer.register(testRootDisposable, output)
        handler.startNotify()

        handler.notifyTextAvailable("before console\n", ProcessOutputTypes.STDOUT)
        assertNotNull(output.getComponent())
        handler.notifyTextAvailable("after console\n", ProcessOutputTypes.STDERR)

        val (outputConsole, logConsole) = output.consolesForTests()
        val text = textOf(outputConsole)
        assertTrue(text, text.contains("before console") && text.contains("after console"))

        // the log tab follows the file in real time
        val log = logDir.resolve("localhost.2026-01-01.log")
        Files.writeString(log, "first line\n")
        val deadline = System.currentTimeMillis() + 10_000
        while (!textOf(logConsole).contains("first line") && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(100)
        }
        assertTrue(textOf(logConsole).contains("first line"))
        Files.writeString(log, "second line\n", java.nio.file.StandardOpenOption.APPEND)
        val deadline2 = System.currentTimeMillis() + 10_000
        while (!textOf(logConsole).contains("second line") && System.currentTimeMillis() < deadline2) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(100)
        }
        assertTrue(textOf(logConsole).contains("second line"))
        handler.destroyProcess()
    }
}
