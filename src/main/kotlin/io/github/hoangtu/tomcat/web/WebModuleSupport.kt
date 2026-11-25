package io.github.hoangtu.tomcat.web

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ui.configuration.FacetsProvider
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaSourceRootType

enum class BuildSystem { MAVEN, GRADLE, NONE }

/** What we know about a web module: where its web resources live and which build tool manages it. */
data class WebModuleInfo(
    val webRoots: List<WebRoot>,
    val facet: WebFacet?,
    val projectDir: String?,
    val buildSystem: BuildSystem,
)

object WebModuleSupport {
    private val CONVENTIONAL_DIRS = listOf("src/main/webapp")

    /** Name of the module without the Gradle source-set suffix (`app.main` -> `app`). */
    fun baseName(module: Module): String = module.name.removeSuffix(".main")

    fun findWebInfo(module: Module, facets: FacetsProvider? = null): WebModuleInfo? {
        val facet = facets?.getFacetsByType(module, WebFacet.ID)?.firstOrNull() ?: WebFacet.getInstances(module).firstOrNull()
        val roots = facet?.webRoots?.takeIf { it.isNotEmpty() }
            ?: findConventionalWebRoot(module)?.let { listOf(WebRoot(it.url, "/")) }
            ?: return null
        val projectDir = detectProjectDir(module, roots.first().path)
        return WebModuleInfo(roots, facet, projectDir?.path, projectDir?.let(::buildSystemOf) ?: BuildSystem.NONE)
    }

    /** `src/main/webapp` for a Maven module (content root = project dir) or a Gradle source-set module (content root = src/main). */
    fun findConventionalWebRoot(module: Module): VirtualFile? {
        for (contentRoot in ModuleRootManager.getInstance(module).contentRoots) {
            for (rel in CONVENTIONAL_DIRS) {
                contentRoot.findFileByRelativePath(rel)?.takeIf { it.isDirectory }?.let { return it }
            }
            if (contentRoot.name == "main" && contentRoot.parent?.name == "src") {
                contentRoot.findChild("webapp")?.takeIf { it.isDirectory }?.let { return it }
            }
        }
        return null
    }

    fun hasProductionSources(module: Module): Boolean =
        ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.SOURCE).isNotEmpty()

    /** Depth of the deepest content root; used to prefer `app.main` over the Gradle aggregator module `app`. */
    fun contentRootDepth(module: Module): Int =
        ModuleRootManager.getInstance(module).contentRoots.maxOfOrNull { it.path.count { c -> c == '/' } } ?: 0

    private fun detectProjectDir(module: Module, webRootPath: String): VirtualFile? {
        val candidates = mutableListOf<VirtualFile>()
        for (contentRoot in ModuleRootManager.getInstance(module).contentRoots) {
            candidates += contentRoot
            if (contentRoot.name == "main" && contentRoot.parent?.name == "src") contentRoot.parent.parent?.let { candidates += it }
        }
        val fromWebRoot = if (webRootPath.endsWith("/src/main/webapp")) webRootPath.removeSuffix("/src/main/webapp") else null
        return candidates.firstOrNull { buildSystemOf(it) != BuildSystem.NONE }
            ?: candidates.firstOrNull { it.path == fromWebRoot }
            ?: candidates.firstOrNull()
    }

    fun buildSystemOf(dir: VirtualFile): BuildSystem = when {
        dir.findChild("pom.xml") != null -> BuildSystem.MAVEN
        dir.findChild("build.gradle") != null || dir.findChild("build.gradle.kts") != null -> BuildSystem.GRADLE
        else -> BuildSystem.NONE
    }

    fun buildSystemOf(path: String): BuildSystem {
        val dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return BuildSystem.NONE
        return buildSystemOf(dir)
    }
}
