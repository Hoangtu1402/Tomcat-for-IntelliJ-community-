package io.github.hoangtu.tomcat.run

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.RunManagerListener
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.dashboard.RunDashboardManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.EditConfigurationsDialog
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.services.ServiceEventListener
import com.intellij.execution.services.ServiceViewContributor
import com.intellij.execution.services.ServiceViewDescriptor
import com.intellij.execution.services.SimpleServiceViewDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.util.PropertiesComponent
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.task.ProjectTaskManager
import io.github.hoangtu.tomcat.artifact.WebArtifacts
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent

/**
 * "Tomcat" group of the Services tool window, like the application servers of IntelliJ IDEA Ultimate:
 * ```
 * Tomcat
 *  └─ Tomcat 10.1  :8080  [running]
 *      ├─ shop:war exploded   /shop
 *      └─ admin:war exploded  /admin
 * ```
 * Each deployed artifact has its own node with Update resources / Update classes / Redeploy / Open in browser / Build.
 */
class TomcatServicesContributor : ServiceViewContributor<TomcatServerNode> {
    override fun getViewDescriptor(project: Project): ServiceViewDescriptor =
        SimpleServiceViewDescriptor("Tomcat", TomcatIcons.Tomcat)

    override fun getServices(project: Project): List<TomcatServerNode> =
        RunManager.getInstance(project).getConfigurationSettingsList(TomcatConfigurationType.getInstance())
            .map { TomcatServerNode(project, it) }

    override fun getServiceDescriptor(project: Project, service: TomcatServerNode): ServiceViewDescriptor = service.descriptor()

    companion object {
        /** Selects the server node in the Services view (and shows the tool window), like Ultimate does on start. */
        fun select(project: Project, configuration: TomcatRunConfiguration) {
            ApplicationManager.getApplication().invokeLater({
                val settings = RunManager.getInstance(project).allSettings.firstOrNull { it.configuration === configuration }
                    ?: RunManager.getInstance(project).findConfigurationByName(configuration.name)
                    ?: return@invokeLater
                com.intellij.execution.services.ServiceViewManager.getInstance(project)
                    .select(TomcatServerNode(project, settings), TomcatServicesContributor::class.java, true, false)
            }, project.disposed)
        }

        fun refresh(project: Project) {
            if (project.isDisposed) return
            project.messageBus.syncPublisher(ServiceEventListener.TOPIC)
                .handle(ServiceEventListener.ServiceEvent.createResetEvent(TomcatServicesContributor::class.java))
        }
    }
}

private fun contextUrl(configuration: TomcatRunConfiguration, contextPath: String): String =
    "http://localhost:${configuration.httpPort}" + if (contextPath == "/") "/" else "$contextPath/"

/** Server node = one "Tomcat Server" run configuration; its children are the deployed artifacts. */
class TomcatServerNode(val project: Project, val settings: RunnerAndConfigurationSettings) : ServiceViewContributor<TomcatDeploymentNode> {
    val configuration: TomcatRunConfiguration get() = settings.configuration as TomcatRunConfiguration
    private val running: RunningTomcat? get() = TomcatInstances.getInstance(project).findByConfiguration(configuration)

    override fun equals(other: Any?): Boolean = other is TomcatServerNode && other.settings.uniqueID == settings.uniqueID
    override fun hashCode(): Int = settings.uniqueID.hashCode()

    override fun getViewDescriptor(project: Project): ServiceViewDescriptor = descriptor()

    override fun getServices(project: Project): List<TomcatDeploymentNode> =
        configuration.deployments.map { TomcatDeploymentNode(this, it) }

    override fun getServiceDescriptor(project: Project, service: TomcatDeploymentNode): ServiceViewDescriptor = service.descriptor()

