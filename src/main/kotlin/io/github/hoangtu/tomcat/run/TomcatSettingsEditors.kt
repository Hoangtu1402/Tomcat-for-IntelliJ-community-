package io.github.hoangtu.tomcat.run

import com.intellij.diagnostic.logging.LogConfigurationPanel
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.ui.DefaultJreSelector
import com.intellij.execution.ui.JrePathEditor
import com.intellij.ide.browsers.BrowserSelector
import com.intellij.ide.browsers.WebBrowserManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTaskProvider
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.hoangtu.tomcat.artifact.WebArtifacts
import io.github.hoangtu.tomcat.server.TomcatCommunitySettings
import io.github.hoangtu.tomcat.server.TomcatServersConfigurable
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/** Numeric port field; empty = 0 (used for the optional HTTPS/AJP ports). */
private class PortField(private val optional: Boolean) : JBTextField(6) {
    var port: Int
        get() = text.trim().toIntOrNull() ?: 0
        set(value) {
            text = if (value == 0 && optional) "" else value.toString()
        }
}

/** "Server" tab, laid out like the Tomcat run configuration of IntelliJ IDEA Ultimate. */
class TomcatServerSettingsEditor(private val project: Project) : SettingsEditor<TomcatRunConfiguration>() {
    private val serverCombo = ComboBox<String>()
    private val versionLabel = JBLabel()
    private val openBrowser = JBCheckBox("After launch")
    private val browserSelector = BrowserSelector()
    private val url = JBTextField()
    private val vmOptions = RawCommandLineEditor()
    private val updatePolicy = ComboBox(DefaultComboBoxModel(UpdatePolicy.forUpdateAction.toTypedArray()))
    private val showDialog = JBCheckBox("Show dialog")
    private val frameDeactivation = ComboBox(DefaultComboBoxModel(UpdatePolicy.forFrameDeactivation.toTypedArray()))
    private val jre = JrePathEditor(DefaultJreSelector.projectSdk(project))
    private val httpPort = PortField(false)
    private val httpsPort = PortField(true)
    private val jmxPort = PortField(false)
    private val ajpPort = PortField(true)
    private val deployTomcatApps = JBCheckBox("Deploy applications configured in Tomcat instance")
    private val preserveSessions = JBCheckBox("Preserve sessions across restarts and redeploys")

    init {
        serverCombo.addActionListener { updateVersionLabel() }
        openBrowser.addActionListener { updateEnabled() }
        var lastPort = 8080
        httpPort.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val newPort = httpPort.port
                if (newPort <= 0) return
                val prefix = "http://localhost:$lastPort"
                if (url.text.startsWith(prefix)) url.text = "http://localhost:$newPort" + url.text.removePrefix(prefix)
                lastPort = newPort
            }
        })
    }

    private fun updateEnabled() {
        browserSelector.mainComponent.isEnabled = openBrowser.isSelected
        url.isEnabled = openBrowser.isSelected
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
            else -> "Tomcat ${installation.version ?: "?"} (${if (installation.isJakarta) "jakarta.*" else "javax.*"}" +
                (installation.requiredJavaVersion?.let { ", Java $it+" } ?: "") + ") — ${installation.home}"
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
        group("Open browser") {
            row {
                cell(openBrowser)
                cell(browserSelector.mainComponent)
            }
            row("URL:") { cell(url).align(AlignX.FILL) }
        }
        row("VM options:") { cell(vmOptions).align(AlignX.FILL) }
        row("On 'Update' action:") {
            cell(updatePolicy)
            cell(showDialog)
        }
        row("On frame deactivation:") { cell(frameDeactivation) }
        row { cell(jre).align(AlignX.FILL) }
        group("Tomcat Server Settings") {
            row("HTTP port:") {
                cell(httpPort)
                cell(deployTomcatApps)
            }
            row("HTTPs port:") {
                cell(httpsPort)
                cell(preserveSessions)
            }
            row("JMX port:") { cell(jmxPort) }
            row("AJP port:") { cell(ajpPort) }
        }
    }

    override fun resetEditorFrom(c: TomcatRunConfiguration) {
        reloadServers(c.serverName)
        openBrowser.isSelected = c.openBrowser
        browserSelector.setSelected(c.browserId?.let { WebBrowserManager.getInstance().findBrowserById(it) })
        url.text = c.browserUrl
        vmOptions.text = c.vmOptions
        updatePolicy.selectedItem = c.updatePolicy
        showDialog.isSelected = c.showUpdateDialog
        frameDeactivation.selectedItem = if (c.frameDeactivationPolicy in UpdatePolicy.forFrameDeactivation) c.frameDeactivationPolicy else UpdatePolicy.NOTHING
        jre.setPathOrName(c.alternativeJrePath.ifBlank { null }, c.useAlternativeJre)
        httpPort.port = c.httpPort
        httpsPort.port = c.httpsPort
        jmxPort.port = c.jmxPort
        ajpPort.port = c.ajpPort
        deployTomcatApps.isSelected = c.deployTomcatApps
        preserveSessions.isSelected = c.preserveSessions
        updateEnabled()
    }

    override fun applyEditorTo(c: TomcatRunConfiguration) {
        c.serverName = serverCombo.selectedItem as String?
        c.openBrowser = openBrowser.isSelected
        c.browserId = browserSelector.selectedBrowserId
        c.browserUrl = url.text.trim()
        c.vmOptions = vmOptions.text
        c.updatePolicy = updatePolicy.selectedItem as UpdatePolicy
        c.showUpdateDialog = showDialog.isSelected
        c.frameDeactivationPolicy = frameDeactivation.selectedItem as UpdatePolicy
        c.useAlternativeJre = jre.isAlternativeJreSelected
        c.alternativeJrePath = jre.jrePathOrName ?: ""
        c.httpPort = httpPort.port
        c.httpsPort = httpsPort.port
        c.jmxPort = jmxPort.port
        c.ajpPort = ajpPort.port
        c.deployTomcatApps = deployTomcatApps.isSelected
        c.preserveSessions = preserveSessions.isSelected
    }
}

