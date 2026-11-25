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
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.execution.dashboard.RunDashboardRunConfigurationNode
import com.intellij.execution.services.ServiceViewActionUtils
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.packaging.impl.artifacts.ArtifactUtil
import com.intellij.ui.SimpleListCellRenderer

/** A set of deployments of one running Tomcat the user can update. */
private class UpdateTarget(val label: String, val deployments: List<ResolvedDeployment>, val wholeServer: Boolean)

/**
 * "Update Tomcat Application" (Ctrl+F10). With one deployed application it applies the "On 'Update' action" policy
 * right away; with several it asks which application(s) to update and how ([UpdateTomcatApplicationChooserAction]).
 */
open class UpdateTomcatApplicationAction : AnAction(), DumbAware {
    protected open val alwaysAsk: Boolean = false

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun candidates(e: AnActionEvent): List<RunningTomcat> {
        val project = e.project ?: return emptyList()
        val instances = TomcatInstances.getInstance(project)
        val descriptor = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)
        instances.findByHandler(descriptor?.processHandler)?.let { return listOf(it) }
        // Services view: the selected run configuration node(s)
        val nodes = ServiceViewActionUtils.getTargets(e, RunDashboardRunConfigurationNode::class.java)
        if (nodes.isNotEmpty()) {
            return nodes.mapNotNull { node -> instances.findByHandler(node.descriptor?.processHandler) }
        }
        val selected = RunContentManager.getInstance(project).selectedContent
        instances.findByHandler(selected?.processHandler)?.let { return listOf(it) }
        return instances.running
    }

    override fun update(e: AnActionEvent) {
        val list = candidates(e)
        e.presentation.isEnabledAndVisible = list.isNotEmpty()
        val suffix = if (alwaysAsk) "…" else ""
        e.presentation.text = if (list.size == 1) "Update '${list[0].name}' Application$suffix" else "Update Tomcat Application$suffix"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val list = candidates(e)
        val module = currentModule(e)
        when {
            list.isEmpty() -> return
            list.size == 1 -> perform(project, list[0], module, e.dataContext)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(list)
                .setTitle("Update Application")
                .setRenderer(SimpleListCellRenderer.create("") { it.name })
                .setItemChosenCallback { perform(project, it, module, e.dataContext) }
                .createPopup()
                .showInBestPositionFor(e.dataContext)
        }
    }

    /** Module of the file currently being edited / selected (used to suggest the matching application). */
    private fun currentModule(e: AnActionEvent): Module? {
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: FileEditorManager.getInstance(project).selectedEditor?.file
            ?: return null
        return ModuleUtilCore.findModuleForFile(file, project)
    }

    private fun perform(project: Project, instance: RunningTomcat, module: Module?, dataContext: DataContext) {
        val instances = TomcatInstances.getInstance(project)
        if (!alwaysAsk && instance.deployments.size <= 1) {
            instances.update(instance, instance.configuration.updatePolicy)
            return
        }

        val targets = mutableListOf<UpdateTarget>()
        targets += UpdateTarget(
            if (instance.deployments.size == 1) instance.deployments[0].let { "${it.artifactName}  (${it.contextPath})" }
            else "All applications (${instance.deployments.size})",
            instance.deployments, wholeServer = true,
        )
        if (module != null && instance.deployments.size > 1) {
            val containing = ReadAction.compute<Set<String>, RuntimeException> {
                ArtifactUtil.getArtifactsContainingModuleOutput(module).map { it.name }.toSet()
            }
            val matching = instance.deployments.filter { it.artifactName in containing }
            if (matching.isNotEmpty() && matching.size < instance.deployments.size) {
                targets += UpdateTarget("Applications containing module '${module.name}'", matching, wholeServer = false)
            }
        }
        if (instance.deployments.size > 1) {
            instance.deployments.forEach { targets += UpdateTarget("${it.artifactName}  (${it.contextPath})", listOf(it), wholeServer = false) }
        }

        val defaultPolicy = instance.configuration.updatePolicy
        val step = object : BaseListPopupStep<UpdateTarget>("Update '${instance.name}'", targets) {
            override fun getTextFor(value: UpdateTarget): String = value.label
            override fun hasSubstep(selectedValue: UpdateTarget): Boolean = true
            override fun onChosen(selectedValue: UpdateTarget, finalChoice: Boolean): PopupStep<*> {
                val policies = if (selectedValue.wholeServer) UpdatePolicy.forUpdateAction
                else UpdatePolicy.forUpdateAction - UpdatePolicy.RESTART
                return object : BaseListPopupStep<UpdatePolicy>("Action", policies) {
                    init {
                        defaultOptionIndex = policies.indexOf(defaultPolicy).coerceAtLeast(0)
                    }

                    override fun getTextFor(value: UpdatePolicy): String =
                        value.presentableName + if (value == defaultPolicy) "  (default)" else ""

                    override fun onChosen(policy: UpdatePolicy, finalChoice: Boolean): PopupStep<*>? = doFinalStep {
                        instances.update(instance, policy, deployments = selectedValue.deployments)
                    }
                }
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(step)
        // the module-specific entry is the most likely choice when editing a file of that module
        if (targets.size > 1 && !targets[1].wholeServer && targets[1].label.startsWith("Applications containing")) {
            popup.listStep.let { (it as BaseListPopupStep<*>).defaultOptionIndex = 1 }
        }
        popup.showInBestPositionFor(dataContext)
    }

    companion object {
        const val ID = "CommunityTomcat.UpdateApplication"
    }
}

/** "Update Tomcat Application…": always asks which application(s) to update and which update action to use. */
class UpdateTomcatApplicationChooserAction : UpdateTomcatApplicationAction() {
    override val alwaysAsk: Boolean = true

    companion object {
        const val ID = "CommunityTomcat.UpdateApplicationChooser"
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
