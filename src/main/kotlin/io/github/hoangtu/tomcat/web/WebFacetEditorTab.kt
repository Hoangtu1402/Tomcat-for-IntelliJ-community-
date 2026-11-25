package io.github.hoangtu.tomcat.web

import com.intellij.facet.ui.FacetEditorContext
import com.intellij.facet.ui.FacetEditorTab
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/** Simple editor for the web resource directory and the web.xml location of a [WebFacet]. */
class WebFacetEditorTab(private val context: FacetEditorContext, private val configuration: WebFacetConfiguration) : FacetEditorTab() {
    private val webRootField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(context.project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Web Resource Directory"))
    }
    private val webXmlField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(context.project, FileChooserDescriptorFactory.createSingleFileDescriptor("xml").withTitle("Deployment Descriptor (web.xml)"))
    }

    override fun getDisplayName(): String = "Web"

    override fun createComponent(): JComponent = panel {
        row("Web resource directory:") { cell(webRootField).align(AlignX.FILL) }
            .rowComment("Its content is copied to the root of the exploded WAR (e.g. src/main/webapp)")
        row("Deployment descriptor (web.xml):") { cell(webXmlField).align(AlignX.FILL) }
            .rowComment("Optional for Servlet 3.0+ applications")
    }

    private fun currentRootPath(): String = configuration.webRoots.firstOrNull()?.path?.let(FileUtil::toSystemDependentName) ?: ""
    private fun currentWebXmlPath(): String =
        configuration.webXmlUrl?.let { FileUtil.toSystemDependentName(VfsUtilCore.urlToPath(it)) } ?: ""

    override fun isModified(): Boolean = webRootField.text.trim() != currentRootPath() || webXmlField.text.trim() != currentWebXmlPath()

    override fun reset() {
        webRootField.text = currentRootPath()
        webXmlField.text = currentWebXmlPath()
    }

    override fun apply() {
        val root = webRootField.text.trim()
        val others = configuration.webRoots.drop(1)
        configuration.webRoots =
            (if (root.isEmpty()) emptyList() else listOf(WebRoot(VfsUtilCore.pathToUrl(FileUtil.toSystemIndependentName(root)), "/"))) + others
        val webXml = webXmlField.text.trim()
        val otherDescriptors = configuration.descriptors.filter { it.name != "web.xml" }
        configuration.descriptors =
            (if (webXml.isEmpty()) emptyList() else listOf(DeploymentDescriptor("web.xml", VfsUtilCore.pathToUrl(FileUtil.toSystemIndependentName(webXml))))) + otherDescriptors
    }
}
