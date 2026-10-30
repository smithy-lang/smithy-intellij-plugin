package software.amazon.smithy.intellij

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Verifies that a `use smithy.protocols#idx` import is reported as unused when the file only uses the IDL 2.1 member
 * index shorthand (which applies `@idx` synthetically, without writing an `idx` shape id).
 */
@Suppress("JUnitMixedFramework")
class SmithyMemberIndexImportTest : BasePlatformTestCase() {
    @Test
    fun testIdxImportUnusedWhenOnlyShorthandUsed() {
        myFixture.addFileToProject(
            "idx.smithy",
            """
            namespace smithy.protocols

            @trait
            integer idx
            """.trimIndent()
        )
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            use smithy.protocols#idx

            structure Record {
                1. id: String
            }
            """.trimIndent()
        )
        val infos = myFixture.doHighlighting(HighlightSeverity.INFORMATION).mapNotNull { it.description }
        assertTrue("expected the idx import to be reported unused, got: $infos", infos.contains("Unused import"))
    }
}
