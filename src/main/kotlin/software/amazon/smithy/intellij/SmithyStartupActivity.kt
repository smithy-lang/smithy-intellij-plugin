package software.amazon.smithy.intellij

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Triggers an initial sync of Smithy Maven dependencies when a project is opened.
 */
class SmithyStartupActivity : StartupActivity.Background {
    override fun runActivity(project: Project) {
        DumbService.getInstance(project).runWhenSmart {
            SmithyDependencySynchronizer.syncProjectInBackground(project)
        }
    }
}
