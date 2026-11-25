package io.github.hoangtu.tomcat.run

import com.intellij.execution.CantRunException
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.util.JavaParametersUtil
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.packaging.artifacts.ArtifactManager
import java.util.concurrent.atomic.AtomicBoolean

/** Starts Tomcat (`org.apache.catalina.startup.Bootstrap start`) with a dedicated CATALINA_BASE. Debugging works out of the box. */
class TomcatCommandLineState(environment: ExecutionEnvironment, private val configuration: TomcatRunConfiguration) :
    JavaCommandLineState(environment) {

    private var prepared: TomcatCatalinaBase.Prepared? = null
    private var resolved: List<ResolvedDeployment> = emptyList()

    override fun isReadActionRequired(): Boolean = false

    @Throws(ExecutionException::class)
    override fun createJavaParameters(): JavaParameters {
        val project = configuration.project
        val installation = configuration.requireInstallation()
        ReadAction.compute<String?, RuntimeException> { TomcatJdk.incompatibility(configuration) }?.let { throw CantRunException(it) }

        resolved = ReadAction.compute<List<ResolvedDeployment>, ExecutionException> {
            val manager = ArtifactManager.getInstance(project)
            configuration.deployments.map { deployment ->
                deployment.externalPath?.let { path ->
                    if (!java.io.File(path).exists()) throw ExecutionException("External source '$path' does not exist")
                    return@map ResolvedDeployment(TomcatContexts.normalize(deployment.contextPath), path, deployment.artifactName, external = true)
                }
                val artifact = manager.findArtifact(deployment.artifactName)
                    ?: throw ExecutionException("Artifact '${deployment.artifactName}' does not exist")
                val output = artifact.outputFilePath
                    ?: throw ExecutionException("Output path is not specified for artifact '${artifact.name}'")
                ResolvedDeployment(TomcatContexts.normalize(deployment.contextPath), output, artifact.name)
            }
        }
        val base = TomcatCatalinaBase.prepare(configuration, installation, resolved)
        prepared = base

        val params = JavaParameters()
        val jdk = ReadAction.compute<com.intellij.openapi.projectRoots.Sdk?, CantRunException> {
            if (configuration.useAlternativeJre && configuration.alternativeJrePath.isNotBlank())
                JavaParametersUtil.createProjectJdk(project, configuration.alternativeJrePath)
            else ProjectRootManager.getInstance(project).projectSdk
        } ?: throw CantRunException.noJdkConfigured()
        params.jdk = jdk

        params.mainClass = "org.apache.catalina.startup.Bootstrap"
        params.programParametersList.add("start")
        val baseJuli = base.base.resolve("bin/tomcat-juli.jar")
        params.classPath.add(installation.bootstrapJar.toString())
        params.classPath.add(if (baseJuli.toFile().isFile) baseJuli.toString() else installation.juliJar.toString())
        params.workingDirectory = base.base.toString()

        val vm = params.vmParametersList
        vm.addParametersString(configuration.vmOptions)
        vm.defineProperty("catalina.home", installation.home.toString())
        vm.defineProperty("catalina.base", base.base.toString())
        vm.defineProperty("java.io.tmpdir", base.base.resolve("temp").toString())
        vm.defineProperty("java.util.logging.config.file", base.base.resolve("conf/logging.properties").toString())
        vm.defineProperty("java.util.logging.manager", "org.apache.juli.ClassLoaderLogManager")
        vm.defineProperty("jdk.tls.ephemeralDHKeySize", "2048")
        vm.defineProperty("org.apache.catalina.startup.EXIT_ON_INIT_FAILURE", "true")
        // JMX (used to redeploy applications without restarting the server)
        vm.defineProperty("com.sun.management.jmxremote", "")
        vm.defineProperty("com.sun.management.jmxremote.port", configuration.jmxPort.toString())
        vm.defineProperty("com.sun.management.jmxremote.rmi.port", configuration.jmxPort.toString())
        vm.defineProperty("com.sun.management.jmxremote.authenticate", "false")
        vm.defineProperty("com.sun.management.jmxremote.ssl", "false")
        vm.defineProperty("com.sun.management.jmxremote.host", "127.0.0.1")
        vm.defineProperty("java.rmi.server.hostname", "127.0.0.1")

        val version = JavaSdk.getInstance().getVersion(jdk)
        if (version == null || version.isAtLeast(JavaSdkVersion.JDK_1_9)) {
            // the same options catalina.sh / catalina.bat add (JDK_JAVA_OPTIONS)
            listOf("java.base/java.lang", "java.base/java.io", "java.base/java.util", "java.base/java.util.concurrent", "java.rmi/sun.rmi.transport")
                .forEach { vm.add("--add-opens=$it=ALL-UNNAMED") }
        }

        params.env = configuration.envs.envs
        params.isPassParentEnvs = configuration.envs.isPassParentEnvs
        return params
    }

    @Throws(ExecutionException::class)
    override fun startProcess(): com.intellij.execution.process.OSProcessHandler {
        val handler = super.startProcess()
        val base = prepared ?: throw ExecutionException("Tomcat base directory was not prepared")
        if (base.warnings.isNotEmpty()) {
            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    base.warnings.forEach { handler.notifyTextAvailable("Warning: $it\n", com.intellij.execution.process.ProcessOutputTypes.STDERR) }
                }
            })
        }
        val isDebug = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID
        val project = configuration.project
        val logTabs = configuration.allLogFiles.filter { it.isEnabled && !it.pathPattern.isNullOrBlank() }.map { (it.name ?: "Log") to it.pathPattern!! }
        val output = TomcatServerOutput(project, handler, logTabs, Charsets.UTF_8)
        val instances = TomcatInstances.getInstance(project)
        instances.replaceOutput(configuration, output)
        val instance = RunningTomcat(configuration, base, resolved, handler, isDebug, output = output)
        instances.register(instance)
        TomcatServicesContributor.select(project, configuration)

        if (configuration.openBrowser && configuration.browserUrl.isNotBlank()) {
            val opened = AtomicBoolean(false)
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (event.text.contains("Server startup in") && opened.compareAndSet(false, true)) {
                        val browser = configuration.browserId?.let { com.intellij.ide.browsers.WebBrowserManager.getInstance().findBrowserById(it) }
                        if (browser != null) com.intellij.ide.browsers.BrowserLauncher.instance.browse(configuration.browserUrl, browser, project)
                        else BrowserUtil.browse(configuration.browserUrl)
                    }
                }
            })
        }
        return handler
    }

    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val result = super.execute(executor, runner)
        if (result is DefaultExecutionResult) {
            ActionManager.getInstance().getAction(UpdateTomcatApplicationAction.ID)?.let { result.setRestartActions(it) }
        }
        return result
    }
}
