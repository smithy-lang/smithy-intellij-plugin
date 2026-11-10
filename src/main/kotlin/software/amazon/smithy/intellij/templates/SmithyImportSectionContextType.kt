package software.amazon.smithy.intellij.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.util.PsiTreeUtil.getChildOfType
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.psi.SmithyNamespace

/**
 * A [TemplateContextType] for [Smithy](https://awslabs.github.io/smithy) providing live templates, specifically files
 * within the section of the file where an import declaration can be placed.
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyImportSectionContextType : TemplateContextType("SmithyImportSection", "Smithy file (import section)") {
    override fun isInContext(context: TemplateActionContext) = context.file.let {
        val file = it as? SmithyFile ?: return@let false
        val namespace = getChildOfType(file.model, SmithyNamespace::class.java)
        namespace != null
                && namespace.endOffset <= context.startOffset
                && (it.model?.shapes ?: emptyList()).none { shape -> shape.startOffset < context.startOffset }
    }
}
