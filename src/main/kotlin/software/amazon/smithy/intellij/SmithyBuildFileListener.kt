package software.amazon.smithy.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.AsyncFileListener.ChangeApplier
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent

/**
 * Re-syncs Smithy dependencies whenever a `smithy-build.json` file is created, modified or
 * deleted in any open project.
 */
class SmithyBuildFileListener : AsyncFileListener {
    override fun prepareChange(events: List<VFileEvent>): ChangeApplier? {
        val triggered = events.any { it.affectsSmithyBuildFile() }
        if (!triggered) return null
        return object : ChangeApplier {
            override fun afterVfsChange() {
                for (project in ProjectManager.getInstance().openProjects) {
                    scheduleSync(project)
                }
            }
        }
    }

    private fun VFileEvent.affectsSmithyBuildFile(): Boolean {
        val name = when (this) {
            is VFileCreateEvent -> childName
            is VFileDeleteEvent -> file.name
            is VFileContentChangeEvent -> file.name
            is VFileCopyEvent -> newChildName
            is VFileMoveEvent -> file.name
            is VFilePropertyChangeEvent -> file.name
            else -> return false
        }
        return name == "smithy-build.json"
    }

    private fun scheduleSync(project: Project) {
        if (project.isDisposed) return
        // Don't kick off the sync from inside VFS event dispatch — the synchronizer needs to
        // read a project-level file index, which is forbidden in that context. Defer it.
        ApplicationManager.getApplication().invokeLater(
            { if (!project.isDisposed) SmithyDependencySynchronizer.syncProjectInBackground(project) },
            project.disposed
        )
    }
}
