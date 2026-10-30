package software.amazon.smithy.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import software.amazon.smithy.intellij.psi.SmithyContainerMember
import software.amazon.smithy.intellij.psi.SmithyInlineListTarget
import software.amazon.smithy.intellij.psi.SmithyInlineMapTarget

/**
 * Tests for IDL 2.1 inline collection declarations: version gating, nesting-depth validation, and synthetic shape
 * resolution. Fixtures target locally-defined shapes to avoid depending on the Smithy prelude.
 */
@Suppress("JUnitMixedFramework")
class SmithyInlineCollectionAnnotatorTest : BasePlatformTestCase() {
    private fun errorDescriptions(text: String): List<String> {
        myFixture.configureByText("test.smithy", text)
        return myFixture.doHighlighting(HighlightSeverity.ERROR).mapNotNull { it.description }
    }

    @Test
    fun testInlineCollectionUnderVersion20IsError() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.0"

            namespace example

            structure Foo {
                names: [Id]
            }

            string Id
            """.trimIndent()
        )
        assertTrue(
            "expected inline-collection version error, got: $errors",
            errors.any { it.contains("Inline collections require") }
        )
    }

    @Test
    fun testInlineCollectionUnderVersion21IsValid() {
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: [Id]
                tags: {Id: Id}
            }

            string Id
            """.trimIndent()
        )
        assertEquals("expected no errors, got: $errors", emptyList<String>(), errors)
    }

    @Test
    fun testInlineCollectionNestedWithinLimitIsValid() {
        //3 levels: map -> list -> list is exactly at the limit.
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                deep: {Id: [[Id]]}
            }

            string Id
            """.trimIndent()
        )
        assertFalse(
            "expected no too-deep error at depth 3, got: $errors",
            errors.any { it.contains("nested more than 3") }
        )
    }

    @Test
    fun testInlineCollectionTooDeepIsError() {
        //4 levels: map -> list -> list -> list exceeds the limit.
        val errors = errorDescriptions(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                deep: {Id: [[[Id]]]}
            }

            string Id
            """.trimIndent()
        )
        assertTrue(
            "expected too-deep error at depth 4, got: $errors",
            errors.any { it.contains("nested more than 3 levels deep") }
        )
    }

    @Test
    fun testInlineListResolvesToSyntheticShape() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: [Id]
            }

            string Id
            """.trimIndent()
        )
        val list = PsiTreeUtil.collectElementsOfType(myFixture.file, SmithyInlineListTarget::class.java).single()
        assertEquals("example#_SyntheticListOf_Id", list.resolve()?.shapeId)
    }

    @Test
    fun testInlineMapResolvesToSyntheticShape() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                tags: {Id: Id}
            }

            string Id
            """.trimIndent()
        )
        val map = PsiTreeUtil.collectElementsOfType(myFixture.file, SmithyInlineMapTarget::class.java).single()
        assertEquals("example#_SyntheticMapOf_Id_To__Id", map.resolve()?.shapeId)
    }

    @Test
    fun testUpgradeVersionQuickFix() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.0"

            namespace example

            structure Foo {
                names: [<caret>Id]
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

            structure Foo {
                names: [Id]
            }

            string Id
            """.trimIndent()
        )
    }
}
