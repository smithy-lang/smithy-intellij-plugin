package software.amazon.smithy.intellij.templates

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil.getChildOfType
import com.intellij.psi.util.endOffset
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.psi.SmithyNamespace
import software.amazon.smithy.intellij.psi.SmithyShape

/**
 * A [TemplateContextType] for [Smithy](https://awslabs.github.io/smithy) providing live templates, specifically within
 * a Smithy file shape section where additional shape/trait/apply statements can be added.
 *
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyShapeSectionOutsideShapeContextType :
    TemplateContextType(
        "SmithyShapeSectionOutsideShape",
        "Smithy file (within shape section, but outside any existing shapes)"
    ) {
    override fun isInContext(context: TemplateActionContext) = context.file.let {
        val file = it as? SmithyFile ?: return@let false
        val namespace = getChildOfType(file.model, SmithyNamespace::class.java)
        namespace != null
                && context.startOffset >= namespace.endOffset
                && !WITHIN_SHAPE.accepts(it.findElementAt(context.startOffset))
    }

    companion object {
        private val WITHIN_SHAPE = PlatformPatterns.psiElement().inside(SmithyShape::class.java)
    }
}
