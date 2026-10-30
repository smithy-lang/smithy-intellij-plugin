package software.amazon.smithy.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import software.amazon.smithy.intellij.psi.SmithyTaggedString

/**
 * Tests for [SmithyAnnotator] behavior related to IDL 2.1 tagged string literals.
 *
 * Highlighting expectations are asserted programmatically. The test environment has no Smithy prelude, so the fixtures
 * place tagged literals in metadata (a top-level node value) to avoid prelude-dependent trait resolution noise.
 */
@Suppress("JUnitMixedFramework")
class SmithyTaggedLiteralAnnotatorTest : BasePlatformTestCase() {
    private fun errorDescriptions(text: String): List<String> {
        myFixture.configureByText("test.smithy", text)
        return myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description }
    }

    @Test
    fun testTaggedLiteralUnderVersion20IsError() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.0"

            metadata foo = #re "^\d+${'$'}"

            namespace example
            """.trimIndent()
        )
        assertTrue(
            "expected tagged-literal version error, got: $errors",
            errors.any { it.contains("Tagged string literals require") }
        )
    }

    @Test
    fun testTagRecognizedWithMultipleSpacesAndTabs() {
        //Smithy's [SP] after the tag is one-or-more spaces/tabs; all of these must tokenize as a tagged literal (proven
        //by the version error, which only fires when #re is recognized as a tag rather than a plain # token).
        listOf("#re  \"^\\d+${'$'}\"", "#re\t\"^\\d+${'$'}\"", "#re\"^\\d+${'$'}\"").forEach { literal ->
            val errors = errorDescriptions(
                """
                ${'$'}version: "2.0"

                metadata foo = $literal

                namespace example
                """.trimIndent()
            )
            assertTrue(
                "expected [$literal] to be recognized as a tagged literal, got: $errors",
                errors.any { it.contains("Tagged string literals require") }
            )
        }
    }

    @Test
    fun testTaggedLiteralUnderVersion21IsValid() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            metadata foo = #re "^\d+${'$'}"

            namespace example
            """.trimIndent()
        )
        assertEquals("expected no errors, got: $errors", emptyList<String>(), errors)
    }

    @Test
    fun testTaggedLiteralSuppressesEscapeSequenceValidation() {
        //"\d" is not a valid Smithy escape sequence; inside a normal string it would be flagged, but inside a #re
        //tagged literal the escape validation must be suppressed.
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            metadata foo = #re "\d\w+"

            namespace example
            """.trimIndent()
        )
        assertEquals("expected no errors inside a tagged literal, got: $errors", emptyList<String>(), errors)
    }

    @Test
    fun testInvalidEscapeStillFlaggedInPlainString() {
        //Sanity check: the same invalid escape IS flagged in a plain (untagged) string.
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            metadata foo = "\d"

            namespace example
            """.trimIndent()
        )
        assertTrue(
            "expected an invalid-escape error in a plain string, got: $errors",
            errors.any { it.contains("Invalid escape sequence") }
        )
    }

    @Test
    fun testTaggedLiteralInTraitContextHasNoUnresolvedShapeError() {
        //Regression: the inner string of a #re literal must not be treated as a shape reference (previously it was
        //flagged "Unresolved shape" in trait-value position, showing red after the pattern quick fix).
        myFixture.addFileToProject(
            "prelude.smithy",
            """
            namespace smithy.api

            @trait
            string pattern
            """.trimIndent()
        )
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.api#pattern

            @pattern(#re "^\d{5}${'$'}")
            string Zip
            """.trimIndent()
        )
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description }
        assertFalse(
            "the #re content must not be flagged as an unresolved shape, got: $errors",
            errors.any { it.contains("Unresolved shape") }
        )
    }

    @Test
    fun testTaggedTextBlockInTraitContextHasNoUnresolvedShapeError() {
        //Regression: the same soft-reference handling must apply to a tagged text block (#re """...""").
        myFixture.addFileToProject(
            "prelude.smithy",
            """
            namespace smithy.api

            @trait
            string pattern
            """.trimIndent()
        )
        myFixture.configureByText(
            "test.smithy",
            "\$version: \"2.1\"\n\nnamespace example\n\nuse smithy.api#pattern\n\n" +
                "@pattern(#re \"\"\"\n    ^\\d{5}(-\\d{4})?\$\n    \"\"\")\nstring Zip\n"
        )
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description }
        assertFalse(
            "the #re text block content must not be flagged as an unresolved shape, got: $errors",
            errors.any { it.contains("Unresolved shape") }
        )
    }

    @Test
    fun testUpgradeVersionQuickFix() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.0"

            metadata foo = #re "^\d+$<caret>"

            namespace example
            """.trimIndent()
        )
        val fix = myFixture.findSingleIntention("Upgrade to \$version: \"2.1\"")
        myFixture.launchAction(fix)
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            metadata foo = #re "^\d+$"

            namespace example
            """.trimIndent()
        )
    }

    private fun taggedString(text: String): SmithyTaggedString {
        myFixture.configureByText("test.smithy", text)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, SmithyTaggedString::class.java).single()
    }

    @Test
    fun testAsStringReturnsRawContentForQuotedString() {
        //"\d" is not a valid plain-string escape; the standard asString() would null/mangle it. A tagged literal must
        //return the raw inner content verbatim (no escape processing), since #re takes backslashes literally.
        val tagged = taggedString(
            """
            ${'$'}version: "2.1"

            metadata foo = #re "^\d{5}${'$'}"

            namespace example
            """.trimIndent()
        )
        assertEquals("^\\d{5}${'$'}", tagged.asString())
        assertEquals("re", tagged.tag)
    }

    @Test
    fun testAsStringReturnsRawContentForTextBlock() {
        val tagged = taggedString(
            "\$version: \"2.1\"\n\nmetadata foo = #re \"\"\"\n^\\d{5}${'$'}\n\"\"\"\n\nnamespace example"
        )
        assertEquals("^\\d{5}${'$'}\n", tagged.asString())
    }
}
