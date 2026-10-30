package software.amazon.smithy.intellij.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import software.amazon.smithy.intellij.SmithyElementFactory
import software.amazon.smithy.intellij.psi.SmithyString

/**
 * An [IntentionAction] which rewrites a double-escaped regular expression string into the equivalent IDL 2.1 `#re`
 * tagged literal, e.g. `@pattern("^\\d{1,5}$")` becomes `@pattern(#re "^\d{1,5}$")`.
 *
 * In a `#re` literal backslashes are taken literally, so the doubled backslashes required by a plain string collapse to
 * single backslashes, which is both shorter and closer to how the pattern reads as a regular expression.
 *
 * @since 1.0
 */
class SmithySimplifyPatternQuickFix(private val string: SmithyString) : BaseIntentionAction() {
    override fun getText() = "Convert to #re tagged literal"
    override fun getFamilyName() = "Simplify regular expression with #re"
    override fun isAvailable(project: Project, editor: Editor, file: PsiFile) = true
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val simplified = simplify(string.text) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            string.replace(SmithyElementFactory.createTaggedString(project, "re", simplified))
        }
    }

    companion object {
        /**
         * Returns true when [stringText] (a quoted plain string, including the surrounding quotes) can be losslessly
         * rewritten as a `#re` tagged literal, i.e. it contains at least one escaped backslash (`\\`) to simplify and no
         * escape sequence whose meaning would change when backslashes are taken literally.
         */
        fun canSimplify(stringText: String): Boolean = simplify(stringText) != null

        /**
         * Returns the raw `#re` content (without surrounding quotes) equivalent to the quoted plain string [stringText],
         * or null when the conversion is not applicable or not safe.
         *
         * Safe conversions are limited to strings whose only escapes are `\\` (collapsed to `\`) and `\"` (kept as-is,
         * since `"` would terminate the literal and `\"` is equivalent to `"` in a regex). Any other escape (`\n`, `\t`,
         * `\uXXXX`, ...) would change meaning when backslashes are taken literally, so those strings are rejected.
         */
        fun simplify(stringText: String): String? {
            if (stringText.length < 2 || !stringText.startsWith('"') || !stringText.endsWith('"')) return null
            val inner = stringText.substring(1, stringText.length - 1)
            val result = StringBuilder()
            var sawEscapedBackslash = false
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c != '\\') {
                    result.append(c)
                    i++
                    continue
                }
                if (i == inner.length - 1) return null //dangling backslash, not a valid plain string
                when (inner[i + 1]) {
                    '\\' -> {
                        result.append('\\')
                        sawEscapedBackslash = true
                    }
                    '"' -> result.append("\\\"") //keep: a bare quote would close the #re literal
                    else -> return null //any other escape would change meaning under #re
                }
                i += 2
            }
            return if (sawEscapedBackslash) result.toString() else null
        }
    }
}
