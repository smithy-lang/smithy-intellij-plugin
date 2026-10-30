package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import software.amazon.smithy.intellij.SmithyElementFactory
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.SmithyImportOptimizer
import software.amazon.smithy.intellij.psi.SmithyContainerMember
import software.amazon.smithy.intellij.psi.SmithyTrait

/**
 * An [IntentionAction] which rewrites an explicit `@smithy.protocols#idx(n)` trait application on a structure/union
 * member as the equivalent IDL 2.1 member index shorthand (`n. member`), removing the trait and, when it becomes
 * unused, the `use smithy.protocols#idx` import.
 *
 * @since 1.0
 */
class SmithyUseMemberIndexShorthandQuickFix(private val trait: SmithyTrait) : BaseIntentionAction() {
    override fun getText() = "Convert to member index shorthand"
    override fun getFamilyName() = "Use member index shorthand"
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile) = true
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val member = eligibleMember(trait) ?: return
        val index = traitIndex(trait) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            //Insert the "n." prefix before the member name.
            member.addBefore(SmithyElementFactory.createMemberIndex(project, index), member.nameIdentifier)
            //Remove the explicit @idx trait and the whitespace separating it from the next element.
            (trait.nextSibling as? PsiWhiteSpace)?.delete()
            trait.delete()
            //Drop the smithy.protocols#idx import if nothing else needs it.
            (file as? SmithyFile)?.let { SmithyImportOptimizer.removeUnusedImports(it) }
        }
    }

    companion object {
        /**
         * Returns the member that [trait] (an `@idx` application) belongs to when it is eligible for conversion to the
         * shorthand, or null otherwise. Eligible when: the trait resolves to `smithy.protocols#idx`, is applied to a
         * structure/union member that has no existing member index, and carries a single positive integer value.
         */
        fun eligibleMember(trait: SmithyTrait): SmithyContainerMember? {
            if (trait.resolve()?.shapeId != "smithy.protocols#idx") return null
            val member = trait.parent as? SmithyContainerMember ?: return null
            if (member.memberIndex != null) return null
            if (member.enclosingShape.type.let { it != "structure" && it != "union" }) return null
            if (traitIndex(trait) == null) return null
            return member
        }

        private fun traitIndex(trait: SmithyTrait): Int? {
            val value = trait.body?.value?.asNumber() ?: return null
            //Must be a positive integer to be expressible as a member index.
            if (value.stripTrailingZeros().scale() > 0) return null
            val i = value.toInt()
            return if (i >= 1) i else null
        }
    }
}
