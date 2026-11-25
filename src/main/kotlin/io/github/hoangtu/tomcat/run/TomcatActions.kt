package io.github.hoangtu.tomcat.run

import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.IdeFrame

/** "Update Tomcat Application" (Ctrl+F10): applies the "On 'Update' action" policy of the run configuration. */
class UpdateTomcatApplicationAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun candidates(e: AnActionEvent): List<RunningTomcat> {
        val project = e.project ?: return emptyList()
        val instances = TomcatInstances.getInstance(project)
        val descriptor = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)
        instances.findByHandler(descriptor?.processHandler)?.let { return listOf(it) }
        val selected = RunContentManager.getInstance(project).selectedContent
        instances.findByHandler(selected?.processHandler)?.let { return listOf(it) }
        return instances.running
    }

    override fun update(e: AnActionEvent) {
        val list = candidates(e)
        e.presentation.isEnabledAndVisible = list.isNotEmpty()
        if (list.size == 1) e.presentation.text = "Update '${list[0].name}' Application"
        else e.presentation.text = "Update Tomcat Application"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val list = candidates(e)
        when {
            list.isEmpty() -> return
            list.size == 1 -> perform(project, list[0])
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(list)
                .setTitle("Update Application")
                .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.name })
                .setItemChosenCallback { perform(project, it) }
                .createPopup()
                .showInBestPositionFor(e.dataContext)
        }
    }

    private fun perform(project: Project, instance: RunningTomcat) {
        TomcatInstances.getInstance(project).update(instance, instance.configuration.updatePolicy)
    }

    companion object {
        const val ID = "CommunityTomcat.UpdateApplication"
    }
}

/** Applies the "On frame deactivation" policy (update resources / classes) when the IDE window loses focus. */
class TomcatFrameDeactivationListener : ApplicationActivationListener {
    override fun applicationDeactivated(ideFrame: IdeFrame) {
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed || CompilerManager.getInstance(project).isCompilationActive) continue
            val instances = project.getServiceIfCreated(TomcatInstances::class.java) ?: continue
            for (instance in instances.running) {
                val policy = instance.configuration.frameDeactivationPolicy
                if (policy == UpdatePolicy.UPDATE_RESOURCES || policy == UpdatePolicy.UPDATE_CLASSES_AND_RESOURCES) {
                    instances.update(instance, policy, silent = true)
                }
            }
        }
    }
}
