package software.amazon.smithy.intellij

import com.intellij.testFramework.ParsingTestCase
import org.junit.Test

@Suppress("JUnitMixedFramework")
class SmithyParserTest : ParsingTestCase("parsing", "smithy", SmithyParserDefinition()) {
    override fun skipSpaces() = true
    override fun includeRanges() = true
    override fun getTestDataPath() = "src/test/resources"

    @Test
    fun testBlank() = doTest(true)

    @Test
    fun testEmpty() = doTest(true)

    @Test
    fun testNamespace_InvalidEmpty() = doTest(true)

    @Test
    fun testNamespace_InvalidEmptyId() = doTest(true)

    @Test
    fun testNamespace_InvalidNumeric() = doTest(true)

    @Test
    fun testNamespace_InvalidUnderscore() = doTest(true)

    @Test
    fun testNamespace_MultiPart() = doTest(true)

    @Test
    fun testNamespace_Simple() = doTest(true)

    @Test
    fun testNamespace_UnderscoreFollowedByNumber() = doTest(true)

    @Test
    fun testSimple() = doTest(true)

    @Test
    fun testMemberIndex() = doTest(true)

    @Test
    fun testTaggedLiterals() = doTest(true)

    @Test
    fun testInlineCollections() = doTest(true)

    @Test
    fun testWeather() = doTest(true)
}
