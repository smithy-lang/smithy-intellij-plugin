package software.amazon.smithy.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import software.amazon.smithy.intellij.SmithyDependencySynchronizer

/**
 * Manually triggers a refresh of every Smithy module's resolved Maven dependencies.
 */
class SmithyReimportDependenciesAction : AnAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.PROJECT) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        SmithyDependencySynchronizer.syncProjectInBackground(project)
    }
}
