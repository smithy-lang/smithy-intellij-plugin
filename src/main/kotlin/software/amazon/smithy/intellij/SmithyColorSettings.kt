package software.amazon.smithy.intellij

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey

/**
 * A collection of color settings used in [SmithyAnnotator]/[SmithySyntaxHighlighter] and customized in [SmithyColorSettingsPage].
 *
 * @author Ian Caffey
 * @since 1.0
 */
object SmithyColorSettings {
    val BRACES = createTextAttributesKey("SMITHY_BRACES", DefaultLanguageHighlighterColors.BRACES)
    val BRACKETS = createTextAttributesKey("SMITHY_BRACKET", DefaultLanguageHighlighterColors.BRACKETS)
    val COMMA = createTextAttributesKey("SMITHY_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val CONTROL = createTextAttributesKey("SMITHY_CONTROL", DefaultLanguageHighlighterColors.KEYWORD)
    val DOC_COMMENT = createTextAttributesKey("SMITHY_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val DOT = createTextAttributesKey("SMITHY_DOT", DefaultLanguageHighlighterColors.DOT)
    val INVALID_ESCAPE_SEQUENCE = createTextAttributesKey(
        "SMITHY_INVALID_ESCAPE_SEQUENCE", DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE
    )
    val KEY = createTextAttributesKey(
        "SMITHY_KEY", DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )
    val KEYWORD = createTextAttributesKey("SMITHY_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val LINE_COMMENT = createTextAttributesKey("SMITHY_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val NUMBER = createTextAttributesKey("SMITHY_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val PARENS = createTextAttributesKey("SMITHY_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
    val SHAPE_MEMBER = createTextAttributesKey("SMITHY_SHAPE_MEMBER", DefaultLanguageHighlighterColors.INSTANCE_FIELD)
    val STRING = createTextAttributesKey("SMITHY_STRING", DefaultLanguageHighlighterColors.STRING)
    val TAG = createTextAttributesKey("SMITHY_TAG", DefaultLanguageHighlighterColors.METADATA)
    val TRAIT_NAME = createTextAttributesKey("SMITHY_TRAIT_NAME", DefaultLanguageHighlighterColors.METADATA)
    val VALID_ESCAPE_SEQUENCE = createTextAttributesKey(
        "SMITHY_VALID_ESCAPE_SEQUENCE", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
    )
}