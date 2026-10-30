package software.amazon.smithy.intellij.psi

/**
 * Shared logic for IDL 2.1 inline collection declarations (`[Target]` lists and `{Key: Value}` maps).
 *
 * Inline collections desugar to synthetic shapes whose names are derived from the resolved element types, following
 * the naming scheme defined in the Smithy "Inline collection declarations" design:
 *
 * - Lists are named `_SyntheticListOf<token>`.
 * - Maps are named `_SyntheticMapOf<keyToken>_To_<valueToken>`.
 *
 * Each target is encoded as a token from its resolved shape id, relative to the namespace of the declaring structure:
 *
 * - a target in the declaring namespace that is itself synthetic (name starts with `_Synthetic`) -> `_Name`
 * - otherwise, a target whose simple name starts with `_` -> flattened-namespace form `_seg1_seg2_Name`
 * - otherwise, a prelude (`smithy.api`) target -> `__Name`
 * - otherwise, a target in the declaring namespace -> `_Name`
 * - otherwise (any other namespace) -> flattened-namespace form `_seg1_seg2_Name`
 *
 * @since 1.0
 */
object SmithyInlineTargets {
    const val SYNTHETIC_PREFIX = "_Synthetic"
    private const val PRELUDE_NAMESPACE = "smithy.api"

    /**
     * Encodes a single resolved target into its synthetic-name token, relative to [declaringNamespace].
     *
     * [resolvedNamespace] may be null when the target cannot be resolved; in that case the declared text is used as a
     * best-effort token so the generated name remains stable for a given source.
     */
    fun token(declaringNamespace: String?, resolvedNamespace: String?, shapeName: String): String {
        val sameNamespace = resolvedNamespace != null && resolvedNamespace == declaringNamespace
        return when {
            sameNamespace && shapeName.startsWith(SYNTHETIC_PREFIX) -> "_$shapeName"
            shapeName.startsWith("_") -> flattened(resolvedNamespace, shapeName)
            resolvedNamespace == PRELUDE_NAMESPACE -> "__$shapeName"
            sameNamespace -> "_$shapeName"
            else -> flattened(resolvedNamespace, shapeName)
        }
    }

    private fun flattened(namespace: String?, shapeName: String): String =
        if (namespace == null) "_$shapeName" else "_" + namespace.replace('.', '_') + "_$shapeName"

    fun listName(elementToken: String) = "${SYNTHETIC_PREFIX}ListOf$elementToken"

    fun mapName(keyToken: String, valueToken: String) = "${SYNTHETIC_PREFIX}MapOf${keyToken}_To_$valueToken"

    /**
     * A human-readable shape name for an inline collection, suitable as the default name when extracting it to an
     * explicit shape, e.g. `[String]` -> `ListOfString`, `{String: String}` -> `MapOfStringToString`,
     * `[[String]]` -> `ListOfListOfString`. Derived from the simple names of the (possibly nested) element targets,
     * without the synthetic prefix or namespace-encoding underscores.
     */
    fun readableName(target: SmithyShapeTarget?): String {
        val t = (target as? SmithyMemberTarget)?.target ?: target
        return when (t) {
            is SmithyInlineListTarget -> "ListOf" + readableName(t.element)
            is SmithyInlineMapTarget -> "MapOf" + readableName(t.key) + "To" + readableName(t.value)
            else -> t?.shapeName ?: "Unknown"
        }
    }
}
