package software.amazon.smithy.intellij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VirtualFile
import software.amazon.smithy.intellij.SmithyVariableExpansion.UnresolvedSmithyVariableException
import software.amazon.smithy.intellij.index.SmithyBuildConfigurationIndex
import java.nio.file.Path

/**
 * Locating and provisioning the dedicated `smithy-build` module that owns the project's
 * Smithy CLI build configuration.
 *
 * The module is named [MODULE_NAME] and its `.iml` is stored under `.idea/modules/`. Each
 * `sources` entry from `smithy-build.json` becomes a content root on this module, with the
 * resolved directory marked as a source folder. The Maven dependencies declared in the
 * `maven` block are attached as a project library to the module.
 *
 * A `sources` entry whose directory is already the content root of another module is skipped
 * to avoid the "module content roots must not overlap" constraint enforced by IntelliJ.
 */
object SmithyBuildModule {
    private val LOG = Logger.getInstance(SmithyBuildModule::class.java)
    const val MODULE_NAME = "smithy-build"

    fun find(project: Project): Module? = ModuleManager.getInstance(project).findModuleByName(MODULE_NAME)

    fun imlPath(project: Project): Path? {
        val baseDir = project.guessProjectDir()?.toNioPath() ?: return null
        return baseDir.resolve(".idea").resolve("modules").resolve("$MODULE_NAME.iml")
    }

    /**
     * Locates the project's single `smithy-build.json`. Searches every existing module's
     * content roots first, then falls back to the project base dir.
     */
    fun findBuildFile(project: Project): VirtualFile? {
        val ours = find(project)
        for (module in ModuleManager.getInstance(project).modules) {
            if (module === ours) continue
            for (root in module.rootManager.contentRoots) {
                root.findChild("smithy-build.json")?.let { return it }
            }
        }
        return project.guessProjectDir()?.findChild("smithy-build.json")
    }

    fun loadConfig(project: Project, file: VirtualFile): SmithyBuildConfiguration? {
        return SmithyBuildConfigurationIndex.getConfig(file, project)
    }

    /**
     * Resolves each `sources` entry in [config] to a directory URL. Files have their parent
     * directory used; relative paths are resolved against [buildFile]'s parent.
     * `${VAR}` placeholders are expanded against system properties / environment variables;
     * entries referencing an unset variable are skipped. Returns a de-duplicated list of
     * source directories.
     */
    fun resolveContentRoots(
        project: Project,
        buildFile: VirtualFile,
        config: SmithyBuildConfiguration
    ): List<VirtualFile> {
        val baseDir = buildFile.parent ?: return emptyList()
        val builtins = SmithyVariableExpansion.builtins(project)
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<VirtualFile>()
        for (raw in config.sources) {
            val expanded = try {
                SmithyVariableExpansion.expand(raw, builtins)
            } catch (e: UnresolvedSmithyVariableException) {
                LOG.warn("Skipping Smithy source '$raw': ${e.message}")
                continue
            }
            val normalized = expanded.trim().removePrefix("./").trimStart('/')
            if (normalized.isEmpty()) continue
            val target = baseDir.findFileByRelativePath(normalized) ?: continue
            val rootDir = if (target.isDirectory) target else target.parent ?: continue
            if (seen.add(rootDir.url)) result += rootDir
        }
        return result
    }

    /**
     * Returns content-root URLs that conflict with another existing module's content root.
     */
    fun conflictingContentRoots(project: Project, candidates: Collection<VirtualFile>): Set<String> {
        val ours = find(project)
        val claimed = mutableSetOf<String>()
        for (module in ModuleManager.getInstance(project).modules) {
            if (module === ours) continue
            for (root in module.rootManager.contentRoots) {
                claimed += root.url
            }
        }
        return candidates.map { it.url }.filter { it in claimed }.toSet()
    }
}

