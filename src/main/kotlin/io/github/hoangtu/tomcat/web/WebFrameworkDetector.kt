package io.github.hoangtu.tomcat.web

import com.intellij.framework.detection.FacetBasedFrameworkDetector
import com.intellij.framework.detection.FileContentPattern
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.ElementPattern
import com.intellij.util.indexing.FileContent

/**
 * Detects web applications by their WEB-INF/web.xml and offers to add the "Web" facet
 * (the "Frameworks detected" notification, as in IntelliJ IDEA Ultimate).
 */
class WebFrameworkDetector : FacetBasedFrameworkDetector<WebFacet, WebFacetConfiguration>("community-web", 1) {
    override fun getFacetType() = WebFacetType.instance

    override fun getFileType(): FileType = XmlFileType.INSTANCE

    override fun createSuitableFilePattern(): ElementPattern<FileContent> =
        FileContentPattern.fileContent().withName("web.xml").xmlWithRootTag("web-app")

    override fun createConfiguration(files: Collection<VirtualFile>): WebFacetConfiguration? {
        val webXml = files.firstOrNull { it.parent?.name == "WEB-INF" } ?: return null
        val webRoot = webXml.parent.parent ?: return null
        return WebFacetConfiguration().apply {
            webRoots = listOf(WebRoot(webRoot.url, "/"))
            descriptors = listOf(DeploymentDescriptor("web.xml", webXml.url))
        }
    }
}
