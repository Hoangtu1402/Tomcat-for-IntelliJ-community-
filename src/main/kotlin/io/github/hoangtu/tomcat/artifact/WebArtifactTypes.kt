package io.github.hoangtu.tomcat.artifact

import com.intellij.icons.AllIcons
import com.intellij.packaging.artifacts.Artifact
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.artifacts.ArtifactTemplate
import com.intellij.packaging.artifacts.ArtifactType
import com.intellij.packaging.artifacts.ModifiableArtifact
import com.intellij.packaging.elements.CompositePackagingElement
import com.intellij.packaging.elements.PackagingElementFactory
import com.intellij.packaging.elements.PackagingElementOutputKind
import com.intellij.packaging.elements.PackagingElementResolvingContext
import com.intellij.packaging.impl.artifacts.ArtifactUtil
import io.github.hoangtu.tomcat.web.WebModuleSupport
import javax.swing.Icon

private fun webDefaultPath(kind: PackagingElementOutputKind): String = when (kind) {
    PackagingElementOutputKind.DIRECTORIES_WITH_CLASSES -> "/WEB-INF/classes"
    PackagingElementOutputKind.JAR_FILES -> "/WEB-INF/lib"
    else -> "/"
}

/** "Web Application: Exploded" artifact type. Same id as in IntelliJ IDEA Ultimate (`exploded-war`). */
class ExplodedWarArtifactType : ArtifactType(ID, { "Web Application: Exploded" }) {
    override fun getIcon(): Icon = AllIcons.Nodes.PpWeb

    override fun getDefaultPathFor(kind: PackagingElementOutputKind): String = webDefaultPath(kind)

    override fun createRootElement(artifactName: String): CompositePackagingElement<*> =
        PackagingElementFactory.getInstance().createArtifactRootElement()

    override fun getNewArtifactTemplates(context: PackagingElementResolvingContext): List<ArtifactTemplate> =
        context.modulesProvider.modules
            .mapNotNull { module -> WebModuleSupport.findWebInfo(module, context.facetsProvider)?.let { module to it } }
            .map { (module, info) ->
                object : ArtifactTemplate() {
                    override fun getPresentableName(): String = "From module '${module.name}'"

                    override fun createArtifact(): NewArtifactConfiguration {
                        val root = WebArtifactLayout.createExplodedRoot(module, info)
                        return NewArtifactConfiguration(root, WebArtifactLayout.explodedName(module), this@ExplodedWarArtifactType)
                    }

                    override fun setUpArtifact(artifact: ModifiableArtifact, configuration: NewArtifactConfiguration) {
                        WebArtifactLayout.defaultExplodedOutput(module, info)?.let(artifact::setOutputPath)
                    }
                }
            }

    companion object {
        const val ID = "exploded-war"

        fun getInstance(): ExplodedWarArtifactType = EP_NAME.findExtensionOrFail(ExplodedWarArtifactType::class.java)
    }
}

/** "Web Application: Archive" (.war) artifact type. Same id as in IntelliJ IDEA Ultimate (`war`). */
class WarArtifactType : ArtifactType(ID, { "Web Application: Archive" }) {
    override fun getIcon(): Icon = AllIcons.FileTypes.Archive

    override fun getDefaultPathFor(kind: PackagingElementOutputKind): String = webDefaultPath(kind)

    override fun createRootElement(artifactName: String): CompositePackagingElement<*> =
        PackagingElementFactory.getInstance().createArchive(ArtifactUtil.suggestArtifactFileName(artifactName) + ".war")

    override fun getNewArtifactTemplates(context: PackagingElementResolvingContext): List<ArtifactTemplate> =
        context.artifactModel.getArtifactsByType(ExplodedWarArtifactType.getInstance()).map { exploded ->
            object : ArtifactTemplate() {
                override fun getPresentableName(): String = "From '${exploded.name}'"

                override fun createArtifact(): NewArtifactConfiguration {
                    val name = WebArtifactLayout.archiveNameFor(exploded.name)
                    return NewArtifactConfiguration(WebArtifactLayout.createWarRoot(context.project, exploded, name), name, this@WarArtifactType)
                }

                override fun setUpArtifact(artifact: ModifiableArtifact, configuration: NewArtifactConfiguration) {
                    exploded.outputPath?.let { artifact.outputPath = WebArtifactLayout.defaultWarOutputFor(it) }
                }
            }
        }

    companion object {
        const val ID = "war"

        fun getInstance(): WarArtifactType = EP_NAME.findExtensionOrFail(WarArtifactType::class.java)
    }
}

object WebArtifacts {
    fun isWebArtifact(artifact: Artifact): Boolean =
        artifact.artifactType.id == ExplodedWarArtifactType.ID || artifact.artifactType.id == WarArtifactType.ID

    fun all(project: com.intellij.openapi.project.Project): List<Artifact> =
        ArtifactManager.getInstance(project).artifacts.filter(::isWebArtifact).sortedBy { it.name }
}
