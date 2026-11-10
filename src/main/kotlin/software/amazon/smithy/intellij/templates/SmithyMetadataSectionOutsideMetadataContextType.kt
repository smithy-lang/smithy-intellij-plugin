package software.amazon.smithy.intellij.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil.getChildOfType
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.psi.SmithyMetadata
import software.amazon.smithy.intellij.psi.SmithyNamespace

/**
 * A [TemplateContextType] for [Smithy](https://awslabs.github.io/smithy) providing live templates, specifically files
 * within the section of the file where a metadata declaration can be placed.
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyMetadataSectionOutsideMetadataContextType :
    TemplateContextType(
        "SmithyMetadataSectionOutsideMetadata",
        "Smithy file (metadata section, but outside any existing metadata)"
    ) {
    override fun isInContext(context: TemplateActionContext) = context.file.let {
        val file = it as? SmithyFile ?: return@let false
        val model = file.model ?: return@let false
        val namespace = getChildOfType(model, SmithyNamespace::class.java)
        (namespace == null || context.startOffset <= namespace.startOffset)
                && model.control.all { control -> control.endOffset <= context.startOffset }
                && !WITHIN_METADATA.accepts(it.findElementAt(context.startOffset))
    }

    companion object {
        private val WITHIN_METADATA = PlatformPatterns.psiElement().inside(SmithyMetadata::class.java)
    }
}