/** "Deployment" tab: artifact list + "Application context" of the selected artifact (as in Ultimate). */
class TomcatDeploymentSettingsEditor(private val project: Project) : SettingsEditor<TomcatRunConfiguration>() {
    private class Item(val artifactName: String, var contextPath: String, val icon: Icon?)

    private val listModel = CollectionListModel<Item>()
    private val list = JBList(listModel).apply {
        cellRenderer = SimpleListCellRenderer.create { label, value, _ ->
            label.text = value.artifactName
            label.icon = value.icon
        }
    }
    private val contextField = JBTextField()
    private var updatingContext = false
    private lateinit var component: JComponent

    private fun iconOf(name: String): Icon? = WebArtifacts.read { WebArtifacts.find(project, name)?.artifactType?.icon }

    init {
        list.addListSelectionListener {
            updatingContext = true
            contextField.text = list.selectedValue?.contextPath ?: ""
            contextField.isEnabled = list.selectedValue != null
            updatingContext = false
        }
        contextField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (updatingContext) return
                list.selectedValue?.contextPath = contextField.text
                fireEditorStateChanged()
            }
        })
    }

    override fun createEditor(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(list)
            .setAddAction { button ->
                val existing = listModel.items.map { it.artifactName }.toSet()
                // snapshot (name, icon) inside a read action: the popup renders on EDT
                val available = WebArtifacts.read {
                    WebArtifacts.all(project).filter { it.name !in existing }.map { it.name to it.artifactType.icon }
                }
                if (available.isEmpty()) return@setAddAction
                JBPopupFactory.getInstance().createPopupChooserBuilder(available)
                    .setTitle("Artifact to Deploy")
                    .setRenderer(SimpleListCellRenderer.create<Pair<String, Icon>> { label, value, _ ->
                        label.text = value.first
                        label.icon = value.second
                    })
                    .setItemChosenCallback { (name, icon) ->
                        listModel.add(Item(name, TomcatDeployment.defaultContextPath(name), icon))
                        list.selectedIndex = listModel.size - 1
                        WebArtifacts.find(project, name)?.let {
                            BuildArtifactsBeforeRunTaskProvider.setBuildArtifactBeforeRunOption(component, project, it, true)
                        }
                        fireEditorStateChanged()
                    }
                    .createPopup()
                    .show(button.preferredPopupPoint)
            }
            .setRemoveAction {
                for (item in list.selectedValuesList) {
                    WebArtifacts.find(project, item.artifactName)?.let {
                        BuildArtifactsBeforeRunTaskProvider.setBuildArtifactBeforeRunOption(component, project, it, false)
                    }
                    listModel.remove(item)
                }
                fireEditorStateChanged()
            }
            .createPanel()
        component = panel {
            row { label("Deploy at the server startup") }
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row("Application context:") { cell(contextField).align(AlignX.FILL) }
            row {
                comment("Artifacts of type 'Web Application: Exploded' or 'Web Application: Archive'. " +
                    "Create them in File | Project Structure | Artifacts; Maven/Gradle web modules get them automatically.")
            }
        }
        return component
    }

    override fun resetEditorFrom(c: TomcatRunConfiguration) {
        listModel.replaceAll(c.deployments.map { Item(it.artifactName, it.contextPath, iconOf(it.artifactName)) })
        if (listModel.size > 0) list.selectedIndex = 0 else contextField.isEnabled = false
    }

    override fun applyEditorTo(c: TomcatRunConfiguration) {
        c.deployments = listModel.items.map { TomcatDeployment(it.artifactName, TomcatContexts.normalize(it.contextPath)) }
    }
}

