package io.github.hoangtu.tomcat.web

import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.FacetTypeId
import com.intellij.facet.FacetTypeRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import javax.swing.Icon

/**
 * "Web" facet. It uses the same type id ("web") and the same XML format as the Web facet of IntelliJ IDEA Ultimate,
 * so projects configured in Ultimate are recognized as-is in Community Edition.
 */
class WebFacet(
    type: FacetType<WebFacet, WebFacetConfiguration>,
    module: Module,
    name: String,
    configuration: WebFacetConfiguration,
    underlying: Facet<*>?,
) : Facet<WebFacetConfiguration>(type, module, name, configuration, underlying) {

    val webRoots: List<WebRoot> get() = configuration.webRoots

    /** A Web facet added by hand gets the web directory from pom.xml / build.gradle (or the usual locations). */
    override fun initFacet() {
        super.initFacet()
        if (configuration.webRoots.isEmpty()) {
            configuration.webRoots = WebModuleSupport.suggestWebRoots(module)
        }
    }

    companion object {
        val ID: FacetTypeId<WebFacet> = FacetTypeId("web")

        fun getInstances(module: Module): Collection<WebFacet> = FacetManager.getInstance(module).getFacetsByType(ID)
    }
}

class WebFacetType : FacetType<WebFacet, WebFacetConfiguration>(WebFacet.ID, TYPE_ID, "Web") {
    override fun createDefaultConfiguration(): WebFacetConfiguration = WebFacetConfiguration()

    override fun createFacet(module: Module, name: String, configuration: WebFacetConfiguration, underlyingFacet: Facet<*>?): WebFacet =
        WebFacet(this, module, name, configuration, underlyingFacet)

    override fun isSuitableModuleType(moduleType: ModuleType<*>?): Boolean = moduleType is JavaModuleType

    override fun getIcon(): Icon = AllIcons.Nodes.PpWeb

    override fun getDefaultFacetName(): String = "Web"

    companion object {
        const val TYPE_ID = "web"

        val instance: WebFacetType
            get() = FacetTypeRegistry.getInstance().findFacetType(WebFacet.ID) as WebFacetType
    }
}
