package io.github.hoangtu.tomcat

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hoangtu.tomcat.run.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JComponent

/** Renders the run configuration tabs to PNG files (build/screenshots) when -Dscreenshots=true. */
class EditorScreenshotTest : BasePlatformTestCase() {
    private fun layout(c: java.awt.Component) {
        c.doLayout()
        if (c is java.awt.Container) c.components.forEach(::layout)
    }

    fun testRenderEditors() {
        if (System.getProperty("screenshots") != "true") return
        val config = TomcatConfigurationType.getInstance().configurationFactories.single().createTemplateConfiguration(project) as TomcatRunConfiguration
        config.deployments = listOf(TomcatDeployment("shop:war exploded", "/shop"), TomcatDeployment("admin:war exploded", "/admin"), TomcatDeployment.external("/opt/apps/legacy.war"))
        val editors = mapOf(
            "server" to TomcatServerSettingsEditor(project),
            "deployment" to TomcatDeploymentSettingsEditor(project),
            "logs" to TomcatLogsSettingsEditor(project),
            "startup" to TomcatStartupSettingsEditor(),
        )
        val dir = File("build/screenshots").apply { mkdirs() }
        for ((name, editor) in editors) {
            val component: JComponent = editor.component
            editor.resetFrom(config)
            component.setSize(760, 620)
            layout(component)
            val image = BufferedImage(760, 620, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, 760, 620)
            component.printAll(g)
            g.dispose()
            ImageIO.write(image, "png", File(dir, "$name.png"))
            Disposer.dispose(editor)
        }
    }
}
