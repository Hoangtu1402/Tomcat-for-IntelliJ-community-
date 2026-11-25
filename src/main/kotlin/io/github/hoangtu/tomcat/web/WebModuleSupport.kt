package io.github.hoangtu.tomcat.web

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ui.configuration.FacetsProvider
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.jdom.Element
import org.jetbrains.jps.model.java.JavaSourceRootType

enum class BuildSystem { MAVEN, GRADLE, NONE }

/** What we know about a web module: where its web resources live and which build tool manages it. */
data class WebModuleInfo(
    val webRoots: List<WebRoot>,
    val facet: WebFacet?,
    val projectDir: String?,
    val buildSystem: BuildSystem,
    /** web.xml declared in the build file or found in the first web root */
    val webXmlPath: String? = null,
)

/**
 * What the build file (pom.xml / build.gradle) of a module says about it.
 * [isWar] = Maven `<packaging>war</packaging>`, or the Gradle `war` plugin.
 */
data class BuildDescriptor(
    val system: BuildSystem,
    val projectDir: VirtualFile,
    val isWar: Boolean,
    val webRoots: List<WebRoot>,
    val webXmlPath: String?,
)

object WebModuleSupport {
    private val LOG = logger<WebModuleSupport>()
    private const val DEFAULT_WAR_SOURCE = "src/main/webapp"

    /** Name of the module without the Gradle source-set suffix (`app.main` -> `app`). */
    fun baseName(module: Module): String = module.name.removeSuffix(".main")

    fun findWebInfo(module: Module, facets: FacetsProvider? = null): WebModuleInfo? {
        val facet = facets?.getFacetsByType(module, WebFacet.ID)?.firstOrNull() ?: WebFacet.getInstances(module).firstOrNull()
        val descriptor = describe(module)
        val roots = facet?.webRoots?.takeIf { it.isNotEmpty() }
            ?: descriptor?.takeIf { it.isWar }?.webRoots
            ?: return null
        val webXml = facet?.configuration?.webXmlUrl?.let { FileUtil.toSystemIndependentName(VfsUtilCore.urlToPath(it)) }
            ?: descriptor?.webXmlPath
            ?: roots.firstOrNull()?.path?.let { "$it/WEB-INF/web.xml" }?.takeIf { java.io.File(it).isFile }
        return WebModuleInfo(roots, facet, descriptor?.projectDir?.path, descriptor?.system ?: BuildSystem.NONE, webXml)
    }

    /** Web roots for a module that has no facet configuration yet (used for a Web facet added by hand). */
    fun suggestWebRoots(module: Module): List<WebRoot> = describe(module)?.webRoots
        ?: ModuleRootManager.getInstance(module).contentRoots.asSequence()
            .flatMap { root -> listOf("src/main/webapp", "WebContent", "web", "WebRoot").mapNotNull { root.findFileByRelativePath(it) } }
            .firstOrNull { it.isDirectory }?.let { listOf(WebRoot(it.url, "/")) }
        ?: emptyList()

    fun hasProductionSources(module: Module): Boolean =
        ModuleRootManager.getInstance(module).getSourceRoots(JavaSourceRootType.SOURCE).isNotEmpty()

    /** Depth of the deepest content root; used to prefer `app.main` over the Gradle aggregator module `app`. */
    fun contentRootDepth(module: Module): Int =
        ModuleRootManager.getInstance(module).contentRoots.maxOfOrNull { it.path.count { c -> c == '/' } } ?: 0

    /**
     * The directory of the module's own build file: the content root (Maven, Gradle project module) or, for a Gradle
     * source-set module (`app.main`, content root `app/src/main`), the project directory two levels up.
     */
    fun moduleProjectDir(module: Module): VirtualFile? {
        val contentRoots = ModuleRootManager.getInstance(module).contentRoots
        contentRoots.firstOrNull { hasBuildFile(it) }?.let { return it }
        for (root in contentRoots) {
            if (root.parent?.name == "src") root.parent.parent?.let { return it } // app/src/main -> app
        }
        return contentRoots.firstOrNull()
    }

    private fun hasBuildFile(dir: VirtualFile): Boolean =
        dir.findChild("pom.xml") != null || dir.findChild("build.gradle") != null || dir.findChild("build.gradle.kts") != null

    fun describe(module: Module): BuildDescriptor? {
        val dir = moduleProjectDir(module) ?: return null
        return when (buildSystemOf(dir)) {
            BuildSystem.MAVEN -> describeMaven(dir)
            BuildSystem.GRADLE -> describeGradle(dir)
            BuildSystem.NONE -> null
        }
    }

    fun buildSystemOf(dir: VirtualFile): BuildSystem {
        if (dir.findChild("pom.xml") != null) return BuildSystem.MAVEN
        if (dir.findChild("build.gradle") != null || dir.findChild("build.gradle.kts") != null) return BuildSystem.GRADLE
        // Gradle subproject configured from the root build (no build file of its own)
        var parent = dir.parent
        var depth = 0
        while (parent != null && depth++ < 8) {
            if (parent.findChild("settings.gradle") != null || parent.findChild("settings.gradle.kts") != null) return BuildSystem.GRADLE
            parent = parent.parent
        }
        return BuildSystem.NONE
    }

