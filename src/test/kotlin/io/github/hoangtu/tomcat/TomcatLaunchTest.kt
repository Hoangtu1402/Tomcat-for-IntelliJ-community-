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
import javax.management.ObjectName
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL
import kotlin.concurrent.thread

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
        val exploded = createTempDirectory().toPath().resolve("app")
        Files.createDirectories(exploded.resolve("WEB-INF"))
        Files.writeString(exploded.resolve("index.html"), "Hello from IntelliJ Community")
        Files.writeString(exploded.resolve("WEB-INF/web.xml"), "<web-app/>")
        WriteAction.runAndWait<Exception> {
            val model = ArtifactManager.getInstance(project).createModifiableModel()
            model.addArtifact("app:war exploded", ExplodedWarArtifactType.getInstance()).outputPath =
                exploded.toString().replace(File.separatorChar, '/')
            model.commit()
        }

        val serverName = TomcatCommunitySettings.getInstance().register(Path.of(tomcatHome))
        val factory = TomcatConfigurationType.getInstance().configurationFactories.single()
        val configuration = factory.createTemplateConfiguration(project) as TomcatRunConfiguration
        configuration.name = "launch-test"
        configuration.serverName = serverName
        configuration.httpPort = freePort()
        configuration.jmxPort = freePort()
        configuration.deployments = listOf(TomcatDeployment("app:war exploded", "/app"))
        configuration.checkConfiguration()

        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(project, executor, configuration).build()
        val state = configuration.getState(executor, environment) as TomcatCommandLineState
        val commandLine = state.javaParameters.toCommandLine()
        val process = commandLine.createProcess()
        val output = StringBuffer()
        thread(isDaemon = true) { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        thread(isDaemon = true) { process.errorStream.bufferedReader().forEachLine { output.appendLine(it) } }
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

            // JMX is available, so the "Redeploy" update action can reload the context
            val base = TomcatCatalinaBase.directoryFor(configuration)
            assertTrue(Files.isRegularFile(base.resolve("conf/Catalina/localhost/app.xml")))
            JMXConnectorFactory.connect(JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:${configuration.jmxPort}/jmxrmi")).use { c ->
                val connection = c.mBeanServerConnection
                val module = connection.queryNames(ObjectName("Catalina:j2eeType=WebModule,*"), null)
                    .single { it.getKeyProperty("name") == "//localhost/app" }
                connection.invoke(module, "reload", null, null)
            }
            Thread.sleep(1000)
            assertEquals(200, get(url)?.first)
            println(output)
        } finally {
            process.destroy()
            process.waitFor()
        }
    }
}
