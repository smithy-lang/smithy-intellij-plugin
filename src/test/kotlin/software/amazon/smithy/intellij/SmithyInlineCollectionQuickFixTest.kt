package software.amazon.smithy.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Tests for the "inline collection" quick fix, which converts an explicit list/map shape into IDL 2.1 inline syntax at
 * every member that targets it and removes the now-unused shape.
 */
@Suppress("JUnitMixedFramework")
class SmithyInlineCollectionQuickFixTest : BasePlatformTestCase() {
    private fun suggestionOffered(text: String): Boolean {
        myFixture.configureByText("test.smithy", text)
        return myFixture.doHighlighting().any { it.description?.contains("Convert") == true && it.description?.contains("inline collection") == true }
    }

    @Test
    fun testOnlyRewritesTheExactShapeNotSameNamedShapeInOtherNamespace() {
        //A different shape with the same simple name exists (and is used) in another namespace. The conversion must
        //only rewrite references to the exact shape being inlined and must not delete or touch the other one.
        myFixture.addFileToProject(
            "other.smithy",
            """
            namespace other.ns

            list StringList {
                member: String
            }

            structure OtherUser {
                names: StringList
            }

            string String
            """.trimIndent()
        )
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names<caret>: StringList
            }

            list StringList {
                member: String
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Inline collection 'StringList'"))
        //example#StringList is inlined and removed; other.ns#StringList and its usage are untouched.
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: [String]
            }

            string String
            """.trimIndent()
        )
        assertEquals(
            "other.ns#StringList must be untouched",
            "namespace other.ns\n\nlist StringList {\n    member: String\n}\n\nstructure OtherUser {\n    names: StringList\n}\n\nstring String",
            myFixture.findFileInTempDir("other.smithy").let {
                com.intellij.psi.PsiManager.getInstance(project).findFile(it)!!.text
            }
        )
    }

    @Test
    fun testConvertsListAndRemovesShape() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names<caret>: StringList
            }

            list StringList {
                member: String
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Inline collection 'StringList'"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                names: [String]
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testConvertsMap() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                tags<caret>: TagMap
            }

            map TagMap {
                key: String
                value: String
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Inline collection 'TagMap'"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                tags: {String: String}
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testConvertsAllReferencesAtOnce() {
        myFixture.configureByText(
            "test.smithy",
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                a<caret>: StringList
            }

            structure Bar {
                b: StringList
            }

            list StringList {
                member: String
            }

            string String
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("Inline collection 'StringList'"))
        myFixture.checkResult(
            """
            ${'$'}version: "2.1"

            namespace example

            structure Foo {
                a: [String]
            }

            structure Bar {
                b: [String]
            }

            string String
            """.trimIndent()
        )
    }

    @Test
    fun testNotOfferedWhenCollectionHasTrait() {
        assertFalse(
            suggestionOffered(
                """
                ${'$'}version: "2.1"

                namespace example

                structure Foo {
                    names: StringList
                }

                @sparse
                list StringList {
                    member: String
                }

                string String

                @trait
                structure sparse {}
                """.trimIndent()
            )
        )
    }

    @Test
    fun testNotOfferedWhenMemberHasTrait() {
        assertFalse(
            suggestionOffered(
                """
                ${'$'}version: "2.1"

                namespace example

                structure Foo {
                    names: StringList
                }

                list StringList {
                    @required
                    member: String
                }

                string String

                @trait
                structure required {}
                """.trimIndent()
            )
        )
    }

    @Test
    fun testNotOfferedUnderVersion20() {
        assertFalse(
            suggestionOffered(
                """
                ${'$'}version: "2.0"

                namespace example

                structure Foo {
                    names: StringList
                }

                list StringList {
                    member: String
                }

                string String
                """.trimIndent()
            )
        )
    }

    @Test
    fun testNotOfferedWhenCollectionInOtherNamespace() {
        myFixture.addFileToProject(
            "other.smithy",
            """
            namespace other.ns

            list StringList {
                member: String
            }

            string String
            """.trimIndent()
        )
        assertFalse(
            suggestionOffered(
                """
                ${'$'}version: "2.1"

                namespace example

                use other.ns#StringList

                structure Foo {
                    names: StringList
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun testNotOfferedWhenTraitAppliedViaApply() {
        //A trait applied through a separate `apply` statement is not in declaredTraits; it must still block inlining.
        assertFalse(
            suggestionOffered(
                """
                ${'$'}version: "2.1"

                namespace example

                structure Foo {
                    names: StringList
                }

                list StringList {
                    member: String
                }

                apply StringList @sparse

                string String

                @trait
                structure sparse {}
                """.trimIndent()
            )
        )
    }

    @Test
    fun testNotOfferedWhenMemberBoundToResourceProperty() {
        //Inlining a member bound to a resource property would retarget it away from the property's declared shape.
        assertFalse(
            suggestionOffered(
                """
                ${'$'}version: "2.1"

                namespace example

                resource R {
                    properties: { names: StringList }
                    read: Read
                }

                @readonly
                operation Read {
                    output: Foo
                }

                structure Foo for R {
                    names: StringList
                }

                list StringList {
                    member: String
                }

                string String

                @trait
                structure readonly {}
                """.trimIndent()
            )
        )
    }
}
