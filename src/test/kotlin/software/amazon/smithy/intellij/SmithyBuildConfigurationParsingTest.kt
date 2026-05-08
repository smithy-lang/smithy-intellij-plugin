package software.amazon.smithy.intellij

import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmithyBuildConfigurationParsingTest {
    @Test
    fun `parses minimal config with only version`() {
        val config = SmithyJson.readValue<SmithyBuildConfiguration>("""{"version":"1.0"}""")
        assertEquals("1.0", config.version)
        assertTrue(config.sources.isEmpty())
        assertTrue(config.imports.isEmpty())
        assertNull(config.maven)
    }

    @Test
    fun `parses sources and imports as string lists`() {
        val config = SmithyJson.readValue<SmithyBuildConfiguration>(
            """
            {
              "version": "1.0",
              "sources": ["model", "src/main/smithy"],
              "imports": ["extra.smithy"]
            }
            """.trimIndent()
        )
        assertEquals(listOf("model", "src/main/smithy"), config.sources)
        assertEquals(listOf("extra.smithy"), config.imports)
    }

    @Test
    fun `parses maven block with dependencies and repositories`() {
        val config = SmithyJson.readValue<SmithyBuildConfiguration>(
            """
            {
              "version": "1.0",
              "maven": {
                "dependencies": ["com.example:foo:1.0", "com.example:bar:2.0"],
                "repositories": [
                  {"id": "central", "url": "https://repo.maven.apache.org/maven2/"},
                  {"url": "https://example.com/repo"}
                ]
              }
            }
            """.trimIndent()
        )
        val maven = assertNotNull(config.maven).let { config.maven!! }
        assertEquals(listOf("com.example:foo:1.0", "com.example:bar:2.0"), maven.dependencies)
        assertEquals(2, maven.repositories.size)
        assertEquals("central", maven.repositories[0].id)
        assertEquals("https://repo.maven.apache.org/maven2/", maven.repositories[0].url)
        assertNull(maven.repositories[1].id)
        assertEquals("https://example.com/repo", maven.repositories[1].url)
    }

    @Test
    fun `repository credentials and proxy fields round-trip`() {
        val config = SmithyJson.readValue<SmithyBuildConfiguration>(
            """
            {
              "version": "1.0",
              "maven": {
                "dependencies": [],
                "repositories": [
                  {
                    "url": "https://private.example.com/repo",
                    "httpCredentials": "user:pass",
                    "proxyHost": "proxy.example.com:8080",
                    "proxyCredentials": "u:p"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val repo = config.maven!!.repositories.single()
        assertEquals("user:pass", repo.httpCredentials)
        assertEquals("proxy.example.com:8080", repo.proxyHost)
        assertEquals("u:p", repo.proxyCredentials)
    }

    @Test
    fun `parses projections with transforms and plugins`() {
        val config = SmithyJson.readValue<SmithyBuildConfiguration>(
            """
            {
              "version": "1.0",
              "projections": {
                "trimmed": {
                  "abstract": false,
                  "transforms": [
                    {"name": "excludeShapesBySelector", "args": {"selector": "[trait|internal]"}}
                  ],
                  "plugins": {
                    "model": {}
                  }
                }
              }
            }
            """.trimIndent()
        )
        val projection = config.projections["trimmed"]!!
        val transform = projection.transforms.single()
        assertEquals("excludeShapesBySelector", transform.name)
        assertEquals("[trait|internal]", transform.args["selector"])
        assertTrue(projection.plugins!!.containsKey("model"))
    }

    @Test
    fun `unknown top-level fields are ignored`() {
        val config = SmithyJson.readValue<SmithyBuildConfiguration>(
            """{"version": "1.0", "unknownThing": 42, "anotherUnknown": {"x": 1}}"""
        )
        assertEquals("1.0", config.version)
    }

    @Test
    fun `placeholders are preserved verbatim during parsing`() {
        // Variable expansion happens at use-time, not at parse-time.
        val config = SmithyJson.readValue<SmithyBuildConfiguration>(
            """
            {
              "version": "1.0",
              "sources": ["${"$"}{SMITHY_ROOT_DIR}/model"],
              "maven": {
                "dependencies": ["com.example:foo:${"$"}{FOO_VERSION}"],
                "repositories": [
                  {"url": "https://${"$"}{HOST}/repo"}
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals(listOf("\${SMITHY_ROOT_DIR}/model"), config.sources)
        assertEquals(listOf("com.example:foo:\${FOO_VERSION}"), config.maven!!.dependencies)
        assertEquals("https://\${HOST}/repo", config.maven!!.repositories.single().url)
    }
}
