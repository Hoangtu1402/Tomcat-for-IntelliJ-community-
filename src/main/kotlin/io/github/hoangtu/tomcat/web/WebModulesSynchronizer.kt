package io.github.hoangtu.tomcat.web

import com.intellij.facet.FacetManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.impl.artifacts.ArtifactUtil
import com.intellij.util.Alarm
import com.intellij.util.xmlb.annotations.MapAnnotation
import com.intellij.util.xmlb.annotations.XMap
import io.github.hoangtu.tomcat.artifact.ExplodedWarArtifactType
import io.github.hoangtu.tomcat.artifact.WarArtifactType
import io.github.hoangtu.tomcat.artifact.WebArtifactLayout
import io.github.hoangtu.tomcat.server.TomcatCommunitySettings

/**
 * Mirrors what IntelliJ IDEA Ultimate does on Maven/Gradle import: every module with `src/main/webapp` (or a Web facet)
 * gets a Web facet plus `<module>:war exploded` and `<module>:war` artifacts. Artifacts created here are kept in sync with
 * the module dependencies (WEB-INF/lib) whenever the project model changes.
 */
@Service(Service.Level.PROJECT)
@State(name = "CommunityWebArtifactsSync", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class WebModulesSynchronizer(private val project: Project) : PersistentStateComponent<WebModulesSynchronizer.SyncState>, Disposable {
    class SyncState {
        /** artifact name -> layout signature of the artifacts managed by this plugin */
        @get:XMap
        @get:MapAnnotation(surroundWithTag = false)
        var managed: MutableMap<String, String> = LinkedHashMap()
    }

    private var state = SyncState()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    override fun getState(): SyncState = state
    override fun loadState(state: SyncState) {
        this.state = state
    }

    override fun dispose() {}

    fun scheduleSync(delayMs: Int = 1500) {
        if (!TomcatCommunitySettings.getInstance().autoConfigureWebModules) return
        alarm.cancelAllRequests()
        alarm.addRequest({ DumbService.getInstance(project).runWhenSmart { syncInBackground() } }, delayMs)
    }

    private data class Plan(
        val module: Module,
        val info: WebModuleInfo,
        val addFacet: Boolean,
        val explodedName: String,
        val warName: String,
        val signature: String,
        val createExploded: Boolean,
        val updateExploded: Boolean,
        val createWar: Boolean,
    )

    private fun syncInBackground() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val plans = try {
                ReadAction.compute<List<Plan>, RuntimeException> { computePlans() }
            } catch (e: Exception) {
                LOG.warn(e)
                return@executeOnPooledThread
            }
            if (plans.isEmpty()) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ apply(plans) }, project.disposed)
        }
    }

    /** Synchronous variant used by tests (must be called on EDT). */
    @org.jetbrains.annotations.TestOnly
    fun syncNow() {
        val plans = ReadAction.compute<List<Plan>, RuntimeException> { computePlans() }
        if (plans.isNotEmpty()) apply(plans)
    }

    private fun computePlans(): List<Plan> {
        val artifactManager = ArtifactManager.getInstance(project)
        val candidates = ModuleManager.getInstance(project).modules.mapNotNull { module ->
            val info = WebModuleSupport.findWebInfo(module) ?: return@mapNotNull null
            if (info.buildSystem == BuildSystem.NONE && info.facet == null) return@mapNotNull null
            if (info.facet == null && !WebModuleSupport.hasProductionSources(module) &&
                ModuleManager.getInstance(project).findModuleByName(module.name + ".main") != null
            ) return@mapNotNull null // Gradle aggregator module; the ".main" source-set module is the web module
            module to info
        }
        // One web root may be visible from several modules (Gradle: "app" and "app.main"): keep the most specific one.
        val byRoot = candidates.groupBy { it.second.webRoots.first().path }
            .mapValues { (_, list) -> list.maxBy { WebModuleSupport.contentRootDepth(it.first) } }

        return byRoot.values.mapNotNull { (module, info) ->
            val explodedName = WebArtifactLayout.explodedName(module)
            val warName = WebArtifactLayout.warName(module)
            val signature = WebArtifactLayout.signature(module, info)
            val exploded = artifactManager.findArtifact(explodedName)
            val managedSignature = state.managed[explodedName]
            val otherArtifactsUseModule = exploded == null && ArtifactUtil.getArtifactsContainingModuleOutput(module)
                .any { it.artifactType.id == ExplodedWarArtifactType.ID }
            val plan = Plan(
                module = module,
                info = info,
                addFacet = info.facet == null && info.buildSystem != BuildSystem.NONE,
                explodedName = explodedName,
                warName = warName,
                signature = signature,
                createExploded = exploded == null && !otherArtifactsUseModule && info.buildSystem != BuildSystem.NONE,
                updateExploded = exploded != null && managedSignature != null && managedSignature != signature,
                createWar = artifactManager.findArtifact(warName) == null && exploded == null && !otherArtifactsUseModule &&
                    info.buildSystem != BuildSystem.NONE,
            )
            plan.takeIf { it.addFacet || it.createExploded || it.updateExploded || it.createWar }
        }
    }

    private fun apply(plans: List<Plan>) {
        if (project.isDisposed) return
        val created = mutableListOf<String>()
        WriteAction.run<RuntimeException> {
            for (plan in plans) {
                if (plan.addFacet && plan.module.isLoaded && WebFacet.getInstances(plan.module).isEmpty()) {
                    val facetType = WebFacetType.instance
                    val configuration = facetType.createDefaultConfiguration().apply {
                        webRoots = plan.info.webRoots
                        plan.info.webRoots.firstOrNull()?.let { root ->
                            val webXml = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(root.path + "/WEB-INF/web.xml")
                            if (webXml != null) descriptors = listOf(DeploymentDescriptor("web.xml", webXml.url))
                        }
                    }
                    val model = FacetManager.getInstance(plan.module).createModifiableModel()
                    model.addFacet(FacetManager.getInstance(plan.module).createFacet(facetType, facetType.defaultFacetName, configuration, null))
                    model.commit()
                }
            }

            val model = ArtifactManager.getInstance(project).createModifiableModel()
            try {
                for (plan in plans) {
                    var exploded = model.findArtifact(plan.explodedName)
                    if (plan.createExploded && exploded == null) {
                        val root = WebArtifactLayout.createExplodedRoot(plan.module, plan.info)
                        val artifact = model.addArtifact(plan.explodedName, ExplodedWarArtifactType.getInstance(), root)
                        WebArtifactLayout.defaultExplodedOutput(plan.module, plan.info)?.let { artifact.outputPath = it }
                        exploded = artifact
                        state.managed[plan.explodedName] = plan.signature
                        created += plan.explodedName
                    } else if (plan.updateExploded && exploded != null) {
                        model.getOrCreateModifiableArtifact(exploded).rootElement = WebArtifactLayout.createExplodedRoot(plan.module, plan.info)
                        state.managed[plan.explodedName] = plan.signature
                    }
                    if (plan.createWar && exploded != null && model.findArtifact(plan.warName) == null) {
                        val war = model.addArtifact(plan.warName, WarArtifactType.getInstance(),
                            WebArtifactLayout.createWarRoot(project, exploded, plan.warName))
                        exploded.outputPath?.let { war.outputPath = WebArtifactLayout.defaultWarOutputFor(it) }
                        created += plan.warName
                    }
                }
                model.commit()
            } catch (e: Exception) {
                model.dispose()
                throw e
            }
        }
        if (created.isNotEmpty()) {
            NotificationGroupManager.getInstance().getNotificationGroup("Tomcat Community")
                .createNotification("Web artifacts created", created.joinToString("<br>"), NotificationType.INFORMATION)
                .notify(project)
        }
    }

    companion object {
        private val LOG = logger<WebModulesSynchronizer>()

        fun getInstance(project: Project): WebModulesSynchronizer = project.service()
    }
}

/** Runs the synchronization when the project is opened. */
class WebModulesStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        WebModulesSynchronizer.getInstance(project).scheduleSync(3000)
    }
}

/** Runs the synchronization after Maven/Gradle import or any other change of module roots / dependencies. */
class WebModulesRootListener(private val project: Project) : ModuleRootListener {
    override fun rootsChanged(event: ModuleRootEvent) {
        WebModulesSynchronizer.getInstance(project).scheduleSync()
    }
}
