package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.hint.QuestionAction
import com.intellij.ide.util.DefaultPsiElementCellRenderer
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import software.amazon.smithy.intellij.SmithyElementFactory
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.SmithyShapeResolver
import software.amazon.smithy.intellij.psi.SmithyShapeDefinition

/**
 * A [QuestionAction] for selecting one of many [SmithyShapeDefinition] options for a [SmithyShapeReference].
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyAddImportAction(
    val project: Project, val editor: Editor, val file: SmithyFile, val shapeIds: List<String>
) : QuestionAction {
    override fun execute(): Boolean {
        val shapes = shapeIds.flatMap {
            val (namespace, shapeName) = it.split('#', limit = 2)
            SmithyShapeResolver.getDefinitions(namespace, shapeName, file.resolveScope)
        }.distinctBy { it.shapeId }
        val step = object : BaseListPopupStep<SmithyShapeDefinition>("Imports", shapes) {
            override fun onChosen(selectedValue: SmithyShapeDefinition?, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice && selectedValue != null) {
                    doFinalStep {
                        WriteCommandAction.runWriteCommandAction(project) {
                            SmithyElementFactory.addImport(file, selectedValue.namespace, selectedValue.shapeName)
                        }
                    }
                }
                return FINAL_CHOICE
            }
        }
        val popup = JBPopupFactory.getInstance().createListPopup(project, step) {
            DefaultPsiElementCellRenderer()
        }
        // Hide the popup if indexing starts, since shape resolution requires indices
        if (!DumbService.isDumb(project)) {
            project.messageBus.connect(popup).subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                override fun enteredDumbMode() = popup.cancel()
            })
        }
        popup.showInBestPositionFor(editor)
        return true
    }
}