package software.amazon.smithy.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests the "convert @idx to member index shorthand" quick fix (adopting IDL 2.1 member index syntax).
 */
@Suppress("JUnitMixedFramework")
class SmithyUseMemberIndexShorthandTest : BasePlatformTestCase() {
    private fun addIdxTrait() {
        myFixture.addFileToProject(
            "idx.smithy",
            """
            namespace smithy.protocols

            @trait
            integer idx
            """.trimIndent()
        )
    }

    @Test
    fun testConvertsIdxAndRemovesImport() {
        addIdxTrait()
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.protocols#idx

            structure Record {
                @idx(1<caret>)
                id: String
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Convert to member index shorthand"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Record {
                1. id: String
            }
            """.trimIndent()
        )
    }

    @Test
    fun testKeepsImportWhenStillUsed() {
        addIdxTrait()
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.protocols#idx

            structure Record {
                @idx(1<caret>)
                id: String

                @idx(2)
                value: String
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Convert to member index shorthand"))
        //Only the first member is converted; the import stays because the second @idx still needs it.
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.protocols#idx

            structure Record {
                1. id: String

                @idx(2)
                value: String
            }
            """.trimIndent()
        )
    }

    @Test
    fun testNotOfferedUnderVersion20() {
        addIdxTrait()
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.0"

            namespace example

            use smithy.protocols#idx

            structure Record {
                @idx(1<caret>)
                id: String
            }
            """.trimIndent()
        )
        assertEmpty(myFixture.filterAvailableIntentions("Convert to member index shorthand"))
    }
}
