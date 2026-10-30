package software.amazon.smithy.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import software.amazon.smithy.intellij.actions.SmithySimplifyPatternQuickFix.Companion.simplify

/**
 * Unit tests for the pure double-escape -> #re simplification logic, plus annotator/quick-fix integration tests that
 * verify the suggestion is only offered for the prelude `smithy.api#pattern` trait.
 */
@Suppress("JUnitMixedFramework")
class SmithySimplifyPatternTest : BasePlatformTestCase() {
    //--- pure logic -----------------------------------------------------------------------------------------------

    @Test
    fun testSimplifyCollapsesEscapedBackslashes() {
        assertEquals("^\\d{1,5}$", simplify(""""^\\d{1,5}$""""))
        assertEquals("\\w+", simplify(""""\\w+""""))
    }

    @Test
    fun testSimplifyKeepsEscapedQuote() {
        //\\ collapses to \, \" is preserved (a bare quote would terminate the #re literal).
        assertEquals("a\\\"b\\d", simplify(""""a\"b\\d""""))
    }

    @Test
    fun testSimplifyRejectsStringWithoutEscapedBackslash() {
        //Nothing to simplify: no doubled backslash.
        assertNull(simplify(""""^abc$""""))
        assertNull(simplify(""""a\"b""""))
    }

    @Test
    fun testSimplifyRejectsMeaningChangingEscapes() {
        //\n, \t, \uXXXX would change meaning when backslashes are taken literally under #re.
        assertNull(simplify(""""\\d\n""""))
        assertNull(simplify(""""\\d\t""""))
        assertNull(simplify("\"\\\\d\\u0041\""))
    }

    //--- annotator / quick-fix integration ------------------------------------------------------------------------

    private val preludePattern = """
        namespace smithy.api

        @trait
        string pattern
    """.trimIndent()

    private fun errors(text: String): List<String> {
        myFixture.addFileToProject("prelude.smithy", preludePattern)
        myFixture.configureByText("test.smithy", text)
        return myFixture.doHighlighting().mapNotNull { it.description }
    }

    @Test
    fun testSuggestionOfferedForPreludePattern() {
        //A locally defined smithy.api#pattern (no real prelude in tests) applied to a shape with a double-escaped value.
        val errors = errors(
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.api#pattern

            @pattern("^\\d{1,5}${'$'}")
            string Zip
            """.trimIndent()
        )
        assertTrue(
            "expected a #re simplification suggestion, got: $errors",
            errors.any { it.contains("Simplify with a #re tagged literal") }
        )
    }

    @Test
    fun testSuggestionNotOfferedForNonPreludePattern() {
        //A pattern trait from a different namespace must NOT trigger the suggestion.
        myFixture.addFileToProject(
            "other.smithy",
            """
            namespace other.ns

            @trait
            string pattern
            """.trimIndent()
        )
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            use other.ns#pattern

            @pattern("^\\d{1,5}${'$'}")
            string Zip
            """.trimIndent()
        )
        val errors = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(
            "did not expect a suggestion for other.ns#pattern, got: $errors",
            errors.any { it.contains("Simplify with a #re tagged literal") }
        )
    }

    @Test
    fun testQuickFixRewritesToTaggedLiteral() {
        myFixture.addFileToProject("prelude.smithy", preludePattern)
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.api#pattern

            @pattern("^\\d{1,5}$<caret>")
            string Zip
            """.trimIndent()
        )
        val fix = myFixture.findSingleIntention("Convert to #re tagged literal")
        myFixture.launchAction(fix)
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.api#pattern

            @pattern(#re "^\d{1,5}$")
            string Zip
            """.trimIndent()
        )
    }
}
