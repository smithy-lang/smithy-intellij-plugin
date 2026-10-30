package software.amazon.smithy.intellij

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil.getParentOfType
import com.intellij.util.ProcessingContext
import com.intellij.util.applyIf
import software.amazon.smithy.intellij.index.SmithyDefinedShapeIdIndex
import software.amazon.smithy.intellij.psi.SmithyControl
import software.amazon.smithy.intellij.psi.SmithyElidedMember
import software.amazon.smithy.intellij.psi.SmithyEntry
import software.amazon.smithy.intellij.psi.SmithyImport
import software.amazon.smithy.intellij.psi.SmithyIncompleteEntry
import software.amazon.smithy.intellij.psi.SmithyIncompleteMember
import software.amazon.smithy.intellij.psi.SmithyMemberId
import software.amazon.smithy.intellij.psi.SmithyMetadata
import software.amazon.smithy.intellij.psi.SmithyObject
import software.amazon.smithy.intellij.psi.SmithyOperationBody
import software.amazon.smithy.intellij.psi.SmithyResourceBody
import software.amazon.smithy.intellij.psi.SmithyServiceBody
import software.amazon.smithy.intellij.psi.SmithyShapeDefinition
import software.amazon.smithy.intellij.psi.SmithyShapeId
import software.amazon.smithy.intellij.psi.SmithyTrait
import software.amazon.smithy.intellij.psi.SmithyTraitBody
import software.amazon.smithy.intellij.psi.SmithyTypes

/**
 * A [CompletionContributor] for [Smithy](https://awslabs.github.io/smithy).
 *
 * @author Ian Caffey
 * @since 1.0
 */
class SmithyCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC,
            PlatformPatterns.psiElement(SmithyTypes.TOKEN_SYMBOL),
            object : CompletionProvider<CompletionParameters>() {
                val unwrappedShapeTypes = setOf("map", "structure", "union")
                override fun addCompletions(
                    parameters: CompletionParameters, context: ProcessingContext, results: CompletionResultSet
                ) {
                    val element = parameters.originalPosition ?: return
                    if (getParentOfType(element, PsiErrorElement::class.java) != null) return
                    getParentOfType(element, SmithyControl::class.java)?.let {
                        setOf("version", "operationInputSuffix", "operationOutputSuffix").forEach {
                            results.addElement(LookupElementBuilder.create(it))
                        }
                    }
                    getParentOfType(element, SmithyElidedMember::class.java)?.let {
                        addElidedTargetMembers(it.enclosingShape, it.name, results)
                    }
                    getParentOfType(element, SmithyEntry::class.java)?.let { addMembers(it, it.key.text, results) }
                    getParentOfType(element, SmithyIncompleteEntry::class.java)?.let {
                        addMembers(it, it.key.text, results)
                    }
                    getParentOfType(element, SmithyMemberId::class.java)?.let {
                        val parent = it.shapeId.reference.resolve()
                        parent?.members?.forEach { member ->
                            member.resolvedTarget?.let { target ->
                                results.addElement(memberElement(member.name, target.shapeName, it))
                            }
                        }
                    }
                    getParentOfType(element, SmithyShapeId::class.java)?.let {
                        val startingTraitMember = it.parent.let { parent ->
                            parent is SmithyTraitBody && parent.parent.let { trait ->
                                trait is SmithyTrait && trait.resolve()?.type in unwrappedShapeTypes
                            }
                        }
                        if (startingTraitMember) {
                            addMembers(it, it.text, results)
                        } else {
                            addShapes(it, results)
                        }
                    }
                    getParentOfType(element, SmithyIncompleteMember::class.java)?.let {
                        when (it.parent) {
                            is SmithyOperationBody -> setOf("errors", "input", "output")
                            is SmithyResourceBody -> setOf(
                                "collectionOperations",
                                "create",
                                "delete",
                                "identifiers",
                                "list",
                                "operations",
                                "properties",
                                "put",
                                "read",
                                "resources",
                                "update"
                            )
                            is SmithyServiceBody -> setOf(
                                "version",
                                "operations",
                                "resources",
                                "errors",
                                "rename"
                            )
                            else -> emptySet()
                        }.forEach { name -> results.addElement(LookupElementBuilder.create(name)) }
                    }
                }
            })
    }
}

