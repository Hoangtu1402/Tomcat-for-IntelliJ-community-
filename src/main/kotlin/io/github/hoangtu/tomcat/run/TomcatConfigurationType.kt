package io.github.hoangtu.tomcat.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationSingletonPolicy
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object TomcatIcons {
    @JvmField
    val Tomcat: Icon = IconLoader.getIcon("/icons/tomcat.svg", TomcatIcons::class.java)
}

/**
 * "Tomcat Server" run configuration type. The ids are the ones used by IntelliJ IDEA Ultimate, so the
 * Tomcat run configurations stored in an existing project (.idea/workspace.xml, .idea/runConfigurations) are picked up.
 */
class TomcatConfigurationType : ConfigurationType {
    private val localFactory = TomcatLocalConfigurationFactory(this)

    override fun getDisplayName(): String = "Tomcat Server"
    override fun getConfigurationTypeDescription(): String = "Run and debug web applications on a local Apache Tomcat"
    override fun getIcon(): Icon = TomcatIcons.Tomcat
    override fun getId(): String = ID
    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(localFactory)
    override fun getHelpTopic(): String? = null

    companion object {
        const val ID = "#com.intellij.j2ee.web.tomcat.TomcatRunConfigurationFactory"

        fun getInstance(): TomcatConfigurationType = ConfigurationTypeUtil.findConfigurationType(TomcatConfigurationType::class.java)
    }
}

class TomcatLocalConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "Local"
    override fun getName(): String = "Local"
    override fun createTemplateConfiguration(project: Project): RunConfiguration = TomcatRunConfiguration(project, this, "Tomcat")
    override fun getSingletonPolicy(): RunConfigurationSingletonPolicy = RunConfigurationSingletonPolicy.SINGLE_INSTANCE_ONLY
    override fun getOptionsClass(): Class<out BaseState>? = null
    override fun isEditableInDumbMode(): Boolean = true
}
