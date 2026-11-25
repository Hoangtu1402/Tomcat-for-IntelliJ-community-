package io.github.hoangtu.tomcat.artifact

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.packaging.artifacts.Artifact
import com.intellij.packaging.artifacts.ArtifactType
import com.intellij.packaging.elements.ComplexPackagingElement
import com.intellij.packaging.elements.ComplexPackagingElementType
import com.intellij.packaging.elements.PackagingElement
import com.intellij.packaging.elements.PackagingElementFactory
import com.intellij.packaging.elements.PackagingElementResolvingContext
import com.intellij.packaging.ui.PackagingElementWeights
import com.intellij.packaging.ui.ArtifactEditorContext
import com.intellij.packaging.ui.PackagingElementPresentation
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.xmlb.annotations.Attribute
import io.github.hoangtu.tomcat.web.WebFacet
import javax.swing.Icon

/**
 * "'Web' facet resources" layout element (id `javaee-facet-resources`), compatible with artifacts created by
 * IntelliJ IDEA Ultimate. It copies all web resource directories of a [WebFacet] into the artifact.
 */
class WebFacetResourcesElement(facetId: String? = null) :
    ComplexPackagingElement<WebFacetResourcesElement.State>(WebFacetResourcesElementType.getInstance()) {

    class State {
        @Attribute("facet")
        var facet: String? = null
    }

    private var facetId: String? = facetId

    /** `moduleName/web/facetName` */
    val facetReference: String? get() = facetId

    val moduleName: String? get() = facetId?.substringBefore('/')
    val facetName: String get() = facetId?.split('/')?.getOrNull(2) ?: "Web"

    fun findFacet(context: PackagingElementResolvingContext): WebFacet? {
        val module = context.modulesProvider.getModule(moduleName ?: return null) ?: return null
        return context.facetsProvider.findFacet(module, WebFacet.ID, facetName)
            ?: context.facetsProvider.getFacetsByType(module, WebFacet.ID).firstOrNull()
    }

    override fun getSubstitution(context: PackagingElementResolvingContext, artifactType: ArtifactType): List<PackagingElement<*>>? {
        val facet = findFacet(context) ?: return null
        val factory = PackagingElementFactory.getInstance()
        return facet.webRoots.map { factory.createDirectoryCopyWithParentDirectories(it.path, it.relativePath) }
    }

    override fun createPresentation(context: ArtifactEditorContext): PackagingElementPresentation =
        object : PackagingElementPresentation() {
            override fun getPresentableName(): String = "'${facetName}' facet resources" + (moduleName?.let { " ($it)" } ?: "")
            override fun render(data: PresentationData, main: SimpleTextAttributes, comment: SimpleTextAttributes) {
                data.setIcon(AllIcons.Nodes.PpWeb)
                val valid = findFacet(context) != null
                data.addText("'$facetName' facet resources", if (valid) main else SimpleTextAttributes.ERROR_ATTRIBUTES)
                moduleName?.let { data.addText(" ($it)", comment) }
            }
            override fun getWeight(): Int = PackagingElementWeights.FACET
        }

    override fun isEqualTo(element: PackagingElement<*>): Boolean =
        element is WebFacetResourcesElement && element.facetId == facetId

    override fun getState(): State = State().also { it.facet = facetId }

    override fun loadState(state: State) {
        facetId = state.facet
    }

    companion object {
        fun forFacet(facet: WebFacet) = WebFacetResourcesElement("${facet.module.name}/${WebFacet.ID}/${facet.name}")
    }
}

class WebFacetResourcesElementType : ComplexPackagingElementType<WebFacetResourcesElement>(ID, { "Web Facet Resources" }) {
    override fun getShowContentActionText(): String = "Show Content of Web Facets"

    override fun getCreateElementIcon(): Icon = AllIcons.Nodes.PpWeb

    override fun canCreate(context: ArtifactEditorContext, artifact: Artifact): Boolean = allFacets(context.project).isNotEmpty()

    override fun chooseAndCreate(context: ArtifactEditorContext, artifact: Artifact, parent: com.intellij.packaging.elements.CompositePackagingElement<*>): List<PackagingElement<*>> {
        val facets = allFacets(context.project)
        val modules = context.chooseModules(facets.map { it.module }.distinct(), "Choose Module With Web Facet")
        return facets.filter { it.module in modules }.map { WebFacetResourcesElement.forFacet(it) }
    }

    override fun createEmpty(project: Project): WebFacetResourcesElement = WebFacetResourcesElement()

    private fun allFacets(project: Project): List<WebFacet> =
        ModuleManager.getInstance(project).modules.flatMap { WebFacet.getInstances(it) }

    companion object {
        const val ID = "javaee-facet-resources"

        fun getInstance(): WebFacetResourcesElementType = getInstance(WebFacetResourcesElementType::class.java)
    }
}
