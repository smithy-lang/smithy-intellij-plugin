package software.amazon.smithy.intellij

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import software.amazon.smithy.intellij.SmithyVariableExpansion.UnresolvedSmithyVariableException

class SmithyVariableExpansionTest {
    private val touchedSystemProperties = mutableListOf<String>()

    @After
    fun tearDown() {
        touchedSystemProperties.forEach { System.clearProperty(it) }
        touchedSystemProperties.clear()
    }

    private fun setSystemProperty(name: String, value: String) {
        touchedSystemProperties += name
        System.setProperty(name, value)
    }

    @Test
    fun `returns input unchanged when there is no dollar sign`() {
        val input = "plain string with no placeholder"
        assertSame(input, SmithyVariableExpansion.expand(input))
    }

    @Test
    fun `expands a single placeholder from builtins`() {
        val result = SmithyVariableExpansion.expand("\${ROOT}/model", mapOf("ROOT" to "/tmp/x"))
        assertEquals("/tmp/x/model", result)
    }

    @Test
    fun `expands multiple placeholders in one string`() {
        val result = SmithyVariableExpansion.expand(
            "\${A}-\${B}-\${A}",
            mapOf("A" to "1", "B" to "2")
        )
        assertEquals("1-2-1", result)
    }

    @Test
    fun `escaped placeholder is treated as literal`() {
        val result = SmithyVariableExpansion.expand("\\\${HOME}", mapOf("HOME" to "/should-not-be-used"))
        assertEquals("\${HOME}", result)
    }

    @Test
    fun `escape only protects the immediately following placeholder`() {
        val result = SmithyVariableExpansion.expand(
            "\\\${A} and \${B}",
            mapOf("A" to "first", "B" to "second")
        )
        assertEquals("\${A} and second", result)
    }

    @Test
    fun `unresolved variable throws`() {
        val ex = assertThrows(UnresolvedSmithyVariableException::class.java) {
            SmithyVariableExpansion.expand("\${DEFINITELY_NOT_SET_IN_TEST_ENV}")
        }
        assertEquals("DEFINITELY_NOT_SET_IN_TEST_ENV", ex.variable)
    }

    @Test
    fun `builtins take precedence over system properties`() {
        setSystemProperty("SMITHY_TEST_VAR", "from-sysprop")
        val result = SmithyVariableExpansion.expand(
            "\${SMITHY_TEST_VAR}",
            mapOf("SMITHY_TEST_VAR" to "from-builtin")
        )
        assertEquals("from-builtin", result)
    }

    @Test
    fun `system property is used when builtin is absent`() {
        setSystemProperty("SMITHY_TEST_VAR", "from-sysprop")
        val result = SmithyVariableExpansion.expand("\${SMITHY_TEST_VAR}")
        assertEquals("from-sysprop", result)
    }

    @Test
    fun `placeholder at the start of the string expands`() {
        val result = SmithyVariableExpansion.expand("\${X}/tail", mapOf("X" to "head"))
        assertEquals("head/tail", result)
    }

    @Test
    fun `placeholder name allows dots and other non-brace characters`() {
        val result = SmithyVariableExpansion.expand(
            "\${some.weird-name}/x",
            mapOf("some.weird-name" to "v")
        )
        assertEquals("v/x", result)
    }

    @Test
    fun `expansion result is not re-expanded`() {
        val result = SmithyVariableExpansion.expand(
            "\${A}",
            mapOf("A" to "\${B}", "B" to "should-not-appear")
        )
        assertEquals("\${B}", result)
    }
}
