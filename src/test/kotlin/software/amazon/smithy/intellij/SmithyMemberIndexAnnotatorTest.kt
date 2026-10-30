package software.amazon.smithy.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests for [SmithyAnnotator] annotations related to IDL 2.1 member index shorthand, and the associated quick fixes.
 *
 * Highlighting expectations are asserted programmatically (rather than via inline `<error>` markers) so the fixtures
 * can target locally-defined shapes and avoid depending on the Smithy prelude being present in the test environment.
 */
@Suppress("JUnitMixedFramework")
class SmithyMemberIndexAnnotatorTest : BasePlatformTestCase() {
    private fun errorDescriptions(text: String): List<String> {
        myFixture.configureByText("test.smithy", text)
        return myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description }
    }

    @Test
    fun testMemberIndexUnderVersion20IsError() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.0"

            namespace example

            structure Record {
                1. id: Id
            }

            string Id
            """.trimIndent()
        )
        assertTrue(
            "expected member-index version error, got: $errors",
            errors.any { it.contains("Member index shorthand requires") }
        )
    }

    @Test
    fun testMemberIndexUnderVersion21IsValid() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Record {
                1. id: Id
            }

            string Id
            """.trimIndent()
        )
        assertEquals("expected no errors, got: $errors", emptyList<String>(), errors)
    }

    @Test
    fun testMemberIndexOnListMemberIsError() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            namespace example

            list Names {
                1. member: Id
            }

            string Id
            """.trimIndent()
        )
        assertEquals(
            listOf("Member indexes are only allowed on structure and union members"), errors
        )
    }

    @Test
    fun testMemberIndexOnListUnderVersion20FiresBothErrors() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.0"

            namespace example

            list Names {
                1. member: Id
            }

            string Id
            """.trimIndent()
        )
        assertTrue(
            "expected version error, got: $errors",
            errors.any { it.contains("Member index shorthand requires") }
        )
        assertTrue(
            "expected shape-target error, got: $errors",
            errors.any { it == "Member indexes are only allowed on structure and union members" }
        )
    }

    @Test
    fun testUpgradeVersionQuickFixEditsExistingControl() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.0"

            namespace example

            structure Record {
                1<caret>. id: Id
            }

            string Id
            """.trimIndent()
        )
        val fix = myFixture.findSingleIntention("Upgrade to \$version: \"2.1\"")
        myFixture.launchAction(fix)
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Record {
                1. id: Id
            }

            string Id
            """.trimIndent()
        )
    }

    @Test
    fun testRemoveMemberIndexQuickFix() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            list Names {
                1<caret>. member: Id
            }

            string Id
            """.trimIndent()
        )
        val fix = myFixture.findSingleIntention("Remove member index '1.'")
        myFixture.launchAction(fix)
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            list Names {
                member: Id
            }

            string Id
            """.trimIndent()
        )
    }
}
