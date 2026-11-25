package io.github.hoangtu.tomcat

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.testFramework.JavaProjectTestCase
import io.github.hoangtu.tomcat.artifact.ExplodedWarArtifactType
import io.github.hoangtu.tomcat.run.TomcatCatalinaBase
import io.github.hoangtu.tomcat.run.TomcatCommandLineState
import io.github.hoangtu.tomcat.run.TomcatConfigurationType
import io.github.hoangtu.tomcat.run.TomcatDeployment
import io.github.hoangtu.tomcat.run.TomcatRunConfiguration
import io.github.hoangtu.tomcat.server.TomcatCommunitySettings
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import io.github.hoangtu.tomcat.run.ResolvedDeployment
import io.github.hoangtu.tomcat.run.RunningTomcat
import io.github.hoangtu.tomcat.run.TomcatInstances

/**
 * Starts a real Tomcat with the command line produced by the run configuration.
 * Requires the `tomcat.home` system property (TOMCAT_HOME environment variable in Gradle); skipped otherwise.
 */
class TomcatLaunchTest : JavaProjectTestCase() {
    private var sdk: Sdk? = null

    override fun tearDown() {
        try {
            sdk?.let { s -> WriteAction.runAndWait<Exception> { ProjectJdkTable.getInstance().removeJdk(s) } }
        } finally {
            super.tearDown()
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun get(url: String): Pair<Int, String>? = try {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1000
        connection.readTimeout = 2000
        val code = connection.responseCode
        code to (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
    } catch (_: Exception) {
        null
    }

    fun testRunsTomcatAndServesExplodedArtifact() {
        val tomcatHome = System.getProperty("tomcat.home")
        if (tomcatHome.isNullOrBlank()) {
            println("tomcat.home is not set - skipping")
            return
        }

        // project JDK = the JDK running the tests
        sdk = JavaSdk.getInstance().createJdk("launch-test-jdk", System.getProperty("java.home"), false)
        WriteAction.runAndWait<Exception> {
            ProjectJdkTable.getInstance().addJdk(sdk!!, testRootDisposable)
            ProjectRootManager.getInstance(project).projectSdk = sdk
        }

        // a "built" exploded artifact
        val temp = createTempDirectory().toPath()
        for (name in listOf("app", "other")) {
            val exploded = temp.resolve(name)
            Files.createDirectories(exploded.resolve("WEB-INF"))
            Files.writeString(exploded.resolve("index.html"), if (name == "app") "Hello from IntelliJ Community" else "other")
            Files.writeString(exploded.resolve("WEB-INF/web.xml"), "<web-app/>")
            WriteAction.runAndWait<Exception> {
                val model = ArtifactManager.getInstance(project).createModifiableModel()
                model.addArtifact("$name:war exploded", ExplodedWarArtifactType.getInstance()).outputPath =
                    exploded.toString().replace(File.separatorChar, '/')
                model.commit()
            }
        }

        val serverName = TomcatCommunitySettings.getInstance().register(Path.of(tomcatHome))
        val factory = TomcatConfigurationType.getInstance().configurationFactories.single()
        val configuration = factory.createTemplateConfiguration(project) as TomcatRunConfiguration
        configuration.name = "launch-test"
        configuration.serverName = serverName
        configuration.httpPort = freePort()
        configuration.jmxPort = freePort()
        configuration.deployments = listOf(TomcatDeployment("app:war exploded", "/app"), TomcatDeployment("other:war exploded", "/other"))
        // custom log directory, with logs of a "previous run" that must be removed on start
        val logDir = temp.resolve("tomcat-logs")
        Files.createDirectories(logDir)
        Files.writeString(logDir.resolve("catalina.2000-01-01.log"), "old run")
        Files.writeString(logDir.resolve("keep-me.txt"), "not a Tomcat log")
        configuration.logDirectory = logDir.toString()
        configuration.clearLogsOnStart = true
        configuration.checkConfiguration()

        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(project, executor, configuration).build()
        val state = configuration.getState(executor, environment) as TomcatCommandLineState
        val commandLine = state.javaParameters.toCommandLine()
        val handler = OSProcessHandler(commandLine)
        val output = StringBuffer()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        val process = handler.process
        try {
            val url = "http://localhost:${configuration.httpPort}/app/index.html"
            var response: Pair<Int, String>? = null
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline && process.isAlive) {
                response = get(url)
                if (response?.first == 200) break
                Thread.sleep(500)
            }
            assertEquals("Tomcat output:\n$output", 200, response?.first)
            assertEquals("Hello from IntelliJ Community", response!!.second.trim())

            // logs are written to the custom directory in real time and the old ones are gone
            assertFalse(Files.exists(logDir.resolve("catalina.2000-01-01.log")))
            assertTrue(Files.exists(logDir.resolve("keep-me.txt")))
            val catalinaLog = Files.list(logDir).use { files -> files.toList() }.firstOrNull { it.fileName.toString().startsWith("catalina.") }
            assertNotNull("catalina log in $logDir", catalinaLog)
            assertTrue(Files.readString(catalinaLog!!).contains("Server startup in"))
            assertTrue(Files.readString(TomcatCatalinaBase.directoryFor(configuration).resolve("conf/Catalina/localhost/app.xml")).contains("<Manager pathname=\"\"/>"))

            assertEquals("other", get("http://localhost:${configuration.httpPort}/other/index.html")?.second?.trim())
            val base = TomcatCatalinaBase.directoryFor(configuration)
            assertTrue(Files.isRegularFile(base.resolve("conf/Catalina/localhost/app.xml")))
            assertTrue(Files.isRegularFile(base.resolve("conf/Catalina/localhost/other.xml")))

            // Redeploy only one application ("update a single module"): the other one must not be reloaded
            val deployments = listOf(
                ResolvedDeployment("/app", temp.resolve("app").toString(), "app:war exploded"),
                ResolvedDeployment("/other", temp.resolve("other").toString(), "other:war exploded"),
            )
            val instance = RunningTomcat(configuration, TomcatCatalinaBase.Prepared(base, "localhost", "Catalina"), deployments, handler, false)
            TomcatInstances.getInstance(project).register(instance)
            TomcatInstances.getInstance(project).reloadContexts(instance, listOf(deployments[0]), silent = true)
            val deadline2 = System.currentTimeMillis() + 15_000
            while (!output.contains("Reloading Context with name [/app] is completed") && System.currentTimeMillis() < deadline2) Thread.sleep(200)
            assertTrue("Tomcat output:\n$output", output.contains("Reloading Context with name [/app] is completed"))
            assertFalse("only /app must be reloaded", output.contains("Reloading Context with name [/other]"))
            assertEquals(200, get(url)?.first)
            println(output)
        } finally {
            handler.destroyProcess()
            process.waitFor()
        }
    }
}