    fun buildSystemOf(path: String): BuildSystem {
        val dir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path) ?: return BuildSystem.NONE
        return buildSystemOf(dir)
    }

    // ---------------------------------------------------------------- Maven

    private fun Element.child(name: String): Element? = children.firstOrNull { it.name == name }
    private fun Element.childrenNamed(name: String): List<Element> = children.filter { it.name == name }
    private fun Element.text(name: String): String? = child(name)?.textTrim?.takeIf { it.isNotEmpty() }

    /** "${basedir}/WebContent", "${project.basedir}/WebContent", "WebContent" -> absolute path; null for other expressions. */
    private fun resolvePath(projectDir: VirtualFile, value: String): String? {
        var v = value.trim()
        for (prefix in listOf("\${project.basedir}", "\${basedir}", "\${pom.basedir}")) {
            if (v.startsWith(prefix)) v = v.removePrefix(prefix).trimStart('/', '\\')
        }
        if (v.contains("\${")) return null
        v = FileUtil.toSystemIndependentName(v)
        return if (FileUtil.isAbsolute(v)) v else FileUtil.toCanonicalPath(projectDir.path + "/" + v)
    }

    private fun describeMaven(dir: VirtualFile): BuildDescriptor {
        val pom = dir.findChild("pom.xml")!!
        val project = try {
            JDOMUtil.load(pom.inputStream)
        } catch (e: Exception) {
            LOG.info("Cannot read ${pom.path}", e)
            return BuildDescriptor(BuildSystem.MAVEN, dir, false, emptyList(), null)
        }
        val isWar = project.text("packaging") == "war"
        val build = project.child("build")
        val warPlugin = listOfNotNull(build?.child("plugins"), build?.child("pluginManagement")?.child("plugins"))
            .flatMap { it.childrenNamed("plugin") }
            .firstOrNull { it.text("artifactId") == "maven-war-plugin" }
        val configuration = warPlugin?.child("configuration")

        val warSource = configuration?.text("warSourceDirectory")?.let { resolvePath(dir, it) }
            ?: FileUtil.toCanonicalPath(dir.path + "/" + DEFAULT_WAR_SOURCE)
        val roots = mutableListOf(WebRoot(VfsUtilCore.pathToUrl(warSource), "/"))
        // <webResources><resource><directory>..</directory><targetPath>..</targetPath></resource></webResources>
        configuration?.child("webResources")?.childrenNamed("resource")?.forEach { resource ->
            val directory = resource.text("directory")?.let { resolvePath(dir, it) } ?: return@forEach
            val target = resource.text("targetPath")?.let { "/" + it.trim('/') } ?: "/"
            if (target.startsWith("/WEB-INF/classes")) return@forEach // already in the module output
            roots += WebRoot(VfsUtilCore.pathToUrl(directory), target)
        }
        val webXml = configuration?.text("webXml")?.let { resolvePath(dir, it) }
            ?: "$warSource/WEB-INF/web.xml".takeIf { java.io.File(it).isFile }
        return BuildDescriptor(BuildSystem.MAVEN, dir, isWar, roots.distinct(), webXml)
    }

    // ---------------------------------------------------------------- Gradle

    private val WAR_PLUGIN = Regex("""(id\s*\(?\s*["']war["']|apply\s+plugin\s*:\s*["']war["']|^\s*war\s*$)""", RegexOption.MULTILINE)
    private val WEB_APP_DIR_PATTERNS = listOf(
        Regex("""webAppDirName\s*=\s*["']([^"']+)["']"""),
        Regex("""webAppDir\s*=\s*(?:file\s*\(\s*)?["']([^"']+)["']"""),
        Regex("""webAppDirectory(?:\.set\s*\(|\s*=)\s*(?:file\s*\(\s*|layout\.projectDirectory\.dir\s*\(\s*)?["']([^"']+)["']"""),
    )

    private fun describeGradle(dir: VirtualFile): BuildDescriptor {
        val buildFile = dir.findChild("build.gradle") ?: dir.findChild("build.gradle.kts")
        val text = try {
            buildFile?.let { VfsUtilCore.loadText(it) } ?: ""
        } catch (_: Exception) {
            ""
        }
        val configured = WEB_APP_DIR_PATTERNS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
        val webApp = configured?.let { resolvePath(dir, it) } ?: FileUtil.toCanonicalPath(dir.path + "/" + DEFAULT_WAR_SOURCE)
        // the war plugin may be applied from the root build file: then the web directory itself tells us
        // A root build file that configures other projects ("subprojects { }", "project(':web') { apply plugin: 'war' }")
        // does not make the root project a web application.
        val configuresOtherProjects = Regex("""\b(subprojects|allprojects|project\s*\()""").containsMatchIn(text)
        val isWar = java.io.File(webApp).isDirectory || (WAR_PLUGIN.containsMatchIn(text) && !configuresOtherProjects)
        val webXml = "$webApp/WEB-INF/web.xml".takeIf { java.io.File(it).isFile }
        return BuildDescriptor(BuildSystem.GRADLE, dir, isWar, listOf(WebRoot(VfsUtilCore.pathToUrl(webApp), "/")), webXml)
    }
}
