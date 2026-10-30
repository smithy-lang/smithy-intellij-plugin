package software.amazon.smithy.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests the "extract inline collection to a named shape" intention (the "outgrowing inline syntax" migration).
 */
@Suppress("JUnitMixedFramework")
class SmithyExtractInlineCollectionTest : BasePlatformTestCase() {
    @Test
    fun testExtractsListWithReadableName() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: [Strin<caret>g]
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Extract inline collection to a named shape"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: ListOfString
            }

            list ListOfString {
                member: String
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testExtractsMapWithReadableName() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                tags: {Strin<caret>g: String}
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Extract inline collection to a named shape"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                tags: MapOfStringToString
            }

            map MapOfStringToString {
                key: String
                value: String
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testExtractAvoidsCollisionWithExistingSimpleName() {
        //A shape named ListOfString already exists in another namespace; the extracted shape must avoid that simple
        //name (it is referenced by simple name) and fall back to ListOfString2.
        myFixture.addFileToProject("other.smithy", "namespace other.ns\n\nlist ListOfString {\n    member: String\n}\n")
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: [Strin<caret>g]
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Extract inline collection to a named shape"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: ListOfString2
            }

            list ListOfString2 {
                member: String
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testNotAvailableUnderVersion20() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.0"

            namespace example

            structure Foo {
                names: [Strin<caret>g]
            }

            string String
            """.trimIndent()
        )
        assertEmpty(myFixture.filterAvailableIntentions("Extract inline collection to a named shape"))
    }
}
