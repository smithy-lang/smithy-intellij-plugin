package software.amazon.smithy.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.roots.libraries.ui.OrderRoot
import com.intellij.openapi.vfs.VirtualFile

/**
 * Reconciles the dedicated `smithy-build` module with the project's `smithy-build.json`.
 *
 * On each sync:
 *  - Locates the project's `smithy-build.json` (if any).
 *  - If missing, disposes the `smithy-build` module and any associated library.
 *  - Otherwise, ensures the module exists, sets its content roots from the `sources` field,
 *    and attaches the resolved Maven dependencies as a project library.
 */
object SmithyDependencySynchronizer {
    private const val LIBRARY_NAME = "Smithy: smithy-build"

    fun syncProjectInBackground(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Resolving Smithy dependencies", true) {
            override fun run(indicator: ProgressIndicator) {
                syncProject(project, indicator)
            }
        })
    }

    fun syncProject(project: Project, indicator: ProgressIndicator? = null) {
        if (project.isDisposed) return
        data class Snapshot(val buildFile: VirtualFile?, val config: SmithyBuildConfiguration?)
        val snapshot = ReadAction.compute<Snapshot, RuntimeException> {
            val file = SmithyBuildModule.findBuildFile(project) ?: return@compute Snapshot(null, null)
            Snapshot(file, SmithyBuildModule.loadConfig(project, file))
        }
        val buildFile = snapshot.buildFile
        val config = snapshot.config
        if (buildFile == null || config == null) {
            disposeModule(project)
            return
        }
        val contentRoots = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            val all = SmithyBuildModule.resolveContentRoots(project, buildFile, config)
            val conflicts = SmithyBuildModule.conflictingContentRoots(project, all)
            all.filter { it.url !in conflicts }
        }
        // Resolve Maven dependencies (off the EDT, may take a while).
        val maven = config.maven
        val orderRoots: List<OrderRoot> = if (maven != null && maven.dependencies.isNotEmpty()) {
            indicator?.text = "Resolving Smithy Maven dependencies"
            try {
                SmithyDependencyResolver.resolve(project, config, indicator)
            } catch (e: Exception) {
                notify(
                    project,
                    "Failed to resolve Smithy dependencies: ${e.message}",
                    NotificationType.ERROR
                )
                emptyList()
            }
        } else emptyList()
        if (maven != null && maven.dependencies.isNotEmpty() && orderRoots.isEmpty()) {
            notify(
                project,
                "No artifacts could be resolved for the Smithy Maven dependencies.",
                NotificationType.WARNING
            )
        }
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                if (project.isDisposed) return@runWriteAction
                applyModule(project, contentRoots, orderRoots)
            }
        }
    }

    private fun applyModule(project: Project, contentRoots: List<VirtualFile>, orderRoots: List<OrderRoot>) {
        val module = ensureModule(project) ?: return
        val library = applyLibrary(project, orderRoots)
        applyContentRoots(module, library, contentRoots)
    }

    private fun ensureModule(project: Project): Module? {
        SmithyBuildModule.find(project)?.let { return it }
        val imlPath = SmithyBuildModule.imlPath(project) ?: return null
        com.intellij.openapi.util.io.FileUtil.ensureExists(imlPath.parent.toFile())
        val moduleModel = ModuleManager.getInstance(project).modifiableModel
        return try {
            val created = moduleModel.newModule(imlPath, ModuleTypeId.JAVA_MODULE)
            moduleModel.commit()
            created
        } catch (e: Throwable) {
            moduleModel.dispose()
            throw e
        }
    }

    private fun applyLibrary(project: Project, orderRoots: List<OrderRoot>): Library? {
        val table = projectLibraryTable(project)
        if (orderRoots.isEmpty()) {
            val existing = table.getLibraryByName(LIBRARY_NAME) ?: return null
            val tableModel = table.modifiableModel
            tableModel.removeLibrary(existing)
            tableModel.commit()
            return null
        }
        val tableModel = table.modifiableModel
        val library = tableModel.getLibraryByName(LIBRARY_NAME) ?: tableModel.createLibrary(LIBRARY_NAME)
        val libraryModel = library.modifiableModel
        for (url in libraryModel.getUrls(OrderRootType.CLASSES)) {
            libraryModel.removeRoot(url, OrderRootType.CLASSES)
        }
        for (url in libraryModel.getUrls(OrderRootType.SOURCES)) {
            libraryModel.removeRoot(url, OrderRootType.SOURCES)
        }
        for (root in orderRoots) {
            libraryModel.addRoot(root.file, root.type)
        }
        libraryModel.commit()
        tableModel.commit()
        return library
    }

    private fun applyContentRoots(module: Module, library: Library?, desired: List<VirtualFile>) {
        val rootModel = ModuleRootManager.getInstance(module).modifiableModel
        try {
            val desiredUrls = desired.map { it.url }.toSet()
            // Remove content entries no longer desired.
            for (entry in rootModel.contentEntries) {
                val url = entry.url
                if (url !in desiredUrls) {
                    rootModel.removeContentEntry(entry)
                }
            }
            val existingUrls = rootModel.contentEntries.map { it.url }.toSet()
            for (file in desired) {
                if (file.url in existingUrls) continue
                val entry: ContentEntry = rootModel.addContentEntry(file)
                entry.addSourceFolder(file, false)
            }
            // Ensure each existing content entry has the directory itself as a source folder.
            for (entry in rootModel.contentEntries) {
                val root = entry.file ?: continue
                if (entry.sourceFolders.none { it.url == root.url }) {
                    entry.addSourceFolder(root, false)
                }
            }
            // Reconcile library order entry.
            val attached = rootModel.orderEntries.filterIsInstance<LibraryOrderEntry>()
                .filter { it.libraryName == LIBRARY_NAME }
            if (library == null) {
                for (entry in attached) rootModel.removeOrderEntry(entry)
            } else if (attached.isEmpty()) {
                rootModel.addLibraryEntry(library).scope = DependencyScope.COMPILE
            }
            rootModel.commit()
        } catch (e: Throwable) {
            rootModel.dispose()
            throw e
        }
    }

    private fun disposeModule(project: Project) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                if (project.isDisposed) return@runWriteAction
                val moduleModel = ModuleManager.getInstance(project).modifiableModel
                val module = moduleModel.findModuleByName(SmithyBuildModule.MODULE_NAME)
                if (module != null) {
                    moduleModel.disposeModule(module)
                    moduleModel.commit()
                } else {
                    moduleModel.dispose()
                }
                val table = projectLibraryTable(project)
                table.getLibraryByName(LIBRARY_NAME)?.let { existing ->
                    val tableModel = table.modifiableModel
                    tableModel.removeLibrary(existing)
                    tableModel.commit()
                }
            }
        }
    }

    private fun projectLibraryTable(project: Project): LibraryTable =
        LibraryTablesRegistrar.getInstance().getLibraryTable(project)

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Smithy")
            .createNotification(message, type)
            .notify(project)
    }
}