private fun addShapes(element: PsiElement, results: CompletionResultSet) {
    SmithyDefinedShapeIdIndex.forEach(element.resolveScope) {
        val (namespace, shapeName) = it.split('#', limit = 2)
        //Prefer prelude (smithy.api) shapes: when a user-defined shape shares a simple name with a prelude shape
        //(e.g. a local "String"), the prelude shape should rank first and be selected by default so completion does
        //not insert an import for the unrelated shape.
        val priority = if (namespace == "smithy.api") 1.0 else 0.0
        results.addElement(
            PrioritizedLookupElement.withPriority(shapeElement(element, namespace, shapeName), priority)
        )
    }
}

private fun addElidedTargetMembers(shape: SmithyShapeDefinition, prefix: String, results: CompletionResultSet) {
    val memberResults = results.withPrefixMatcher(prefix)
    if (SmithyShapeAggregator.hasCycle(shape)) return
    shape.resource?.resolve()?.let { target ->
        target.identifiers.forEach { memberResults.addElement(identifierElement(it.name, it.enclosingShape.name)) }
        target.properties.forEach { memberResults.addElement(propertyElement(it.name, it.enclosingShape.name)) }
    }
    val mixinMembers = mutableMapOf<String, String>()
    shape.mixins.forEach { target ->
        val mixin = target.resolve() ?: return@forEach
        mixin.members.forEach { mixinMembers[it.name] = mixin.name }
    }
    mixinMembers.forEach { (memberName, shapeName) ->
        memberResults.addElement(memberElement(memberName, shapeName))
    }
}

private fun addMembers(element: PsiElement, prefix: String, results: CompletionResultSet) {
    val memberResults = results.withPrefixMatcher(prefix)
    val enclosingShape = when (val parent = element.parent) {
        is SmithyObject -> parent.reference.resolve()
        is SmithyTraitBody -> (parent.parent as? SmithyTrait)?.resolve()
        else -> null
    }
    enclosingShape?.members?.forEach { member ->
        member.resolvedTarget?.let { target ->
            memberResults.addElement(memberElement(member.name, target.shapeName))
        }
    }
}

private fun shapeElement(
    element: PsiElement,
    namespace: String,
    shapeName: String
): LookupElementBuilder {
    val withinMetadata = getParentOfType(element, SmithyMetadata::class.java) != null
    val hasConflicts = (element.containingFile as? SmithyFile)?.model?.shapes?.any {
        it.shapeName == shapeName && namespace != it.namespace
    } == true
    val importRequired = !withinMetadata && !hasConflicts && getParentOfType(element, SmithyImport::class.java) == null
    val qualifiedName = "$namespace#$shapeName"
    return LookupElementBuilder.create(
        qualifiedName, //used to disambiguate amongst other lookup elements
        if (importRequired || (withinMetadata && namespace == "smithy.api")) shapeName else qualifiedName //the text which is actually inserted
    )
        .withIcon(SmithyIcons.SHAPE)
        .withPresentableText(shapeName)
        .withLookupString(shapeName)
        .withLookupString(qualifiedName)
        .withTailText("($namespace)")
        .applyIf(importRequired) {
            withInsertHandler { context, _ ->
                context.file.let { it as? SmithyFile }?.let {
                    val model = it.model ?: return@let
                    val enclosingNamespace = model.namespace
                    if (enclosingNamespace == namespace) return@let
                    if (enclosingNamespace != null && namespace == "smithy.api"
                        && !SmithyDefinedShapeIdIndex.exists(enclosingNamespace, shapeName, it.resolveScope)
                    ) return@let
                    SmithyElementFactory.addImport(it, namespace, shapeName)
                }
            }
        }
}

private fun identifierElement(
    memberName: String,
    shapeName: String
): LookupElementBuilder {
    return LookupElementBuilder.create(memberName)
        .withPresentableText(memberName)
        .withTypeText(shapeName)
        .withIcon(SmithyIcons.RESOURCE_IDENTIFIER)
}

private fun memberElement(
    memberName: String,
    shapeName: String,
    memberId: SmithyMemberId? = null
): LookupElementBuilder {
    return LookupElementBuilder.create(if (memberId != null) "${memberId.shapeId.text}$$memberName" else memberName)
        .withPresentableText(memberName)
        .withTypeText(shapeName)
        .withIcon(SmithyIcons.MEMBER)
}

private fun propertyElement(
    memberName: String,
    shapeName: String
): LookupElementBuilder {
    return LookupElementBuilder.create(memberName)
        .withPresentableText(memberName)
        .withTypeText(shapeName)
        .withIcon(SmithyIcons.RESOURCE_PROPERTY)
}
