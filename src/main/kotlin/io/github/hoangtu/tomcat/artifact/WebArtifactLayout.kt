package io.github.hoangtu.tomcat.artifact

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.packaging.artifacts.Artifact
import com.intellij.packaging.elements.CompositePackagingElement
import com.intellij.packaging.elements.PackagingElement
import com.intellij.packaging.elements.PackagingElementFactory
import com.intellij.packaging.impl.artifacts.ArtifactUtil
import io.github.hoangtu.tomcat.web.BuildSystem
import io.github.hoangtu.tomcat.web.WebModuleInfo
import io.github.hoangtu.tomcat.web.WebModuleSupport

/**
 * Builds the standard WAR layout, like IntelliJ IDEA Ultimate does for web modules:
 * ```
 * <web resources>            (src/main/webapp)
 * WEB-INF/classes/           module compile output
 * WEB-INF/lib/               runtime libraries + dependent modules packed as jars
 * ```
 * Provided and test dependencies are not packaged.
 */
object WebArtifactLayout {
    const val EXPLODED_SUFFIX = ":war exploded"
    const val WAR_SUFFIX = ":war"

    fun explodedName(module: Module): String = WebModuleSupport.baseName(module) + EXPLODED_SUFFIX
    fun warName(module: Module): String = WebModuleSupport.baseName(module) + WAR_SUFFIX

    fun archiveNameFor(explodedArtifactName: String): String =
        if (explodedArtifactName.endsWith(EXPLODED_SUFFIX)) explodedArtifactName.removeSuffix(" exploded") else "$explodedArtifactName$WAR_SUFFIX"

    private fun warFileName(artifactName: String): String =
        ArtifactUtil.suggestArtifactFileName(artifactName.removeSuffix(EXPLODED_SUFFIX).removeSuffix(WAR_SUFFIX)) + ".war"

    private class Dependencies(val libraries: List<LibraryOrderEntry>, val modules: List<Module>)

    private fun collectDependencies(module: Module): Dependencies {
        val libraries = LinkedHashMap<String, LibraryOrderEntry>()
        val modules = LinkedHashSet<Module>()
        OrderEnumerator.orderEntries(module).productionOnly().runtimeOnly().recursively().withoutSdk().withoutModuleSourceEntries()
            .forEach { entry ->
                when (entry) {
                    is LibraryOrderEntry -> {
                        val key = "${entry.libraryLevel}|${entry.libraryName ?: entry.presentableName}|${if (entry.isModuleLevel) entry.ownerModule.name else ""}"
                        libraries.putIfAbsent(key, entry)
                    }
                    is ModuleOrderEntry -> entry.module?.takeIf { it != module }?.let(modules::add)
                }
                true
            }
        return Dependencies(libraries.values.toList(), modules.toList())
    }

    fun createExplodedRoot(module: Module, info: WebModuleInfo): CompositePackagingElement<*> {
        val factory = PackagingElementFactory.getInstance()
        val root = factory.createArtifactRootElement()
        for (webRoot in info.webRoots) {
            root.addOrFindChild(factory.createDirectoryCopyWithParentDirectories(webRoot.path, webRoot.relativePath))
        }
        factory.getOrCreateDirectory(root, "WEB-INF/classes").addOrFindChild(factory.createModuleOutput(module))

        val deps = collectDependencies(module)
        val lib = factory.getOrCreateDirectory(root, "WEB-INF/lib")
        for (entry in deps.libraries) {
            val elements: List<PackagingElement<*>> = when {
                entry.libraryName != null && entry.isModuleLevel ->
                    listOf(factory.createLibraryFiles(entry.libraryName!!, "module", entry.ownerModule.name))
                entry.libraryName != null ->
                    listOf(factory.createLibraryFiles(entry.libraryName!!, entry.libraryLevel, null))
                else -> entry.library?.let(factory::createLibraryElements) ?: emptyList()
            }
            lib.addOrFindChildren(elements)
        }
        for (dep in deps.modules) {
            val jar = factory.createArchive(jarName(dep))
            jar.addOrFindChild(factory.createModuleOutput(dep))
            lib.addOrFindChild(jar)
        }
        return root
    }

    /** Text describing the generated layout; when it changes the managed artifact is regenerated. */
    fun signature(module: Module, info: WebModuleInfo): String {
        val deps = collectDependencies(module)
        return buildString {
            info.webRoots.forEach { append("root:").append(it.path).append("->").append(it.relativePath).append('\n') }
            deps.libraries.forEach { append("lib:").append(it.libraryLevel).append(':').append(it.libraryName ?: it.presentableName).append('\n') }
            deps.modules.forEach { append("module:").append(it.name).append('\n') }
        }
    }

    private fun jarName(module: Module): String {
        val base = WebModuleSupport.baseName(module)
        return ArtifactUtil.suggestArtifactFileName(base.substringAfterLast('.')) + ".jar"
    }

    fun createWarRoot(project: Project, exploded: Artifact, warArtifactName: String): CompositePackagingElement<*> {
        val factory = PackagingElementFactory.getInstance()
        val archive = factory.createArchive(warFileName(warArtifactName))
        archive.addOrFindChild(factory.createArtifactElement(exploded, project))
        return archive
    }

    fun defaultExplodedOutput(module: Module, info: WebModuleInfo): String? {
        val dir = info.projectDir ?: return ArtifactUtil.getDefaultArtifactOutputPath(explodedName(module), module.project)
        val base = FileUtil.sanitizeFileName(WebModuleSupport.baseName(module).substringAfterLast('.'))
        return when (info.buildSystem) {
            BuildSystem.MAVEN -> "$dir/target/$base"
            BuildSystem.GRADLE -> "$dir/build/libs/exploded/$base.war"
            BuildSystem.NONE -> ArtifactUtil.getDefaultArtifactOutputPath(explodedName(module), module.project)
        }
    }

    fun defaultWarOutputFor(explodedOutput: String): String {
        val parent = explodedOutput.substringBeforeLast('/')
        return if (parent.endsWith("/exploded")) parent.removeSuffix("/exploded") else parent
    }
}
