package io.github.hoangtu.tomcat.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.packaging.artifacts.Artifact
import com.intellij.packaging.artifacts.ArtifactManager
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTaskProvider
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import io.github.hoangtu.tomcat.artifact.WebArtifacts
import io.github.hoangtu.tomcat.server.TomcatCommunitySettings
import io.github.hoangtu.tomcat.server.TomcatServersConfigurable
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/** "Server" tab of the Tomcat run configuration. */
class TomcatServerSettingsEditor(private val project: Project) : SettingsEditor<TomcatRunConfiguration>() {
    private val serverCombo = ComboBox<String>()
    private val versionLabel = JBLabel()
    private val openBrowser = JBCheckBox("After launch, open browser at:")
    private val url = JBTextField()
    private val vmOptions = RawCommandLineEditor()
    private val updatePolicy = ComboBox(DefaultComboBoxModel(UpdatePolicy.forUpdateAction.toTypedArray()))
    private val frameDeactivation = ComboBox(DefaultComboBoxModel(UpdatePolicy.forFrameDeactivation.toTypedArray()))
    private val useJre = JBCheckBox("JRE:")
    private val jrePath = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select JRE/JDK Home"))
    }
    private val httpPort = JBIntSpinner(8080, 1, 65535)
    private val jmxPort = JBIntSpinner(1099, 1, 65535)
    private val deployTomcatApps = JBCheckBox("Deploy applications configured in Tomcat instance (webapps directory)")
    private val envs = EnvironmentVariablesComponent()

    init {
        serverCombo.addActionListener { updateVersionLabel() }
        useJre.addActionListener { jrePath.isEnabled = useJre.isSelected }
        openBrowser.addActionListener { url.isEnabled = openBrowser.isSelected }
        var lastPort = 8080
        httpPort.addChangeListener {
            val newPort = httpPort.number
            val text = url.text
            val prefix = "http://localhost:$lastPort"
            if (text.startsWith(prefix)) url.text = "http://localhost:$newPort" + text.removePrefix(prefix)
            lastPort = newPort
        }
    }

    private fun reloadServers(selected: String?) {
        val names = TomcatCommunitySettings.getInstance().servers.map { it.name }.toMutableList()
        if (!selected.isNullOrBlank() && selected !in names) names += selected
        serverCombo.model = DefaultComboBoxModel(names.toTypedArray())
        serverCombo.selectedItem = selected ?: names.firstOrNull()
        updateVersionLabel()
    }

    private fun updateVersionLabel() {
        val name = serverCombo.selectedItem as String?
        val installation = TomcatCommunitySettings.getInstance().findInstallation(name)
        versionLabel.text = when {
            name.isNullOrBlank() -> "Add a Tomcat installation with 'Configure…'"
            installation == null -> "<html><font color='red'>'$name' is not configured</font></html>"
            !installation.isValid -> "<html><font color='red'>Invalid Tomcat home: ${installation.home}</font></html>"
            else -> "Version ${installation.version ?: "?"} (${if (installation.isJakarta) "jakarta.*" else "javax.*"} Servlet API) — ${installation.home}"
        }
    }

    override fun createEditor(): JComponent = panel {
        row("Application server:") {
            cell(serverCombo).align(AlignX.FILL).resizableColumn()
            button("Configure…") {
                val current = serverCombo.selectedItem as String?
                ShowSettingsUtil.getInstance().showSettingsDialog(project, TomcatServersConfigurable::class.java)
                reloadServers(current ?: TomcatCommunitySettings.getInstance().servers.firstOrNull()?.name)
            }
        }
        row("") { cell(versionLabel) }
        group("Open Browser") {
            row { cell(openBrowser) }
            row { cell(url).align(AlignX.FILL) }
        }
        row("VM options:") { cell(vmOptions).align(AlignX.FILL) }
        row("On 'Update' action:") { cell(updatePolicy) }
            .rowComment("Run | Update Tomcat Application (Ctrl+F10), also available on the Run tool window toolbar")
        row("On frame deactivation:") { cell(frameDeactivation) }
        row { cell(useJre); cell(jrePath).align(AlignX.FILL).resizableColumn() }
        group("Tomcat Server Settings") {
            row("HTTP port:") { cell(httpPort) }
            row("JMX port:") { cell(jmxPort) }.rowComment("Used to redeploy applications without restarting Tomcat")
            row { cell(deployTomcatApps) }
        }
        row { cell(envs).align(AlignX.FILL) }
    }

    override fun resetEditorFrom(c: TomcatRunConfiguration) {
        reloadServers(c.serverName)
        openBrowser.isSelected = c.openBrowser
        url.text = c.browserUrl
        url.isEnabled = c.openBrowser
        vmOptions.text = c.vmOptions
        updatePolicy.selectedItem = c.updatePolicy
        frameDeactivation.selectedItem = if (c.frameDeactivationPolicy in UpdatePolicy.forFrameDeactivation) c.frameDeactivationPolicy else UpdatePolicy.NOTHING
        useJre.isSelected = c.useAlternativeJre
        jrePath.text = c.alternativeJrePath
        jrePath.isEnabled = c.useAlternativeJre
        httpPort.number = c.httpPort
        jmxPort.number = c.jmxPort
        deployTomcatApps.isSelected = c.deployTomcatApps
        envs.envData = c.envs
    }

    override fun applyEditorTo(c: TomcatRunConfiguration) {
        c.serverName = serverCombo.selectedItem as String?
        c.openBrowser = openBrowser.isSelected
        c.browserUrl = url.text.trim()
        c.vmOptions = vmOptions.text
        c.updatePolicy = updatePolicy.selectedItem as UpdatePolicy
        c.frameDeactivationPolicy = frameDeactivation.selectedItem as UpdatePolicy
        c.useAlternativeJre = useJre.isSelected
        c.alternativeJrePath = jrePath.text.trim()
        c.httpPort = httpPort.number
        c.jmxPort = jmxPort.number
        c.deployTomcatApps = deployTomcatApps.isSelected
        c.envs = envs.envData
    }
}

