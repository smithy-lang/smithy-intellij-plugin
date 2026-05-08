package software.amazon.smithy.intellij

import com.intellij.jarRepository.JarRepositoryManager
import com.intellij.jarRepository.RemoteRepositoryDescription
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.libraries.ui.OrderRoot
import org.jetbrains.idea.maven.aether.ArtifactKind
import org.jetbrains.jps.model.library.JpsMavenRepositoryLibraryDescriptor
import software.amazon.smithy.intellij.SmithyVariableExpansion.UnresolvedSmithyVariableException
import java.util.EnumSet

/**
 * Resolves the Maven coordinates declared in a [SmithyBuildConfiguration] using IntelliJ's
 * [JarRepositoryManager], returning the [OrderRoot]s that should be attached to a Smithy
 * module's library so that dependencies declared in `smithy-build.json` become visible to
 * editor features (completion, navigation, etc.).
 *
 * `${VAR}` placeholders in coordinates and repository fields are expanded against system
 * properties and environment variables (matching the Smithy CLI's load-time behavior). An
 * entry referencing an unset variable is skipped rather than failing the entire sync.
 */
object SmithyDependencyResolver {
    private val LOG = Logger.getInstance(SmithyDependencyResolver::class.java)
    private val ARTIFACT_KINDS: Set<ArtifactKind> = EnumSet.of(ArtifactKind.ARTIFACT)

    fun resolve(
        project: Project,
        config: SmithyBuildConfiguration,
        indicator: ProgressIndicator? = null
    ): List<OrderRoot> {
        val maven = config.maven ?: return emptyList()
        if (maven.dependencies.isEmpty()) return emptyList()
        val builtins = SmithyVariableExpansion.builtins(project)
        val repositories = remoteRepositories(maven.repositories, builtins)
        val roots = mutableListOf<OrderRoot>()
        for (rawCoordinate in maven.dependencies) {
            val coordinate = try {
                SmithyVariableExpansion.expand(rawCoordinate, builtins)
            } catch (e: UnresolvedSmithyVariableException) {
                LOG.warn("Skipping Smithy Maven dependency '$rawCoordinate': ${e.message}")
                continue
            }
            val descriptor = JpsMavenRepositoryLibraryDescriptor(coordinate, true, emptyList())
            val resolved = JarRepositoryManager.loadDependenciesSync(
                project,
                descriptor,
                ARTIFACT_KINDS,
                repositories,
                /* copyTo = */ null
            ) ?: continue
            roots.addAll(resolved)
            indicator?.checkCanceled()
        }
        return roots
    }

    private fun remoteRepositories(
        declared: List<SmithyMavenRepository>,
        builtins: Map<String, String>
    ): List<RemoteRepositoryDescription> {
        if (declared.isEmpty()) return RemoteRepositoryDescription.DEFAULT_REPOSITORIES
        val expanded = mutableListOf<RemoteRepositoryDescription>()
        declared.forEachIndexed { index, repo ->
            try {
                val url = SmithyVariableExpansion.expand(repo.url, builtins)
                val id = repo.id?.let { SmithyVariableExpansion.expand(it, builtins) } ?: "smithy-maven-$index"
                expanded += RemoteRepositoryDescription(id, id, url)
            } catch (e: UnresolvedSmithyVariableException) {
                LOG.warn("Skipping Smithy Maven repository '${repo.url}': ${e.message}")
            }
        }
        return expanded
    }
}
