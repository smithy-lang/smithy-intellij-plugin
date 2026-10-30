package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import software.amazon.smithy.intellij.psi.SmithyMemberIndex

/**
 * An [IntentionAction] to remove a member index shorthand (the `N.` prefix which desugars to `@smithy.protocols#idx`).
 *
 * @since 1.0
 */
data class SmithyRemoveMemberIndexQuickFix(val memberIndex: SmithyMemberIndex) : BaseIntentionAction() {
    override fun getText() = "Remove member index '${memberIndex.text}'"
    override fun getFamilyName() = "Remove member index"
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile) = true
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        WriteCommandAction.runWriteCommandAction(project) {
            //Note: the index is separated from the member name by whitespace, which must be removed with it.
            (memberIndex.nextSibling as? PsiWhiteSpace)?.delete()
            memberIndex.delete()
        }
    }
}
