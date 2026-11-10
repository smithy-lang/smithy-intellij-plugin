package software.amazon.smithy.intellij

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications
import software.amazon.smithy.intellij.index.SmithyDefinedShapeIdIndex

/**
 * An [EditorNotifications.Provider] for a notification alerting the user the Smithy prelude cannot be found.
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyMissingPreludeNotificationProvider : EditorNotifications.Provider<EditorNotificationPanel>() {
    companion object {
        val KEY = Key.create<EditorNotificationPanel>("SmithyMissingPreludeNotificationProvider")
    }

    override fun getKey() = KEY
    override fun createNotificationPanel(
        file: VirtualFile,
        fileEditor: FileEditor,
        project: Project
    ): EditorNotificationPanel? {
        if (file.extension != "smithy") return null
        val module = ModuleUtil.findModuleForFile(file, project) ?: return null
        if (SmithyModule.findSourceRoot(file, module.rootManager.modifiableModel) == null) return null
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        if (SmithyDefinedShapeIdIndex.exists("smithy.api", "String", psi.resolveScope)) return null
        return EditorNotificationPanel(fileEditor).also {
            it.text =
                "Unable to find the Smithy prelude. Make sure either the Smithy CLI or Smithy Model is added as a dependency within your build system."
            it.createActionLabel("Need help?") {}
                .setHyperlinkTarget("https://github.com/smithy-lang/smithy-intellij-plugin/issues")
        }
    }
}