package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import software.amazon.smithy.intellij.SmithyElementFactory
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.SmithyVersion
import software.amazon.smithy.intellij.index.SmithyShapeNameResolutionHintIndex
import software.amazon.smithy.intellij.psi.SmithyContainerMember
import software.amazon.smithy.intellij.psi.SmithyDefinition
import software.amazon.smithy.intellij.psi.SmithyMemberTarget
import software.amazon.smithy.intellij.psi.SmithyShape
import software.amazon.smithy.intellij.psi.SmithyShapeDefinition
import software.amazon.smithy.intellij.psi.SmithyStructure
import software.amazon.smithy.intellij.psi.SmithySyntheticTrait
import software.amazon.smithy.intellij.psi.SmithyShapeId

/**
 * An [IntentionAction] which inlines an explicit `list`/`map` shape into IDL 2.1 inline collection syntax at every
 * member that targets it, then deletes the now-unused shape, e.g.
 *
 * ```
 * structure Foo { names: StringList }
 * list StringList { member: String }
 * ```
 *
 * becomes
 *
 * ```
 * structure Foo { names: [String] }
 * ```
 *
 * The conversion is only offered when it is lossless and local: the collection carries no shape-level traits, its
 * member/key/value carry no traits, it lives in the same namespace as the members that target it, and every reference
 * to it is a convertible member (so the shape can be safely removed). See the "Inline collection declarations" design.
 *
 * @since 1.0
 */