/**
 * "Logs" tab: the standard log panel of the platform (log files shown in the console, "Save console output to file",
 * as in Ultimate) plus where Tomcat writes its own log files and whether all logs are reset on every start.
 */
class TomcatLogsSettingsEditor(private val project: Project) : SettingsEditor<TomcatRunConfiguration>() {
    private val platformPanel = LogConfigurationPanel<TomcatRunConfiguration>().also { Disposer.register(this, it) }
    private val logDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Tomcat Log Directory"))
    }
    private val clearLogs = JBCheckBox("Clear old Tomcat log files on every start")

    override fun createEditor(): JComponent = panel {
        row { cell(platformPanel.component).align(Align.FILL) }.resizableRow()
        group("Tomcat Log Files (optional)") {
            row("Log directory:") { cell(logDirectory).align(AlignX.FILL) }
                .rowComment("Leave empty to keep the default. When set, catalina.*.log, localhost.*.log and access logs are written there in real time.")
            row { cell(clearLogs) }
                .rowComment("Off by default. The console and 'Save console output to file' always start empty on every run.")
            row {
                comment("These options never stop Tomcat from starting: if a directory cannot be used, a warning is printed and the default is used.")
            }
        }
    }

    override fun resetEditorFrom(c: TomcatRunConfiguration) {
        platformPanel.resetFrom(c)
        logDirectory.text = FileUtil.toSystemDependentName(c.logDirectory)
        clearLogs.isSelected = c.clearLogsOnStart
    }

    override fun applyEditorTo(c: TomcatRunConfiguration) {
        platformPanel.applyTo(c)
        // "Save console output to file" with a relative path: resolve against the project directory
        val output = c.outputFilePath
        val base = project.basePath
        try {
            if (!output.isNullOrBlank() && base != null && !Path.of(FileUtil.toSystemDependentName(output)).isAbsolute) {
                c.setFileOutputPath(FileUtil.toSystemIndependentName(Path.of(base).resolve(output).normalize().toString()))
            }
        } catch (_: java.nio.file.InvalidPathException) {
            // keep the text as typed; writing the file will report the problem
        }
        c.logDirectory = FileUtil.toSystemIndependentName(logDirectory.text.trim())
        c.clearLogsOnStart = clearLogs.isSelected
    }
}

/** "Startup/Connection" tab: environment variables passed to Tomcat. */
class TomcatStartupSettingsEditor : SettingsEditor<TomcatRunConfiguration>() {
    private val envs = EnvironmentVariablesComponent()

    override fun createEditor(): JComponent = panel {
        row { cell(envs).align(AlignX.FILL) }
        row {
            comment("Tomcat is started with <code>org.apache.catalina.startup.Bootstrap</code> for both Run and Debug; " +
                "the debugger is attached automatically.")
        }
    }

    override fun resetEditorFrom(c: TomcatRunConfiguration) {
        envs.envData = c.envs
    }

    override fun applyEditorTo(c: TomcatRunConfiguration) {
        c.envs = envs.envData
    }
}
