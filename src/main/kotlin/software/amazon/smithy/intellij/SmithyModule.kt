package software.amazon.smithy.intellij

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import software.amazon.smithy.intellij.SmithyVariableExpansion.UnresolvedSmithyVariableException
import software.amazon.smithy.intellij.index.SmithyBuildConfigurationIndex
import java.nio.file.Path

/**
 * A utility class for introspecting and modifying the enclosing [Module] containing [Smithy](https://awslabs.github.io/smithy) models,
 * as well as for locating and provisioning the dedicated `smithy-build` module that owns the
 * project's Smithy CLI build configuration.
 *
 * The `smithy-build` module is named [SMITHY_BUILD_MODULE_NAME] and its `.iml` is stored under
 * `.idea/modules/`. Each `sources` entry from `smithy-build.json` becomes a content root on
 * this module, with the resolved directory marked as a source folder. The Maven dependencies
 * declared in the `maven` block are attached as a project library to the module.
 *
 * A `sources` entry whose directory is already the content root of another module is skipped
 * to avoid the "module content roots must not overlap" constraint enforced by IntelliJ.
 *
 * @author Ian Caffey
 * @since 1.0
 */
object SmithyModule {
    private val LOG = Logger.getInstance(SmithyModule::class.java)
    const val SMITHY_BUILD_MODULE_NAME = "smithy-build"

    fun findGradleBuildFile(module: Module) = module.rootManager.contentRoots.firstNotNullOfOrNull {
        //Looks for standard build files first, but still supports non-standard if necessary
        it.children.firstOrNull { child -> child.name == "build.gradle" || child.name == "build.gradle.kts" }
            ?: it.children.firstOrNull { child ->
                !child.isDirectory && child.name != ".gradle" && !child.name.startsWith("settings")
                        && (child.name.endsWith(".gradle") || child.name.endsWith(".gradle.kts"))
            }
    }

    fun inferSourceRoot(file: VirtualFile, contentRoot: ContentEntry): VirtualFile? {
        val root = contentRoot.file ?: return null
        val path = root.toNioPath()
        val relative = path.relativize(file.toNioPath())
        return when {
            relative.startsWith("model") -> path.resolve("model")
            relative.startsWith("smithy") -> path.resolve("smithy")
            else -> null
        }?.let { VirtualFileManager.getInstance().findFileByNioPath(it) }
    }

    fun findBuildConfig(module: Module) = findBuildFile(module)?.let {
        SmithyBuildConfigurationIndex.getConfig(it, module.project)
    }

    fun findBuildFile(module: Module): VirtualFile? {
        val roots = module.rootManager.contentRoots
        roots.firstNotNullOfOrNull { it.findFileByRelativePath("smithy-build.json") }?.let { return it }
        //Maven-style ("main" module content root needs to be traversed 2 directories up to /src/main to find the build config)
        return roots.firstNotNullOfOrNull { root ->
            root.takeIf { it.name == "main" }
                ?.parent?.takeIf { it.name == "src" }
                ?.parent?.findFileByRelativePath("smithy-build.json")
        }
    }

    fun findContentRoot(file: VirtualFile, model: ModifiableRootModel) = model.contentEntries.find {
        val contentRoot = it.file ?: return@find false
        VfsUtil.isAncestor(contentRoot, file, true)
    }

    fun findSourceRoot(file: VirtualFile, model: ModifiableRootModel) = model.sourceRoots.find {
        VfsUtil.isAncestor(it, file, true)
    }

    fun defaultNamespace(file: PsiFile) =
        file.parent?.let { findDefaultNamespace(it) }
            ?: file.parent?.parent?.let { findDefaultNamespace(it) }
            ?: file.name.replace(Regex("[^A-Za-z0-9_.]"), "").split(".").filter { it.isEmpty() }.joinToString(".")
                .let { name ->
                    when {
                        name.isEmpty() -> "smithy"
                        name[0] in 'A'..'Z' || name[0] == '_' -> name
                        else -> "_$name"
                    }
                }

    fun findDefaultNamespace(dir: PsiDirectory) = dir.files.mapNotNull {
        (it as? SmithyFile)?.model?.namespace
    }.minOrNull()

    fun findDefaultVersion(dir: PsiDirectory) = dir.files.mapNotNull { file ->
        (file as? SmithyFile)?.model?.version
    }.minOrNull()

    fun findSmithyBuildModule(project: Project): Module? =
        ModuleManager.getInstance(project).findModuleByName(SMITHY_BUILD_MODULE_NAME)

    fun smithyBuildImlPath(project: Project): Path? {
        val baseDir = project.guessProjectDir()?.toNioPath() ?: return null
        return baseDir.resolve(".idea").resolve("modules").resolve("$SMITHY_BUILD_MODULE_NAME.iml")
    }

    /**
     * Locates the project's single `smithy-build.json`. Searches every existing module's
     * content roots first, then falls back to the project base dir.
     */
    fun findProjectBuildFile(project: Project): VirtualFile? {
        val ours = findSmithyBuildModule(project)
        for (module in ModuleManager.getInstance(project).modules) {
            if (module === ours) continue
            for (root in module.rootManager.contentRoots) {
                root.findChild("smithy-build.json")?.let { return it }
            }
        }
        return project.guessProjectDir()?.findChild("smithy-build.json")
    }

    fun loadBuildConfig(project: Project, file: VirtualFile): SmithyBuildConfiguration? {
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
        val ours = findSmithyBuildModule(project)
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
