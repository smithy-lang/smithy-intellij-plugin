package software.amazon.smithy.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests validation of IDL 2.1 member index shorthands: duplicate detection and the non-contiguous warning with its
 * renumber quick fix.
 */
@Suppress("JUnitMixedFramework")
class SmithyMemberIndexValidationTest : BasePlatformTestCase() {
    private fun errors(text: String): List<String> {
        myFixture.configureByText("test.smithy", text)
        return myFixture.doHighlighting(HighlightSeverity.WARNING).mapNotNull { it.description }
    }

    @Test
    fun testDuplicateIndexIsError() {
        val descriptions = errors(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Record {
                1. id: String
                1. value: String
            }

            string String
            """.trimIndent()
        )
        assertTrue("expected duplicate index error, got: $descriptions", descriptions.any { it.contains("Duplicate member index: 1") })
    }

    @Test
    fun testContiguousIndexesHaveNoWarning() {
        val descriptions = errors(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Record {
                1. id: String
                2. value: String
            }

            string String
            """.trimIndent()
        )
        assertFalse("expected no renumber warning, got: $descriptions", descriptions.any { it.contains("contiguous") })
    }

    @Test
    fun testGapTriggersRenumberFix() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Record {
                1. id: String
                3<caret>. value: String
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Renumber member indexes"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Record {
                1. id: String
                2. value: String
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testDuplicateIndexDetectedAcrossElidedMember() {
        //An elided member (1. $id) carries an index too; a duplicate between it and a regular member must be flagged.
        val descriptions = errors(
            """
            ${'$'}version: "2.1"

            namespace example

            @mixin
            structure HasId {
                id: String
            }

            structure Record with [HasId] {
                1. ${'$'}id
                1. value: String
            }

            string String
            """.trimIndent()
        )
        assertTrue(
            "expected duplicate index error involving the elided member, got: $descriptions",
            descriptions.any { it.contains("Duplicate member index: 1") }
        )
    }

    @Test
    fun testRenumberIncludesElidedMember() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            @mixin
            structure HasId {
                id: String
            }

            structure Record with [HasId] {
                1. ${'$'}id
                3<caret>. value: String
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Renumber member indexes"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            @mixin
            structure HasId {
                id: String
            }

            structure Record with [HasId] {
                1. ${'$'}id
                2. value: String
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testRenumberChangesElidedMemberIndexKeepingSpace() {
        //Here the elided member's own index changes (2 -> 1 after the gap is closed), exercising replacement of an
        //elided index rather than a no-op.
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            @mixin
            structure HasId {
                id: String
            }

            structure Record with [HasId] {
                2. value: String
                5<caret>. ${'$'}id
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Renumber member indexes"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            @mixin
            structure HasId {
                id: String
            }

            structure Record with [HasId] {
                1. value: String
                2. ${'$'}id
            }

            string String
            """.trimIndent()
        )
    }
}
