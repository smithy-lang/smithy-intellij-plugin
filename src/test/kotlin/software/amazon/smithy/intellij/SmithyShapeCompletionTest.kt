package software.amazon.smithy.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests that shape-name completion prefers prelude (smithy.api) shapes over user-defined shapes that share a simple
 * name, so completing e.g. "String" does not insert an import for an unrelated shape.
 */
@Suppress("JUnitMixedFramework")
class SmithyShapeCompletionTest : BasePlatformTestCase() {
    private fun addPreludeString() {
        myFixture.addFileToProject("prelude.smithy", "namespace smithy.api\n\nstring String\n")
    }

    @Test
    fun testPreludeShapeRanksBeforeConflictingUserShape() {
        addPreludeString()
        myFixture.addFileToProject("other.smithy", "namespace com.foo\n\nstring String\n")
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.0"

            namespace example

            structure Foo {
                bar: Strin<caret>
            }
            """.trimIndent()
        )
        myFixture.completeBasic()
        //Both smithy.api#String and com.foo#String match and render as "String"; distinguish them by the namespace
        //shown in each element's tail text, and assert the prelude entry comes first.
        val namespaces = myFixture.lookupElements!!.mapNotNull { el ->
            val p = com.intellij.codeInsight.lookup.LookupElementPresentation()
            el.renderElement(p)
            p.tailText?.trim('(', ')')
        }.filter { it == "smithy.api" || it == "com.foo" }
        val preludeIndex = namespaces.indexOf("smithy.api")
        val userIndex = namespaces.indexOf("com.foo")
        assertTrue("expected both String entries, got: $namespaces", preludeIndex >= 0 && userIndex >= 0)
        assertTrue("prelude String must rank before user String, got: $namespaces", preludeIndex < userIndex)
    }

    @Test
    fun testCompletingPreludeStringInsertsBareNameWithoutImport() {
        addPreludeString()
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.0"

            namespace example

            structure Foo {
                bar: Strin<caret>
            }
            """.trimIndent()
        )
        //Only the prelude String exists; completing it should insert a bare "String" and add no use statement.
        myFixture.completeBasic()
        myFixture.checkResult(
            """
            ${'$'}version: "2.0"

            namespace example

            structure Foo {
                bar: String
            }
            """.trimIndent()
        )
    }
}