    fun descriptor(): ServiceViewDescriptor = object : ServiceViewDescriptor {
        override fun getId(): String = "tomcat:" + settings.uniqueID

        override fun getPresentation(): ItemPresentation {
            val instance = running
            val status = when {
                instance == null -> ""
                !instance.started -> "  [starting]"
                instance.isDebug -> "  [debugging]"
                else -> "  [running]"
            }
            val icon = if (instance != null) ExecutionUtil.getLiveIndicator(TomcatIcons.Tomcat) else TomcatIcons.Tomcat
            return PresentationData(settings.name, ":${configuration.httpPort}$status", icon, null).apply {
                addText(settings.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                addText("  :${configuration.httpPort}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                if (status.isNotEmpty()) addText(status, SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
        }

        override fun getToolbarActions(): ActionGroup = serverActions()
        override fun getPopupActions(): ActionGroup = serverActions()
        override fun getContentComponent(): JComponent =
            TomcatInstances.getInstance(project).lastOutput(configuration)?.getComponent() ?: serverContent()
    }

    private fun showConsole() {
        val instance = running ?: return
        val descriptor = RunContentManager.getInstance(project).allDescriptors.firstOrNull { it.processHandler == instance.processHandler } ?: return
        val executor = ExecutorRegistry.getInstance().getExecutorById(instance.executorId) ?: DefaultRunExecutor.getRunExecutorInstance()
        RunContentManager.getInstance(project).toFrontRunContent(executor, descriptor)
    }

    private fun serverActions(): ActionGroup = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Run", "Start Tomcat", AllIcons.Actions.Execute) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = running == null
            }
            override fun actionPerformed(e: AnActionEvent) = ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
        })
        add(object : DumbAwareAction("Debug", "Start Tomcat in debug mode", AllIcons.Actions.StartDebugger) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = running == null
            }
            override fun actionPerformed(e: AnActionEvent) =
                ExecutionUtil.runConfiguration(settings, ExecutorRegistry.getInstance().getExecutorById(DefaultDebugExecutor.EXECUTOR_ID)!!)
        })
        add(object : DumbAwareAction("Stop", "Stop Tomcat", AllIcons.Actions.Suspend) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = running != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                running?.processHandler?.destroyProcess()
            }
        })
        add(object : DumbAwareAction("Restart Server", "Restart Tomcat", AllIcons.Actions.Restart) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = running != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                running?.let { TomcatInstances.getInstance(project).update(it, UpdatePolicy.RESTART) }
            }
        })
        add(object : DumbAwareAction("Update All Applications", "Apply the 'On Update action' policy to all deployed applications",
            AllIcons.Javaee.UpdateRunningApplication) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = running != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                running?.let { TomcatInstances.getInstance(project).update(it, configuration.updatePolicy) }
            }
        })
        add(Separator.getInstance())
        add(object : DumbAwareAction("Show in Run Tool Window", "Show this run in the Run/Debug tool window", AllIcons.Debugger.Console) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = running != null
            }
            override fun actionPerformed(e: AnActionEvent) = showConsole()
        })
        add(object : DumbAwareAction("Edit Configuration…", null, AllIcons.Actions.EditSource) {
            override fun actionPerformed(e: AnActionEvent) {
                RunManager.getInstance(project).selectedConfiguration = settings
                EditConfigurationsDialog(project).show()
                TomcatServicesContributor.refresh(project)
            }
        })
    }

    private fun serverContent(): JComponent {
        val instance = running
        val installation = configuration.installation
        return panel {
            row("Status:") {
                label(when {
                    instance == null -> "Stopped"
                    !instance.started -> "Starting…"
                    instance.isDebug -> "Running (debug)"
                    else -> "Running"
                })
            }
            row("Server:") {
                label(installation?.let { "${configuration.serverName} — ${it.home}" } ?: "'${configuration.serverName ?: ""}' is not configured")
            }
            row("HTTP:") { browserLink("http://localhost:${configuration.httpPort}/", "http://localhost:${configuration.httpPort}/") }
            group("Deployments") {
                if (configuration.deployments.isEmpty()) row { label("No artifacts are deployed (Edit Configuration… › Deployment)") }
                for (deployment in configuration.deployments) {
                    row(deployment.artifactName) {
                        val url = contextUrl(configuration, deployment.contextPath)
                        if (instance != null) browserLink(url, url) else label(deployment.contextPath)
                    }
                }
            }
            if (instance != null) {
                row { button("Show in Run Tool Window") { showConsole() } }
            }
        }.apply { border = JBUI.Borders.empty(8) }
    }
}

/** A deployed artifact under a server node. */
class TomcatDeploymentNode(val server: TomcatServerNode, val deployment: TomcatDeployment) {
    private val project get() = server.project
    private val configuration get() = server.configuration

    override fun equals(other: Any?): Boolean =
        other is TomcatDeploymentNode && other.server == server && other.deployment == deployment
    override fun hashCode(): Int = server.hashCode() * 31 + deployment.hashCode()

    private fun instance(): RunningTomcat? = TomcatInstances.getInstance(project).findByConfiguration(configuration)

    private fun resolved(instance: RunningTomcat): ResolvedDeployment? =
        instance.deployments.firstOrNull { it.artifactName == deployment.artifactName && it.contextPath == TomcatContexts.normalize(deployment.contextPath) }

    /** Snapshot of the artifact taken inside a read action (the Services tree renders on EDT without one). */
    private class ArtifactInfo(val icon: Icon, val typeName: String, val outputFilePath: String?)

    private fun artifact(): ArtifactInfo? = WebArtifacts.read {
        ArtifactManager.getInstance(project).findArtifact(deployment.artifactName)?.let {
            ArtifactInfo(it.artifactType.icon, it.artifactType.presentableName, it.outputFilePath)
        }
    }

