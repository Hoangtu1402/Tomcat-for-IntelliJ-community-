package io.github.hoangtu.tomcat.web

import com.intellij.facet.FacetConfiguration
import com.intellij.facet.ui.FacetEditorContext
import com.intellij.facet.ui.FacetEditorTab
import com.intellij.facet.ui.FacetValidatorsManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import org.jdom.Element

/** Web resource directory and the path it is deployed to inside the application ("/" = application root). */
data class WebRoot(val url: String, val relativePath: String = "/") {
    val path: String get() = FileUtil.toSystemIndependentName(VfsUtilCore.urlToPath(url))
}

data class DeploymentDescriptor(val name: String, val url: String)

/**
 * Configuration of the [WebFacet]. Reads and writes the same XML as IntelliJ IDEA Ultimate:
 * ```
 * <configuration>
 *   <descriptors><deploymentDescriptor name="web.xml" url="file://$MODULE_DIR$/src/main/webapp/WEB-INF/web.xml"/></descriptors>
 *   <webroots><root url="file://$MODULE_DIR$/src/main/webapp" relative="/"/></webroots>
 * </configuration>
 * ```
 * Unknown child elements (written by Ultimate) are preserved.
 */
class WebFacetConfiguration : FacetConfiguration, PersistentStateComponent<Element> {
    var webRoots: List<WebRoot> = emptyList()
    var descriptors: List<DeploymentDescriptor> = emptyList()
    private var otherElements: List<Element> = emptyList()

    override fun createEditorTabs(editorContext: FacetEditorContext, validatorsManager: FacetValidatorsManager): Array<FacetEditorTab> =
        arrayOf(WebFacetEditorTab(editorContext, this))

    override fun getState(): Element {
        val root = Element("configuration")
        if (descriptors.isNotEmpty()) {
            val descriptorsTag = Element("descriptors")
            descriptors.forEach {
                descriptorsTag.addContent(Element("deploymentDescriptor").setAttribute("name", it.name).setAttribute("url", it.url))
            }
            root.addContent(descriptorsTag)
        }
        val rootsTag = Element("webroots")
        webRoots.forEach { rootsTag.addContent(Element("root").setAttribute("url", it.url).setAttribute("relative", it.relativePath)) }
        root.addContent(rootsTag)
        otherElements.forEach { root.addContent(it.clone()) }
        return root
    }

    override fun loadState(state: Element) {
        descriptors = state.getChild("descriptors")?.getChildren("deploymentDescriptor")?.mapNotNull {
            val url = it.getAttributeValue("url") ?: return@mapNotNull null
            DeploymentDescriptor(it.getAttributeValue("name") ?: "web.xml", url)
        } ?: emptyList()
        webRoots = state.getChild("webroots")?.getChildren("root")?.mapNotNull {
            val url = it.getAttributeValue("url") ?: return@mapNotNull null
            WebRoot(url, it.getAttributeValue("relative")?.takeIf(String::isNotEmpty) ?: "/")
        } ?: emptyList()
        otherElements = state.children.filter { it.name != "descriptors" && it.name != "webroots" }.map { it.clone() }
    }

    val webXmlUrl: String? get() = descriptors.firstOrNull { it.name == "web.xml" }?.url
}
