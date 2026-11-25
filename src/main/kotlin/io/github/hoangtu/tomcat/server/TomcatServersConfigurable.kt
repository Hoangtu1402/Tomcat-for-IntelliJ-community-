package io.github.hoangtu.tomcat.server

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.nio.file.Path
import javax.swing.JComponent

/** Settings | Build, Execution, Deployment | Tomcat Servers (Community). */
class TomcatServersConfigurable : Configurable {
    private val model = ListTableModel<TomcatCommunitySettings.ServerEntry>(
        object : ColumnInfo<TomcatCommunitySettings.ServerEntry, String>("Name") {
            override fun valueOf(item: TomcatCommunitySettings.ServerEntry) = item.name
            override fun isCellEditable(item: TomcatCommunitySettings.ServerEntry) = true
            override fun setValue(item: TomcatCommunitySettings.ServerEntry, value: String) {
                item.name = value.trim()
            }
        },
        object : ColumnInfo<TomcatCommunitySettings.ServerEntry, String>("Tomcat Home") {
            override fun valueOf(item: TomcatCommunitySettings.ServerEntry) = item.home
        },
        object : ColumnInfo<TomcatCommunitySettings.ServerEntry, String>("Version") {
            override fun valueOf(item: TomcatCommunitySettings.ServerEntry): String {
                val installation = TomcatInstallation(Path.of(item.home))
                if (!installation.isValid) return "invalid"
                val version = installation.version ?: return "?"
                return version + (installation.requiredJavaVersion?.let { "  (Java $it+)" } ?: "")
            }
        },
    )
    private val table = TableView(model)
    private val autoConfigure = JBCheckBox("Automatically create Web facet and ':war exploded' / ':war' artifacts for Maven/Gradle web modules")

    override fun getDisplayName(): String = "Tomcat Servers"

    override fun createComponent(): JComponent {
        val tablePanel = ToolbarDecorator.createDecorator(table)
            .setAddAction { addServer() }
            .createPanel()
        return panel {
            row { cell(tablePanel).align(Align.FILL) }.resizableRow()
                .rowComment("Local Tomcat installations (CATALINA_HOME) used by 'Tomcat Server' run configurations. Tomcat 7 – 11 are supported.")
            row { cell(autoConfigure) }
        }
    }

    private fun addServer() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Tomcat Home Directory")
        val dir = FileChooser.chooseFile(descriptor, null, null) ?: return
        val home = Path.of(dir.path)
        TomcatInstallation.validate(home)?.let {
            Messages.showErrorDialog(table, it, "Invalid Tomcat Home")
            return
        }
        val base = TomcatInstallation(home).defaultName
        var name = base
        var i = 2
        while (model.items.any { it.name == name }) name = "$base (${i++})"
        model.addRow(TomcatCommunitySettings.ServerEntry().also { it.name = name; it.home = home.toString() })
    }

    private fun current(): List<Pair<String, String>> = model.items.map { it.name to it.home }

    override fun isModified(): Boolean {
        val settings = TomcatCommunitySettings.getInstance()
        return current() != settings.servers.map { it.name to it.home } || autoConfigure.isSelected != settings.autoConfigureWebModules
    }

    override fun apply() {
        if (table.isEditing) table.stopEditing()
        val names = model.items.map { it.name }
        if (names.any { it.isBlank() }) throw ConfigurationException("Server name must not be empty")
        if (names.toSet().size != names.size) throw ConfigurationException("Server names must be unique")
        val settings = TomcatCommunitySettings.getInstance()
        settings.servers = model.items
        settings.autoConfigureWebModules = autoConfigure.isSelected
    }

    override fun reset() {
        val settings = TomcatCommunitySettings.getInstance()
        model.items = settings.servers.map { it.copy() }.toMutableList()
        autoConfigure.isSelected = settings.autoConfigureWebModules
    }
}