    fun descriptor(): ServiceViewDescriptor = object : ServiceViewDescriptor {
        override fun getId(): String = "tomcat:" + server.settings.uniqueID + ":" + deployment.artifactName + ":" + deployment.contextPath

        override fun getPresentation(): ItemPresentation {
            val artifact = artifact()
            val instance = instance()
            val deployed = instance != null && instance.started && resolved(instance) != null
            val baseIcon: Icon = artifact?.icon ?: AllIcons.Nodes.Artifact
            return PresentationData(deployment.artifactName, deployment.contextPath, if (deployed) ExecutionUtil.getLiveIndicator(baseIcon) else baseIcon, null).apply {
                addText(deployment.artifactName, if (artifact == null) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                addText("  ${TomcatContexts.normalize(deployment.contextPath)}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                if (artifact == null) addText("  [artifact not found]", SimpleTextAttributes.ERROR_ATTRIBUTES)
                else if (deployed) addText("  [deployed]", SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES)
            }
        }

        override fun getToolbarActions(): ActionGroup = deploymentActions()
        override fun getPopupActions(): ActionGroup = deploymentActions()

        override fun getContentComponent(): JComponent {
            val artifact = artifact()
            val instance = instance()
            val url = contextUrl(configuration, TomcatContexts.normalize(deployment.contextPath))
            return panel {
                row("Artifact:") { label(deployment.artifactName + (artifact?.let { "  (${it.typeName})" } ?: "  — not found")) }
                row("Output:") { label(artifact?.outputFilePath ?: "-") }
                row("Context:") { label(TomcatContexts.normalize(deployment.contextPath)) }
                row("URL:") { if (instance != null) browserLink(url, url) else label(url) }
                row {
                    comment("Use the toolbar to update only this application: the other applications deployed on this Tomcat are not reloaded.")
                }
            }.apply { border = JBUI.Borders.empty(8) }
        }

        override fun handleDoubleClick(event: MouseEvent): Boolean {
            if (instance() == null) return false
            BrowserUtil.browse(contextUrl(configuration, TomcatContexts.normalize(deployment.contextPath)))
            return true
        }
    }

    private fun updateAction(policy: UpdatePolicy, icon: Icon?) = object : DumbAwareAction(policy.presentableName, "${policy.presentableName} of '${deployment.artifactName}' only", icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = instance()?.let(::resolved) != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val instance = instance() ?: return
            val resolved = resolved(instance) ?: return
            TomcatInstances.getInstance(project).update(instance, policy, deployments = listOf(resolved))
        }
    }

    private fun deploymentActions(): ActionGroup = DefaultActionGroup().apply {
        add(updateAction(UpdatePolicy.UPDATE_RESOURCES, AllIcons.Actions.Refresh))
        add(updateAction(UpdatePolicy.UPDATE_CLASSES_AND_RESOURCES, AllIcons.Javaee.UpdateRunningApplication))
        add(updateAction(UpdatePolicy.REDEPLOY, AllIcons.Actions.Rerun))
        add(Separator.getInstance())
        add(object : DumbAwareAction("Open in Browser", null, AllIcons.General.Web) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = instance() != null
            }
            override fun actionPerformed(e: AnActionEvent) =
                BrowserUtil.browse(contextUrl(configuration, TomcatContexts.normalize(deployment.contextPath)))
        })
        add(object : DumbAwareAction("Build Artifact", "Build '${deployment.artifactName}'", AllIcons.Actions.Compile) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = artifact() != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                WebArtifacts.find(project, deployment.artifactName)?.let { ProjectTaskManager.getInstance(project).build(it) }
            }
        })
    }
}

/** Keeps the Tomcat group of the Services view in sync with the run configurations. */
class TomcatServicesRunManagerListener(private val project: Project) : RunManagerListener {
    private fun refreshIfTomcat(settings: RunnerAndConfigurationSettings) {
        if (settings.configuration is TomcatRunConfiguration) {
            ApplicationManager.getApplication().invokeLater({ TomcatServicesContributor.refresh(project) }, project.disposed)
        }
    }

    override fun runConfigurationAdded(settings: RunnerAndConfigurationSettings) = refreshIfTomcat(settings)
    override fun runConfigurationRemoved(settings: RunnerAndConfigurationSettings) = refreshIfTomcat(settings)
    override fun runConfigurationChanged(settings: RunnerAndConfigurationSettings) = refreshIfTomcat(settings)
}

/**
 * Version 1.0 of the plugin put Tomcat run configurations into the "Run Dashboard" part of the Services view.
 * They now have their own "Tomcat" group with artifact nodes, so remove the duplicate entry once.
 */
class TomcatRunDashboardStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(MIGRATED_KEY)) return
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            if (properties.getBoolean(OLD_KEY)) {
                val manager = RunDashboardManager.getInstance(project)
                if (TomcatConfigurationType.ID in manager.types) manager.types = manager.types - TomcatConfigurationType.ID
            }
            properties.setValue(MIGRATED_KEY, true)
        }
    }

    private companion object {
        const val OLD_KEY = "community.tomcat.services.view.registered"
        const val MIGRATED_KEY = "community.tomcat.services.view.own.group"
    }
}
