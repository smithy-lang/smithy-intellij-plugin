package software.amazon.smithy.intellij

/**
 * A [build configuration](https://awslabs.github.io/smithy/1.0/guides/building-models/build-config.html#using-smithy-build-json) for [Smithy](https://awslabs.github.io/smithy).
 *
 * @author Ian Caffey
 * @since 1.0
 */
data class SmithyBuildConfiguration(
    val version: String,
    val outputDirectory: String? = null,
    val sources: List<String> = emptyList(),
    val imports: List<String> = emptyList(),
    val projections: Map<String, SmithyProjection> = emptyMap(),
    val plugins: Map<String, Any?> = emptyMap(),
    val ignoreMissingPlugins: Boolean = false,
    val maven: SmithyMavenConfig? = null
)

data class SmithyProjection(
    val abstract: Boolean = false,
    val imports: List<String> = emptyList(),
    val transforms: List<SmithyTransform> = emptyList(),
    val plugins: Map<String, Any?>?,
)

data class SmithyTransform(
    val name: String,
    val args: Map<String, Any?> = emptyMap(),
)

data class SmithyMavenConfig(
    val dependencies: List<String> = emptyList(),
    val repositories: List<SmithyMavenRepository> = emptyList()
)

data class SmithyMavenRepository(
    val url: String,
    val id: String? = null,
    val httpCredentials: String? = null,
    val proxyHost: String? = null,
    val proxyCredentials: String? = null
)
