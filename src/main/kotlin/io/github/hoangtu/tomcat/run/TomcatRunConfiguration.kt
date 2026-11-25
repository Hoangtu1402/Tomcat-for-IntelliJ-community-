package io.github.hoangtu.tomcat.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LogFileOptions
import com.intellij.execution.configurations.ModuleRunProfile
import com.intellij.execution.configurations.PredefinedLogFile
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.module.Module
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTaskProvider
import io.github.hoangtu.tomcat.artifact.ExplodedWarArtifactType
import io.github.hoangtu.tomcat.artifact.WebArtifacts
import io.github.hoangtu.tomcat.server.TomcatCommunitySettings
import io.github.hoangtu.tomcat.server.TomcatInstallation
import io.github.hoangtu.tomcat.server.TomcatServersConfigurable
import org.jdom.Element

/** What to do when the user invokes "Update Tomcat Application" (or when the IDE frame loses focus). */
enum class UpdatePolicy(val id: String, val presentableName: String) {
    NOTHING("do-nothing", "Do nothing"),
    UPDATE_RESOURCES("update-resources", "Update resources"),
    UPDATE_CLASSES_AND_RESOURCES("hot-swap-classes", "Update classes and resources"),
    REDEPLOY("redeploy-artifacts", "Redeploy"),
    RESTART("restart-server", "Restart server");

    override fun toString(): String = presentableName

    companion object {
        fun byId(id: String?, default: UpdatePolicy): UpdatePolicy = entries.firstOrNull { it.id == id } ?: default
        val forUpdateAction = listOf(UPDATE_RESOURCES, UPDATE_CLASSES_AND_RESOURCES, REDEPLOY, RESTART)
        val forFrameDeactivation = listOf(NOTHING, UPDATE_RESOURCES, UPDATE_CLASSES_AND_RESOURCES)
    }
}

