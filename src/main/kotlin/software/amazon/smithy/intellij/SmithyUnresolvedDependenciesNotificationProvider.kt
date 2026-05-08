package software.amazon.smithy.intellij

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications

/**
 * Shows a banner above `smithy-build.json` files when the declared Maven dependencies have not yet
 * been resolved into the project's `smithy-build` library. Offers a one-click action to reimport.
 */
class SmithyUnresolvedDependenciesNotificationProvider : EditorNotifications.Provider<EditorNotificationPanel>() {
    companion object {
        val KEY = Key.create<EditorNotificationPanel>("SmithyUnresolvedDependenciesNotification")
        private const val LIBRARY_NAME = "Smithy: smithy-build"
    }

    override fun getKey() = KEY

    override fun createNotificationPanel(
        file: VirtualFile,
        fileEditor: FileEditor,
        project: Project
    ): EditorNotificationPanel? {
        if (file.name != "smithy-build.json") return null
        if (DumbService.isDumb(project)) return null
        val config = SmithyModule.loadBuildConfig(project, file) ?: return null
        val maven = config.maven ?: return null
        if (maven.dependencies.isEmpty()) return null
        val library = LibraryTablesRegistrar.getInstance()
            .getLibraryTable(project)
            .getLibraryByName(LIBRARY_NAME)
        if (library != null && library.getUrls(OrderRootType.CLASSES).isNotEmpty()) return null
        val panel = EditorNotificationPanel(fileEditor)
        panel.text = "Smithy dependencies declared in smithy-build.json have not been resolved"
        panel.createActionLabel("Reimport") {
            SmithyDependencySynchronizer.syncProjectInBackground(project)
        }
        return panel
    }
}
