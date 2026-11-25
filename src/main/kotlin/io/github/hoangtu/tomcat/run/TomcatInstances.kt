package io.github.hoangtu.tomcat.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.packaging.artifacts.Artifact
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.task.ProjectTaskManager
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.management.ObjectName
import javax.management.remote.JMXConnectorFactory
import javax.management.remote.JMXServiceURL

/** A Tomcat process started from a [TomcatRunConfiguration]. */
class RunningTomcat(
    val configuration: TomcatRunConfiguration,
    val base: TomcatCatalinaBase.Prepared,
    val deployments: List<ResolvedDeployment>,
    val processHandler: ProcessHandler,
    val isDebug: Boolean,
) {
    val name: String get() = configuration.name
    val jmxPort: Int = configuration.jmxPort
    internal val updating = AtomicBoolean(false)
}

/** Keeps track of running Tomcat instances and implements the "Update application" actions. */
@Service(Service.Level.PROJECT)
class TomcatInstances(private val project: Project) {
    private val instances = CopyOnWriteArrayList<RunningTomcat>()

    val running: List<RunningTomcat> get() = instances.filter { !it.processHandler.isProcessTerminated }

    fun register(instance: RunningTomcat) {
        instances += instance
        instance.processHandler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                instances -= instance
            }
        })
    }

    fun findByHandler(handler: ProcessHandler?): RunningTomcat? = running.firstOrNull { it.processHandler == handler }

    /**
     * Applies [policy] to the given [deployments] of a running Tomcat (all of them by default): only the artifacts
     * of these deployments are built and only their contexts are reloaded, the other applications are not touched.
     */
    fun update(
        instance: RunningTomcat,
        policy: UpdatePolicy,
        silent: Boolean = false,
        deployments: List<ResolvedDeployment> = instance.deployments,
    ) {
        when (policy) {
            UpdatePolicy.NOTHING -> return
            UpdatePolicy.RESTART -> {
                val descriptor = RunContentManager.getInstance(project).allDescriptors.firstOrNull { it.processHandler == instance.processHandler }
                if (descriptor != null) ExecutionUtil.restart(descriptor)
                return
            }
            else -> Unit
        }
        if (!instance.updating.compareAndSet(false, true)) return

        val artifacts: List<Artifact> = ReadAction.compute<List<Artifact>, RuntimeException> {
            val manager = ArtifactManager.getInstance(project)
            deployments.mapNotNull { manager.findArtifact(it.artifactName) }.distinct()
        }
        if (artifacts.isEmpty()) {
            instance.updating.set(false)
            return
        }
        val stampBefore = classesStamp(deployments)
        ProjectTaskManager.getInstance(project).build(*artifacts.toTypedArray())
            .onProcessed { result ->
                try {
                    if (result == null || result.hasErrors() || result.isAborted) return@onProcessed
                    val classesChanged = classesStamp(deployments) != stampBefore
                    val reload = when (policy) {
                        UpdatePolicy.REDEPLOY -> true
                        // in a debug session HotSwap reloads the changed classes without redeploying
                        UpdatePolicy.UPDATE_CLASSES_AND_RESOURCES -> classesChanged && !instance.isDebug
                        else -> false
                    }
                    if (reload && !instance.processHandler.isProcessTerminated) {
                        ApplicationManager.getApplication().executeOnPooledThread { reloadContexts(instance, deployments, silent) }
                    }
                } finally {
                    instance.updating.set(false)
                }
            }
    }

    /** Last modification time of WEB-INF/classes and WEB-INF/lib of exploded deployments. */
    private fun classesStamp(deployments: List<ResolvedDeployment>): Long {
        var max = 0L
        for (deployment in deployments) {
            val docBase = File(deployment.docBase)
            if (docBase.isFile) {
                max = maxOf(max, docBase.lastModified())
                continue
            }
            for (sub in listOf("WEB-INF/classes", "WEB-INF/lib")) {
                File(docBase, sub).walkTopDown().forEach { max = maxOf(max, it.lastModified()) }
            }
        }
        return max
    }

    @org.jetbrains.annotations.VisibleForTesting
    internal fun reloadContexts(instance: RunningTomcat, deployments: List<ResolvedDeployment>, silent: Boolean) {
        val failed = mutableListOf<String>()
        try {
            val url = JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:${instance.jmxPort}/jmxrmi")
            JMXConnectorFactory.connect(url).use { connector ->
                val connection = connector.mBeanServerConnection
                val modules = connection.queryNames(ObjectName("${instance.base.engineName}:j2eeType=WebModule,*"), null)
                for (deployment in deployments) {
                    val ctx = if (deployment.contextPath == "/") "/" else deployment.contextPath
                    val target = "//${instance.base.hostName}$ctx"
                    val module = modules.firstOrNull { it.getKeyProperty("name") == target }
                    if (module != null) {
                        connection.invoke(module, "reload", null, null)
                    } else {
                        failed += deployment.contextPath
                    }
                }
            }
        } catch (e: Exception) {
            LOG.info("JMX reload failed, falling back to touching the context descriptors", e)
            failed.clear()
            failed += deployments.map { it.contextPath }
        }
        // Fallback: Tomcat's auto-deployer redeploys a context whose descriptor was modified.
        for (ctx in failed) {
            val file = instance.base.contextFile(ctx)
            if (Files.exists(file)) file.toFile().setLastModified(System.currentTimeMillis())
        }
        if (!silent) {
            NotificationGroupManager.getInstance().getNotificationGroup("Tomcat Community")
                .createNotification("'${instance.name}': redeployed " + deployments.joinToString { it.contextPath }, NotificationType.INFORMATION)
                .notify(project)
        }
    }

    companion object {
        private val LOG = logger<TomcatInstances>()

        fun getInstance(project: Project): TomcatInstances = project.service()
    }
}