class SmithyInlineCollectionQuickFix(private val shape: SmithyShapeDefinition) : BaseIntentionAction() {
    override fun getText() = "Inline collection '${shape.shapeName}'"
    override fun getFamilyName() = "Inline collection declaration"
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile) = true
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (shape !is SmithyShape) return
        val inlineText = inlineText(shape) ?: return
        //A single project scan (on explicit invocation) classifies every reference to this shape.
        val refs = findReferences(shape)
        //If any referencing member is bound to a resource property/identifier, inlining would break that binding, so
        //refuse the whole conversion rather than partially rewrite.
        if (refs.hasResourceBound) return
        WriteCommandAction.runWriteCommandAction(project) {
            refs.members.forEach { member ->
                PsiTreeUtil.getChildOfType(member, SmithyMemberTarget::class.java)
                    ?.replace(SmithyElementFactory.createMemberTarget(project, inlineText))
            }
            //Only remove the shape when nothing other than the rewritten members referenced it.
            if (refs.members.isNotEmpty() && !refs.hasOther) shape.delete()
        }
    }

    private data class References(
        val members: List<SmithyContainerMember>,
        val hasOther: Boolean,
        val hasResourceBound: Boolean
    )

    companion object {
        /**
         * Returns the convertible [SmithyShapeDefinition] that [member]'s target resolves to, or null when the member
         * is not an eligible candidate for inlining.
         *
         * This performs only cheap, local checks (a single target resolution plus shape/member trait inspection) so it
         * is safe to call on every member during highlighting. The project-wide reference scan that guards shape
         * deletion is deferred to [invoke], which runs only when the user applies the fix.
         */
        fun eligibleTarget(member: SmithyContainerMember): SmithyShapeDefinition? {
            //Inline collections are an IDL 2.1 feature; do not offer the conversion in older files.
            val version = (member.containingFile as? SmithyFile)?.model?.version
            if (version == null || SmithyVersion.compare(version, "2.1") < 0) return null
            //After the IDL 2.1 broadening, declaredTarget is a member_target wrapper; unwrap to the underlying id.
            val memberTarget = member.declaredTarget as? SmithyMemberTarget ?: return null
            val target = memberTarget.target as? SmithyShapeId ?: return null //already inline, or not a plain id
            val shape = target.resolve() ?: return null
            if (!isConvertible(shape)) return null
            //A member bound to a resource property/identifier must keep targeting the declared shape.
            if (isResourceBound(member)) return null
            //The synthetic shape lands in the member's namespace, so inlining a shape from another namespace would move
            //it; only offer when the collection shares the enclosing structure's namespace.
            if (shape.namespace != member.enclosingShape.namespace) return null
            return shape
        }

        /**
         * True when [member] is bound to a resource property or identifier via its enclosing `structure ... for R`.
         * Inlining such a member retargets it to a synthetic shape and would no longer match the resource's declared
         * property/identifier type, so the conversion must be refused.
         */
        private fun isResourceBound(member: SmithyContainerMember): Boolean {
            val resource = (member.enclosingShape as? SmithyStructure)?.resource?.resolve() ?: return false
            return resource.getProperty(member.name) != null || resource.getIdentifier(member.name) != null
        }

        private fun isConvertible(shape: SmithyShapeDefinition): Boolean {
            if (shape !is SmithyShape) return false //must be a real IDL shape we can edit/delete
            if (shape.type != "list" && shape.type != "map") return false
            //Shape-level traits cannot be expressed inline. Use the resolved trait list (not just declaredTraits) so
            //traits applied via a separate `apply` statement or inherited from a mixin are not silently dropped.
            if (hasBlockingTrait(shape)) return false
            //member/key/value must be plain targets without blocking traits.
            val memberNames = if (shape.type == "list") listOf("member") else listOf("key", "value")
            return memberNames.all { name ->
                val m = shape.getMember(name)
                val mTarget = (m?.declaredTarget as? SmithyMemberTarget)?.target
                m != null && !hasBlockingTrait(m) && mTarget is SmithyShapeId
            }
        }

        //A trait blocks inlining unless it is purely synthetic (e.g. a documentation trait derived from a doc comment),
        //which carries no information that inline syntax cannot already preserve.
        private fun hasBlockingTrait(definition: SmithyDefinition): Boolean =
            definition.traits.any { it !is SmithySyntheticTrait }

        /**
         * Scans the project once and classifies every reference to [shape]: the members whose target is the shape (to
         * be rewritten) and whether any other kind of reference exists (which would make deleting the shape unsafe).
         *
         * Only shape ids whose simple name matches the shape are resolved, so the expensive [SmithyShapeId.resolve]
         * call is skipped for the overwhelming majority of ids in the project.
         */
        private fun findReferences(shape: SmithyShape): References {
            val scope = GlobalSearchScope.allScope(shape.project)
            val simpleName = shape.shapeName
            val members = mutableListOf<SmithyContainerMember>()
            var hasOther = false
            var hasResourceBound = false
            //Only inspect files that actually mention the shape's simple name (via the shape-hint index), rather than
            //scanning every file in the project.
            val psiManager = PsiManager.getInstance(shape.project)
            FileBasedIndex.getInstance()
                .getContainingFiles(SmithyShapeNameResolutionHintIndex.NAME, simpleName, scope)
                .mapNotNull { psiManager.findFile(it) as? SmithyFile }
                .forEach { file ->
                    PsiTreeUtil.collectElementsOfType(file, SmithyShapeId::class.java).forEach { id ->
                        //Cheap name filter first; only resolve ids that could possibly refer to this shape.
                        if (id.shapeName == simpleName && id.resolve() === shape) {
                            val owningMember = PsiTreeUtil.getParentOfType(id, SmithyContainerMember::class.java)
                            if (owningMember != null &&
                                (owningMember.declaredTarget as? SmithyMemberTarget)?.target === id
                            ) {
                                members += owningMember
                                if (isResourceBound(owningMember)) hasResourceBound = true
                            } else {
                                hasOther = true
                            }
                        }
                    }
                }
            return References(members, hasOther, hasResourceBound)
        }

        private fun inlineText(shape: SmithyShapeDefinition): String? {
            return when (shape.type) {
                "list" -> shape.getMember("member")?.declaredTarget?.text?.let { "[$it]" }
                "map" -> {
                    val key = shape.getMember("key")?.declaredTarget?.text ?: return null
                    val value = shape.getMember("value")?.declaredTarget?.text ?: return null
                    "{$key: $value}"
                }
                else -> null
            }
        }
    }
}
