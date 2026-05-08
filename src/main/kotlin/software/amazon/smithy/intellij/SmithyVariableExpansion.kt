package software.amazon.smithy.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir

/**
 * Expands `${VAR}` placeholders in strings from `smithy-build.json` the same way the Smithy CLI
 * does at load time: system properties take precedence over environment variables, and `\${...}`
 * is treated as a literal `${...}`.
 *
 * Mirrors the algorithm in
 * [SmithyBuildUtils](https://github.com/smithy-lang/smithy/blob/main/smithy-build/src/main/java/software/amazon/smithy/build/model/SmithyBuildUtils.java).
 *
 * Unlike the CLI — which fails the whole build on a missing variable — this plugin throws
 * [UnresolvedSmithyVariableException] so callers can skip an individual entry without aborting
 * the rest of the sync.
 */
object SmithyVariableExpansion {
    private val PATTERN = Regex("""(?:^|[^\\])\$\{([^}]+)}""")

    class UnresolvedSmithyVariableException(val variable: String) :
        RuntimeException("Variable \${$variable} is not set as a system property or environment variable")

    fun expand(input: String, builtins: Map<String, String> = emptyMap()): String {
        if (!input.contains('$')) return input
        val replaced = PATTERN.replace(input) { match ->
            val name = match.groupValues[1]
            val value = builtins[name] ?: System.getProperty(name) ?: System.getenv(name)
                ?: throw UnresolvedSmithyVariableException(name)
            // The pattern over-matches by one character to detect escapes; preserve any leading
            // non-`$` character that came along with the match.
            val matched = match.value
            val leading = if (matched.startsWith("\${")) "" else matched.substring(0, 1)
            leading + value
        }
        return replaced.replace("\\$", "$")
    }

    fun builtins(project: Project): Map<String, String> {
        val rootDir = project.guessProjectDir()?.path ?: return emptyMap()
        return mapOf("SMITHY_ROOT_DIR" to rootDir)
    }
}
