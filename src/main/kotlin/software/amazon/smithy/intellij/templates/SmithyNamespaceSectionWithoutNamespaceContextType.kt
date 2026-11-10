package software.amazon.smithy.intellij.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.util.startOffset
import software.amazon.smithy.intellij.SmithyFile

/**
 * A [TemplateContextType] for [Smithy](https://awslabs.github.io/smithy) providing live templates, specifically files
 * within the section of the file where a namespace declaration can be placed, but without an existing namespace.
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyNamespaceSectionWithoutNamespaceContextType :
    TemplateContextType(
        "SmithyNamespaceSectionWithoutNamespace",
        "Smithy file (namespace section, without an existing namespace)"
    ) {
    override fun isInContext(context: TemplateActionContext) = context.file.let {
        it is SmithyFile
                && it.model?.namespace == null
                && (it.model?.shapes ?: emptyList()).none { shape -> shape.startOffset < context.startOffset }
                && (it.model?.imports ?: emptyList()).none { import -> import.startOffset < context.startOffset }
    }
}
