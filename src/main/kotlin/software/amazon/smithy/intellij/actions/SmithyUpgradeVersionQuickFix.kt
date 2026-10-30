package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import software.amazon.smithy.intellij.SmithyElementFactory
import software.amazon.smithy.intellij.SmithyFile

/**
 * An [IntentionAction] to upgrade (or add) the `$version` control statement to a minimum IDL version.
 *
 * This is offered when a file uses syntax introduced by a newer IDL version than the one declared (or when no
 * `$version` control is present at all).
 *
 * @since 1.0
 */
class SmithyUpgradeVersionQuickFix(private val version: String) : BaseIntentionAction() {
    override fun getText() = "Upgrade to \$version: \"$version\""
    override fun getFamilyName() = "Upgrade Smithy IDL version"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = file is SmithyFile
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val model = (file as? SmithyFile)?.model ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val existing = model.control.firstOrNull { it.name == "version" }
            if (existing != null) {
                existing.value.replace(SmithyElementFactory.createControl(project, "version", "\"$version\"").value)
            } else {
                val control = SmithyElementFactory.createControl(project, "version", "\"$version\"")
                val anchor = model.firstChild
                if (anchor != null) model.addBefore(control, anchor) else model.add(control)
            }
        }
    }
}
