package software.amazon.smithy.intellij

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.SpacingBuilder
import com.intellij.psi.tree.TokenSet
import software.amazon.smithy.intellij.psi.SmithyTypes

/**
 * A [FormattingModelBuilder] for [Smithy](https://awslabs.github.io/smithy) model files.
 *
 * @author Ian Caffey
 * @since 1.0
 * @see SmithyBlock
 */
class SmithyFormattingModelBuilder : FormattingModelBuilder {
    companion object {
        val TOKENS_REQUIRING_TRAILING_NEW_LINE = TokenSet.create(
            SmithyTypes.APPLIED_TRAIT,
            SmithyTypes.CONTROL,
            SmithyTypes.DOCUMENTATION,
            SmithyTypes.IMPORT,
            SmithyTypes.LIST,
            SmithyTypes.MAP,
            SmithyTypes.METADATA,
            SmithyTypes.NAMESPACE,
            SmithyTypes.OPERATION,
            SmithyTypes.RESOURCE,
            SmithyTypes.SERVICE,
            SmithyTypes.SET,
            SmithyTypes.SIMPLE_SHAPE,
            SmithyTypes.STRUCTURE,
            SmithyTypes.TOKEN_LINE_COMMENT,
            SmithyTypes.UNION
        )
    }

    @Suppress("UnstableApiUsage")
    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val codeStyleSettings = formattingContext.codeStyleSettings
        val spacingBuilder = SpacingBuilder(codeStyleSettings, SmithyLanguage)
            //No extra spaces within array/object literal
            .afterInside(SmithyTypes.TOKEN_OPEN_BRACE, SmithyTypes.OBJECT).spacing(0, 0, 0, true, 0)
            .beforeInside(SmithyTypes.TOKEN_CLOSE_BRACE, SmithyTypes.OBJECT).spacing(0, 0, 0, true, 0)
            .afterInside(SmithyTypes.TOKEN_OPEN_BRACKET, SmithyTypes.ARRAY).spacing(0, 0, 0, true, 0)
            .beforeInside(SmithyTypes.TOKEN_CLOSE_BRACKET, SmithyTypes.ARRAY).spacing(0, 0, 0, true, 0)
            //No extra spaces within inline collection declarations (IDL 2.1): [Target] and {Key: Value}
            .afterInside(SmithyTypes.TOKEN_OPEN_BRACKET, SmithyTypes.INLINE_LIST_TARGET).spacing(0, 0, 0, false, 0)
            .beforeInside(SmithyTypes.TOKEN_CLOSE_BRACKET, SmithyTypes.INLINE_LIST_TARGET).spacing(0, 0, 0, false, 0)
            .afterInside(SmithyTypes.TOKEN_OPEN_BRACE, SmithyTypes.INLINE_MAP_TARGET).spacing(0, 0, 0, false, 0)
            .beforeInside(SmithyTypes.TOKEN_CLOSE_BRACE, SmithyTypes.INLINE_MAP_TARGET).spacing(0, 0, 0, false, 0)
            //No extra spaces within trait body
            .afterInside(SmithyTypes.TOKEN_OPEN_PAREN, SmithyTypes.TRAIT_BODY).spacing(0, 0, 0, true, 0)
            .beforeInside(SmithyTypes.TOKEN_CLOSE_PAREN, SmithyTypes.TRAIT_BODY).spacing(0, 0, 0, true, 0)
            //No extra spaces within container bodies
            .afterInside(SmithyTypes.TOKEN_OPEN_BRACE, SmithyTypes.CONTAINER_BODY).spacing(0, 0, 0, true, 0)
            .beforeInside(SmithyTypes.TOKEN_CLOSE_BRACE, SmithyTypes.CONTAINER_BODY).spacing(0, 0, 0, true, 0)
            //Force single space within other braces (e.g. inline i/o, resource/operation members)
            .afterInside(SmithyTypes.TOKEN_OPEN_BRACE, TokenSet.forAllMatching { it != SmithyTypes.CONTAINER_BODY })
            .spacing(1, 1, 0, true, 0)
            .beforeInside(SmithyTypes.TOKEN_CLOSE_BRACE, TokenSet.forAllMatching { it != SmithyTypes.CONTAINER_BODY })
            .spacing(1, 1, 0, true, 0)
            //No extra spaces around $ ... but a member index shorthand before an elided member keeps its single space
            //(this must precede the generic $ rule, since the first matching spacing rule wins).
            .between(SmithyTypes.MEMBER_INDEX, SmithyTypes.TOKEN_DOLLAR_SIGN).spacing(1, 1, 0, false, 0)
            .around(SmithyTypes.TOKEN_DOLLAR_SIGN).none()
            //No extra spaces before :
            .before(TokenSet.create(SmithyTypes.TOKEN_COLON)).none()
            //Space before container bodies (to add separation from the shape name or traits for inline I/O)
            .before(
                TokenSet.create(
                    SmithyTypes.CONTAINER_BODY,
                    SmithyTypes.ENUM_BODY,
                    SmithyTypes.INT_ENUM_BODY,
                    SmithyTypes.MEMBER_INITIALIZER
                )
            ).spaces(1)
            //Space after : and ,
            .after(TokenSet.create(SmithyTypes.TOKEN_COLON, SmithyTypes.TOKEN_COMMA)).spaces(1)
            //Exactly one space after a member index shorthand (e.g. "1. member") - column alignment is not applied
            .after(SmithyTypes.MEMBER_INDEX).spacing(1, 1, 0, false, 0)
            //Spaces around =, :=, for, mixins list, and with
            .around(
                TokenSet.create(
                    SmithyTypes.TOKEN_EQUALS,
                    SmithyTypes.TOKEN_FOR,
                    SmithyTypes.MIXINS,
                    SmithyTypes.TOKEN_WALRUS,
                    SmithyTypes.TOKEN_WITH
                )
            ).spaces(1)
            //Blank lines after all top-level declarations (not before so line comments can be placed)
            .after(
                TokenSet.create(
                    SmithyTypes.LIST,
                    SmithyTypes.MAP,
                    SmithyTypes.NAMESPACE,
                    SmithyTypes.OPERATION,
                    SmithyTypes.RESOURCE,
                    SmithyTypes.SERVICE,
                    SmithyTypes.SET,
                    SmithyTypes.SIMPLE_SHAPE,
                    SmithyTypes.STRUCTURE,
                    SmithyTypes.UNION
                )
            ).blankLines(1)
            //Line break after all traits
            .after(TOKENS_REQUIRING_TRAILING_NEW_LINE).lineBreakInCode()
            //No empty lines around members
            .around(
                TokenSet.create(
                    SmithyTypes.CONTAINER_MEMBER,
                    SmithyTypes.ENUM_MEMBER,
                    SmithyTypes.INT_ENUM_MEMBER,
                    SmithyTypes.OPERATION_INPUT,
                    SmithyTypes.OPERATION_OUTPUT,
                    SmithyTypes.OPERATION_ERRORS,
                    SmithyTypes.RESOURCE_COLLECTION_OPERATIONS,
                    SmithyTypes.RESOURCE_CREATE_OPERATION,
                    SmithyTypes.RESOURCE_DELETE_OPERATION,
                    SmithyTypes.RESOURCE_COLLECTION_OPERATIONS,
                    SmithyTypes.RESOURCE_IDENTIFIERS,
                    SmithyTypes.RESOURCE_LIST_OPERATION,
                    SmithyTypes.RESOURCE_OPERATIONS,
                    SmithyTypes.RESOURCE_PROPERTIES,
                    SmithyTypes.RESOURCE_PUT_OPERATION,
                    SmithyTypes.RESOURCE_READ_OPERATION,
                    SmithyTypes.RESOURCE_RESOURCES,
                    SmithyTypes.RESOURCE_UPDATE_OPERATION,
                    SmithyTypes.SERVICE_ERRORS,
                    SmithyTypes.SERVICE_OPERATIONS,
                    SmithyTypes.SERVICE_RENAMES,
                    SmithyTypes.SERVICE_RESOURCES,
                    SmithyTypes.SERVICE_VERSION
                )
            ).spacing(0, 0, 0, true, 0)
        return FormattingModelProvider.createFormattingModelForPsiFile(
            formattingContext.containingFile, SmithyBlock(formattingContext.node, spacingBuilder), codeStyleSettings
        )
    }
}