/** An artifact deployed to Tomcat and the context path it is deployed under. */
data class TomcatDeployment(val artifactName: String, val contextPath: String) {
    companion object {
        /** Same default as IntelliJ IDEA Ultimate: "demo:war exploded" -> "/demo_war_exploded". */
        fun defaultContextPath(artifactName: String): String = "/" + artifactName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

class TomcatRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<Any>(project, factory, name), ModuleRunProfile {

    var serverName: String? = null
    var openBrowser: Boolean = true
    var browserUrl: String = "http://localhost:8080/"
    var updatePolicy: UpdatePolicy = UpdatePolicy.REDEPLOY
    var frameDeactivationPolicy: UpdatePolicy = UpdatePolicy.NOTHING
    var vmOptions: String = ""
    var envs: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT
    var useAlternativeJre: Boolean = false
    var alternativeJrePath: String = ""
    var httpPort: Int = 8080
    var jmxPort: Int = 1099
    var deployTomcatApps: Boolean = false
    var deployments: List<TomcatDeployment> = emptyList()

    init {
        addPredefinedLogFile(PredefinedLogFile(LOG_LOCALHOST, true))
        addPredefinedLogFile(PredefinedLogFile(LOG_CATALINA, false))
    }

    val installation: TomcatInstallation? get() = TomcatCommunitySettings.getInstance().findInstallation(serverName)

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = SettingsEditorGroup<TomcatRunConfiguration>().apply {
        addEditor("Server", TomcatServerSettingsEditor(project))
        addEditor("Deployment", TomcatDeploymentSettingsEditor(project))
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = TomcatCommandLineState(environment, this)

    override fun getModules(): Array<Module> = Module.EMPTY_ARRAY

    override fun onNewConfigurationCreated() {
        super.onNewConfigurationCreated()
        val settings = TomcatCommunitySettings.getInstance()
        if (serverName == null) serverName = settings.servers.firstOrNull()?.name
        if (deployments.isEmpty()) {
            val artifact = ArtifactManager.getInstance(project).getArtifactsByType(ExplodedWarArtifactType.getInstance()).minByOrNull { it.name }
                ?: WebArtifacts.all(project).firstOrNull()
            if (artifact != null) {
                val ctx = TomcatDeployment.defaultContextPath(artifact.name)
                deployments = listOf(TomcatDeployment(artifact.name, ctx))
                browserUrl = "http://localhost:$httpPort$ctx/"
                BuildArtifactsBeforeRunTaskProvider.setBuildArtifactBeforeRun(project, this, artifact)
            }
        }
    }

    override fun suggestedName(): String? = serverName?.takeIf { it.isNotBlank() }

    @Throws(RuntimeConfigurationException::class)
    override fun checkConfiguration() {
        val name = serverName
        if (name.isNullOrBlank()) throw serverError("Application server is not specified")
        val installation = installation ?: throw serverError("Application server '$name' is not configured")
        if (!installation.isValid) throw serverError("Tomcat installation '${installation.home}' is invalid or missing")
        if (httpPort !in 1..65535) throw RuntimeConfigurationError("HTTP port must be between 1 and 65535")
        if (jmxPort !in 1..65535) throw RuntimeConfigurationError("JMX port must be between 1 and 65535")
        if (jmxPort == httpPort) throw RuntimeConfigurationError("HTTP and JMX ports must be different")
        val manager = ArtifactManager.getInstance(project)
        for (deployment in deployments) {
            if (manager.findArtifact(deployment.artifactName) == null) {
                throw RuntimeConfigurationError("Artifact '${deployment.artifactName}' does not exist")
            }
        }
        val contexts = deployments.map { TomcatContexts.normalize(it.contextPath) }
        if (contexts.toSet().size != contexts.size) throw RuntimeConfigurationError("Context paths of deployed artifacts must be unique")
        if (deployments.isEmpty() && !deployTomcatApps) throw RuntimeConfigurationWarning("No artifacts marked for deployment")
    }

    private fun serverError(message: String) = RuntimeConfigurationError(message, Runnable {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, TomcatServersConfigurable::class.java)
    })

    override fun getOptionsForPredefinedLogFile(file: PredefinedLogFile): LogFileOptions? {
        val logs = TomcatCatalinaBase.directoryFor(this).resolve("logs").toString()
        return when (file.id) {
            LOG_LOCALHOST -> LogFileOptions("Tomcat Localhost Log", "$logs/localhost.*.log", file.isEnabled, true, false)
            LOG_CATALINA -> LogFileOptions("Tomcat Catalina Log", "$logs/catalina.*.log", file.isEnabled, true, false)
            else -> null
        }
    }

    // ---- persistence (attribute/option names follow IntelliJ IDEA Ultimate where they exist) ----

    override fun readExternal(element: Element) {
        super.readExternal(element)
        serverName = element.getAttributeValue("APPLICATION_SERVER_NAME")
        useAlternativeJre = element.getAttributeValue("ALTERNATIVE_JRE_ENABLED")?.toBoolean() ?: false
        alternativeJrePath = element.getAttributeValue("ALTERNATIVE_JRE_PATH") ?: ""
        openBrowser = JDOMExternalizerUtil.readField(element, "OPEN_IN_BROWSER")?.toBoolean() ?: true
        browserUrl = JDOMExternalizerUtil.readField(element, "OPEN_IN_BROWSER_URL") ?: browserUrl
        updatePolicy = UpdatePolicy.byId(JDOMExternalizerUtil.readField(element, "UPDATING_POLICY"), UpdatePolicy.REDEPLOY)
            .let { if (it == UpdatePolicy.NOTHING) UpdatePolicy.REDEPLOY else it }
        frameDeactivationPolicy = UpdatePolicy.byId(JDOMExternalizerUtil.readField(element, "FRAME_DEACTIVATION_UPDATING_POLICY"), UpdatePolicy.NOTHING)
        vmOptions = JDOMExternalizerUtil.readField(element, "COMMON_VM_ARGUMENTS") ?: ""

        deployments = element.getChild("deployment")?.getChildren("artifact")?.mapNotNull { artifact ->
            val artifactName = artifact.getAttributeValue("name") ?: return@mapNotNull null
            val ctx = artifact.getChild("settings")?.let { JDOMExternalizerUtil.readField(it, "CONTEXT_PATH") }
            TomcatDeployment(artifactName, ctx ?: TomcatDeployment.defaultContextPath(artifactName))
        } ?: emptyList()

        val own = element.getChild(OWN_TAG)
        if (own != null) {
            httpPort = JDOMExternalizerUtil.readField(own, "HTTP_PORT")?.toIntOrNull() ?: 8080
            jmxPort = JDOMExternalizerUtil.readField(own, "JMX_PORT")?.toIntOrNull() ?: 1099
            deployTomcatApps = JDOMExternalizerUtil.readField(own, "DEPLOY_TOMCAT_APPS")?.toBoolean() ?: false
            envs = EnvironmentVariablesData.readExternal(own)
        } else {
            // Configuration created by IntelliJ IDEA Ultimate: take the port from the browser URL.
            httpPort = Regex("^https?://[^/:]+:(\\d+)").find(browserUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 8080
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        serverName?.let { element.setAttribute("APPLICATION_SERVER_NAME", it) }
        element.setAttribute("ALTERNATIVE_JRE_ENABLED", useAlternativeJre.toString())
        if (alternativeJrePath.isNotBlank()) element.setAttribute("ALTERNATIVE_JRE_PATH", alternativeJrePath)
        JDOMExternalizerUtil.writeField(element, "OPEN_IN_BROWSER", openBrowser.toString())
        JDOMExternalizerUtil.writeField(element, "OPEN_IN_BROWSER_URL", browserUrl)
        JDOMExternalizerUtil.writeField(element, "UPDATING_POLICY", updatePolicy.id)
        JDOMExternalizerUtil.writeField(element, "FRAME_DEACTIVATION_UPDATING_POLICY", frameDeactivationPolicy.id)
        if (vmOptions.isNotBlank()) JDOMExternalizerUtil.writeField(element, "COMMON_VM_ARGUMENTS", vmOptions)

        if (deployments.isNotEmpty()) {
            val deploymentTag = Element("deployment")
            for (d in deployments) {
                val settings = Element("settings")
                JDOMExternalizerUtil.writeField(settings, "CONTEXT_PATH", d.contextPath)
                deploymentTag.addContent(Element("artifact").setAttribute("name", d.artifactName).addContent(settings))
            }
            element.addContent(deploymentTag)
        }

        val own = Element(OWN_TAG)
        JDOMExternalizerUtil.writeField(own, "HTTP_PORT", httpPort.toString())
        JDOMExternalizerUtil.writeField(own, "JMX_PORT", jmxPort.toString())
        JDOMExternalizerUtil.writeField(own, "DEPLOY_TOMCAT_APPS", deployTomcatApps.toString())
        envs.writeExternal(own)
        element.addContent(own)
    }

    override fun clone(): RunConfiguration {
        val copy = super.clone() as TomcatRunConfiguration
        copy.deployments = deployments.toList()
        return copy
    }

    @Throws(ExecutionException::class)
    fun requireInstallation(): TomcatInstallation {
        val installation = installation ?: throw ExecutionException("Application server '${serverName ?: ""}' is not configured")
        if (!installation.isValid) throw ExecutionException("Tomcat installation '${installation.home}' is invalid")
        return installation
    }

    companion object {
        const val LOG_LOCALHOST = "Tomcat"
        const val LOG_CATALINA = "Tomcat Catalina"
        private const val OWN_TAG = "community-tomcat"
    }
}