/** "Deployment" tab: artifacts deployed on server startup and their context paths. */
class TomcatDeploymentSettingsEditor(private val project: Project) : SettingsEditor<TomcatRunConfiguration>() {
    private class Row(var artifactName: String, var contextPath: String)

    private val model = ListTableModel<Row>(
        object : ColumnInfo<Row, String>("Artifact") {
            override fun valueOf(item: Row) = item.artifactName
        },
        object : ColumnInfo<Row, String>("Application context") {
            override fun valueOf(item: Row) = item.contextPath
            override fun isCellEditable(item: Row) = true
            override fun setValue(item: Row, value: String) {
                item.contextPath = TomcatContexts.normalize(value)
                fireEditorStateChanged()
            }
        },
    )
    private val table = TableView(model)
    private lateinit var component: JComponent

    override fun createEditor(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { button ->
                val existing = model.items.map { it.artifactName }.toSet()
                val available = WebArtifacts.all(project).filter { it.name !in existing }
                if (available.isEmpty()) return@setAddAction
                JBPopupFactory.getInstance().createPopupChooserBuilder(available)
                    .setTitle("Deploy at Server Startup")
                    .setRenderer(SimpleListCellRenderer.create<Artifact> { label, value, _ ->
                        label.text = value.name
                        label.icon = value.artifactType.icon
                    })
                    .setItemChosenCallback { artifact ->
                        model.addRow(Row(artifact.name, TomcatDeployment.defaultContextPath(artifact.name)))
                        BuildArtifactsBeforeRunTaskProvider.setBuildArtifactBeforeRunOption(component, project, artifact, true)
                        fireEditorStateChanged()
                    }
                    .createPopup()
                    .show(button.preferredPopupPoint)
            }
            .setRemoveAction {
                val selected = table.selectedObjects.toList()
                for (row in selected) {
                    ArtifactManager.getInstance(project).findArtifact(row.artifactName)?.let {
                        BuildArtifactsBeforeRunTaskProvider.setBuildArtifactBeforeRunOption(component, project, it, false)
                    }
                    model.removeRow(model.items.indexOf(row))
                }
                fireEditorStateChanged()
            }
            .createPanel()
        component = panel {
            row { label("Deploy at the server startup:") }
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row {
                comment("Artifacts of type 'Web Application: Exploded' or 'Web Application: Archive'. " +
                    "Create them in File | Project Structure | Artifacts, or let the plugin create them for Maven/Gradle web modules.")
            }
        }
        return component
    }

    override fun resetEditorFrom(c: TomcatRunConfiguration) {
        model.items = c.deployments.map { Row(it.artifactName, it.contextPath) }.toMutableList()
    }

    override fun applyEditorTo(c: TomcatRunConfiguration) {
        if (table.isEditing) table.stopEditing()
        c.deployments = model.items.map { TomcatDeployment(it.artifactName, TomcatContexts.normalize(it.contextPath)) }
    }
}
