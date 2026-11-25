package io.github.hoangtu.tomcat.run

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.dashboard.RunDashboardCustomizationBuilder
import com.intellij.execution.dashboard.RunDashboardCustomizer
import com.intellij.execution.dashboard.RunDashboardDefaultTypesProvider
import com.intellij.execution.dashboard.RunDashboardManager
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.SimpleTextAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows "Tomcat Server" run configurations in the Services tool window by default (like Ultimate's application servers). */
class TomcatRunDashboardTypesProvider : RunDashboardDefaultTypesProvider {
    override fun getDefaultTypeIds(project: Project): Collection<String> = listOf(TomcatConfigurationType.ID)
}

/**
 * The default types are only used by projects whose Services settings were never saved; for existing projects
 * add the Tomcat type once (the user can remove it again from the Services view).
 */
class TomcatRunDashboardStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(KEY)) return
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val manager = RunDashboardManager.getInstance(project)
            if (TomcatConfigurationType.ID !in manager.types) {
                manager.types = manager.types + TomcatConfigurationType.ID
            }
            properties.setValue(KEY, true)
        }
    }

    private companion object {
        const val KEY = "community.tomcat.services.view.registered"
    }
}

/** Services view node: "Tomcat  :8080  /app  /admin" with links opening the deployed applications in the browser. */
class TomcatRunDashboardCustomizer : RunDashboardCustomizer() {
    override fun isApplicable(settings: RunnerAndConfigurationSettings, descriptor: RunContentDescriptor?): Boolean =
        settings.configuration is TomcatRunConfiguration

    override fun updatePresentation(
        builder: RunDashboardCustomizationBuilder,
        settings: RunnerAndConfigurationSettings,
        descriptor: RunContentDescriptor?,
    ): Boolean {
        val configuration = settings.configuration as? TomcatRunConfiguration ?: return false
        builder.setIcon(TomcatIcons.Tomcat)
        val running = descriptor?.processHandler?.let { handler ->
            TomcatInstances.getInstance(configuration.project).findByHandler(handler)
        }
        if (running == null) {
            builder.addText("  :${configuration.httpPort}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            return true
        }
        builder.addText("  :${configuration.httpPort}", SimpleTextAttributes.GRAY_ATTRIBUTES)
        for (deployment in running.deployments) {
            val url = "http://localhost:${configuration.httpPort}${if (deployment.contextPath == "/") "/" else deployment.contextPath + "/"}"
            builder.addText("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            builder.addLink(deployment.contextPath) { BrowserUtil.browse(url) }
        }
        return true
    }
}
