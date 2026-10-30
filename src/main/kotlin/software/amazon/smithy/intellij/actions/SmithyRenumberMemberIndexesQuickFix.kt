package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import software.amazon.smithy.intellij.SmithyElementFactory
import software.amazon.smithy.intellij.psi.SmithyContainerMember
import software.amazon.smithy.intellij.psi.SmithyContainerShape
import software.amazon.smithy.intellij.psi.SmithyElidedMember
import software.amazon.smithy.intellij.psi.SmithyMemberIndex

/**
 * An [IntentionAction] which renumbers the member index shorthands of a structure/union so they are contiguous starting
 * at 1, in declaration order. Only members that already carry an index are renumbered.
 *
 * @since 1.0
 */
class SmithyRenumberMemberIndexesQuickFix(private val shape: SmithyContainerShape) : BaseIntentionAction() {
    override fun getText() = "Renumber member indexes"
    override fun getFamilyName() = "Renumber member indexes"
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile) = true
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val indexes = memberIndexes(shape)
        WriteCommandAction.runWriteCommandAction(project) {
            var changed = false
            indexes.forEachIndexed { i, existing ->
                if (existing.index != i + 1) {
                    existing.replace(SmithyElementFactory.createMemberIndex(project, i + 1))
                    changed = true
                }
            }
            //Replacing an index node can disturb the single space before the member (notably for elided members), so
            //let the formatter normalize spacing on the affected shape rather than doing fragile whitespace surgery.
            if (changed) CodeStyleManager.getInstance(project).reformat(shape)
        }
    }

    companion object {
        /**
         * The [SmithyMemberIndex] element of a member, whether an explicit or an elided member. Both forms accept the
         * IDL 2.1 `n.` shorthand, so both must be considered for index validation and renumbering.
         */
        private val PsiElement.memberIndexElement: SmithyMemberIndex?
            get() = when (this) {
                is SmithyContainerMember -> memberIndex
                is SmithyElidedMember -> memberIndex
                else -> null
            }

        /** The index elements declared on [shape]'s members, in declaration order. */
        fun memberIndexes(shape: SmithyContainerShape): List<SmithyMemberIndex> =
            shape.body.members.mapNotNull { (it as? PsiElement)?.memberIndexElement }

        /** The index values declared on [shape]'s members, in declaration order; omits unparseable values. */
        fun declaredIndexes(shape: SmithyContainerShape): List<Int> = memberIndexes(shape).mapNotNull { it.index }

        /** True when the shape has indexed members whose indexes are not exactly the contiguous set 1..N. */
        fun needsRenumber(shape: SmithyContainerShape): Boolean {
            val indexes = declaredIndexes(shape)
            if (indexes.isEmpty()) return false
            return indexes.sorted() != (1..indexes.size).toList()
        }
    }
}
