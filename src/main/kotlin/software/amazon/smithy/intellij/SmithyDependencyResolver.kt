package software.amazon.smithy.intellij

import com.intellij.jarRepository.JarRepositoryManager
import com.intellij.jarRepository.RemoteRepositoryDescription
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.libraries.ui.OrderRoot
import org.jetbrains.idea.maven.aether.ArtifactKind
import org.jetbrains.jps.model.library.JpsMavenRepositoryLibraryDescriptor
import java.util.EnumSet

/**
 * Resolves the Maven coordinates declared in a [SmithyBuildConfiguration] using IntelliJ's
 * [JarRepositoryManager], returning the [OrderRoot]s that should be attached to a Smithy
 * module's library so that dependencies declared in `smithy-build.json` become visible to
 * editor features (completion, navigation, etc.).
 */
object SmithyDependencyResolver {
    private val ARTIFACT_KINDS: Set<ArtifactKind> = EnumSet.of(ArtifactKind.ARTIFACT)

    fun resolve(
        project: Project,
        config: SmithyBuildConfiguration,
        indicator: ProgressIndicator? = null
    ): List<OrderRoot> {
        val maven = config.maven ?: return emptyList()
        if (maven.dependencies.isEmpty()) return emptyList()
        val repositories = remoteRepositories(maven.repositories)
        val roots = mutableListOf<OrderRoot>()
        for (coordinate in maven.dependencies) {
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

    private fun remoteRepositories(declared: List<SmithyMavenRepository>): List<RemoteRepositoryDescription> {
        if (declared.isEmpty()) return RemoteRepositoryDescription.DEFAULT_REPOSITORIES
        return declared.mapIndexed { index, repo ->
            val id = repo.id ?: "smithy-maven-$index"
            RemoteRepositoryDescription(id, id, repo.url)
        }
    }
}
