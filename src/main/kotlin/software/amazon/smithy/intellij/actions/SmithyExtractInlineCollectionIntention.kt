package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import software.amazon.smithy.intellij.SmithyElementFactory
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.SmithyVersion
import software.amazon.smithy.intellij.index.SmithyDefinedShapeIdIndex
import software.amazon.smithy.intellij.psi.SmithyInlineListTarget
import software.amazon.smithy.intellij.psi.SmithyInlineMapTarget
import software.amazon.smithy.intellij.psi.SmithyInlineTargets
import software.amazon.smithy.intellij.psi.SmithyMemberTarget
import software.amazon.smithy.intellij.psi.SmithyShape
import software.amazon.smithy.intellij.psi.SmithyShapeTarget

/**
 * An [IntentionAction] which extracts an IDL 2.1 inline collection (`[Target]` / `{Key: Value}`) into an explicit
 * top-level `list`/`map` shape and points the member at it. This is the "outgrowing inline syntax" migration from the
 * design: inline collections cannot carry shape-level traits (`@sparse`, `@uniqueItems`, `@length` on the collection),
 * so authors needing those must use an explicit shape.
 *
 * Offered as an on-demand intention (no highlight) since it is only situationally useful.
 *
 * @since 1.0
 */
class SmithyExtractInlineCollectionIntention : BaseIntentionAction() {
    override fun getText() = "Extract inline collection to a named shape"
    override fun getFamilyName() = "Extract inline collection"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val target = targetAt(editor, file) ?: return false
        //Only the outermost inline target; a nested one is extracted as part of its parent.
        if ((target.parent as? SmithyMemberTarget)?.parent is SmithyInlineListTarget) return false
        if ((target.parent as? SmithyMemberTarget)?.parent is SmithyInlineMapTarget) return false
        val version = (file as? SmithyFile)?.model?.version
        return version != null && SmithyVersion.compare(version, "2.1") >= 0
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val target = targetAt(editor, file) ?: return
        val memberTarget = target.parent as? SmithyMemberTarget ?: return
        val structure = PsiTreeUtil.getParentOfType(target, SmithyShape::class.java) ?: return
        val model = (file as? SmithyFile)?.model ?: return
        val scope = file.resolveScope
        val name = uniqueName(SmithyInlineTargets.readableName(target), scope)
        val declaration = shapeDeclaration(name, target) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            val shape = SmithyElementFactory.createShape(project, declaration)
            model.addAfter(shape, structure)
            memberTarget.replace(SmithyElementFactory.createMemberTarget(project, name))
        }
    }

    private fun targetAt(editor: Editor?, file: PsiFile?): SmithyShapeTarget? {
        if (editor == null || file == null) return null
        val at = file.findElementAt(editor.caretModel.offset) ?: return null
        return PsiTreeUtil.getParentOfType(at, SmithyInlineListTarget::class.java, SmithyInlineMapTarget::class.java)
    }

    private fun shapeDeclaration(name: String, target: SmithyShapeTarget): String? {
        return when (target) {
            is SmithyInlineListTarget -> target.element?.text?.let { "list $name {\n    member: $it\n}" }
            is SmithyInlineMapTarget -> {
                val key = target.key?.text ?: return null
                val value = target.value?.text ?: return null
                "map $name {\n    key: $key\n    value: $value\n}"
            }
            else -> null
        }
    }

    //A simple name is unsafe if any shape with that simple name is defined in the project (prelude, an import, or the
    //current namespace), since the extracted shape is referenced by simple name and would otherwise be ambiguous.
    private fun uniqueName(base: String, scope: GlobalSearchScope): String {
        if (!isTaken(base, scope)) return base
        var i = 2
        while (isTaken("$base$i", scope)) i++
        return "$base$i"
    }

    private fun isTaken(name: String, scope: GlobalSearchScope) =
        SmithyDefinedShapeIdIndex.getShapeIdsByName(name, scope).isNotEmpty()
}
