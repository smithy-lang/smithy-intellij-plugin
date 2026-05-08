package software.amazon.smithy.intellij

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Exercises [SmithyModule.resolveContentRoots] over real [VirtualFile]s. */
class SmithyModuleBuildTest : BasePlatformTestCase() {
    private fun writeBuildFile(content: String): VirtualFile = runWriteAction {
        val baseDir = myFixture.tempDirFixture.findOrCreateDir(".")
        val file = baseDir.findChild("smithy-build.json") ?: baseDir.createChildData(this, "smithy-build.json")
        file.setBinaryContent(content.toByteArray())
        file
    }

    private fun mkdirs(vararg paths: String) = runWriteAction {
        val baseDir = myFixture.tempDirFixture.findOrCreateDir(".")
        paths.forEach { path ->
            var dir = baseDir
            for (segment in path.split('/').filter { it.isNotEmpty() }) {
                dir = dir.findChild(segment) ?: dir.createChildDirectory(this, segment)
            }
        }
    }

    private fun parse(buildFile: VirtualFile): SmithyBuildConfiguration =
        SmithyJson.readValue(buildFile.contentsToByteArray(), SmithyBuildConfiguration::class.java)

    fun testResolvesRelativeSourceDirectory() {
        mkdirs("model")
        val buildFile = writeBuildFile("""{"version":"1.0","sources":["model"]}""")

        val roots = SmithyModule.resolveContentRoots(project, buildFile, parse(buildFile))

        assertEquals(1, roots.size)
        assertEquals("model", roots[0].name)
        assertTrue(roots[0].isDirectory)
    }

    fun testStripsLeadingDotSlashAndDeduplicates() {
        mkdirs("model")
        val buildFile = writeBuildFile("""{"version":"1.0","sources":["model","./model"]}""")

        val roots = SmithyModule.resolveContentRoots(project, buildFile, parse(buildFile))

        assertEquals(1, roots.size)
    }

    fun testFileSourceIsLiftedToParentDirectory() {
        mkdirs("model")
        runWriteAction {
            val baseDir = myFixture.tempDirFixture.findOrCreateDir(".")
            baseDir.findChild("model")!!.createChildData(this, "weather.smithy")
        }
        val buildFile = writeBuildFile("""{"version":"1.0","sources":["model/weather.smithy"]}""")

        val roots = SmithyModule.resolveContentRoots(project, buildFile, parse(buildFile))

        assertEquals(1, roots.size)
        assertEquals("model", roots[0].name)
    }

    fun testMissingSourceIsSkipped() {
        mkdirs("model")
        val buildFile = writeBuildFile("""{"version":"1.0","sources":["model","does-not-exist"]}""")

        val roots = SmithyModule.resolveContentRoots(project, buildFile, parse(buildFile))

        assertEquals(1, roots.size)
        assertEquals("model", roots[0].name)
    }

    fun testSourceWithUnresolvedVariableIsSkipped() {
        mkdirs("model")
        val buildFile = writeBuildFile(
            """{"version":"1.0","sources":["model","${"$"}{NOPE_NOT_SET_FOR_TEST}/extra"]}"""
        )

        val roots = SmithyModule.resolveContentRoots(project, buildFile, parse(buildFile))

        assertEquals(1, roots.size)
        assertEquals("model", roots[0].name)
    }

    fun testSourceWithExpandedSystemPropertyResolves() {
        mkdirs("nested/model")
        try {
            System.setProperty("SMITHY_TEST_NESTED", "nested")
            val buildFile = writeBuildFile(
                """{"version":"1.0","sources":["${"$"}{SMITHY_TEST_NESTED}/model"]}"""
            )

            val roots = SmithyModule.resolveContentRoots(project, buildFile, parse(buildFile))

            assertEquals(1, roots.size)
            assertEquals("model", roots[0].name)
        } finally {
            System.clearProperty("SMITHY_TEST_NESTED")
        }
    }

    fun testEmptyPathEntriesAreIgnored() {
        mkdirs("model")
        val buildFile = writeBuildFile("""{"version":"1.0","sources":["","   ","model"]}""")

        val roots = SmithyModule.resolveContentRoots(project, buildFile, parse(buildFile))

        assertEquals(1, roots.size)
    }
}
