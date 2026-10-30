package software.amazon.smithy.intellij.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocCommentBase
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil.getChildOfType
import com.intellij.psi.util.PsiTreeUtil.getChildrenOfTypeAsList
import com.intellij.psi.util.PsiTreeUtil.getParentOfType
import com.intellij.psi.util.siblings
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.SmithyIdReference
import software.amazon.smithy.intellij.SmithyKeyReference
import software.amazon.smithy.intellij.SmithyLanguage
import software.amazon.smithy.intellij.SmithyMemberReference
import software.amazon.smithy.intellij.SmithyReference
import software.amazon.smithy.intellij.SmithyShapeAggregator.getDeclaration
import software.amazon.smithy.intellij.SmithyShapeReference
import software.amazon.smithy.intellij.SmithyShapeResolver.getNamespace
import software.amazon.smithy.intellij.psi.impl.SmithyContainerShapeImpl
import software.amazon.smithy.intellij.psi.impl.SmithyKeyedElementImpl
import software.amazon.smithy.intellij.psi.impl.SmithyOperationPropertyImpl
import software.amazon.smithy.intellij.psi.impl.SmithyPrimitiveImpl
import software.amazon.smithy.intellij.psi.impl.SmithyShapeImpl
import software.amazon.smithy.intellij.psi.impl.SmithyValueImpl
import java.math.BigDecimal
import java.math.BigInteger

/**
 * A base [PsiElement] for all [SmithyElement] implementations.
 *
 * @author Ian Caffey
 * @since 1.0
 */
open class SmithyPsiElement(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun toString(): String = name ?: text
}

interface SmithyElement : PsiElement
interface SmithyContainer : SmithyElement
interface SmithyNamedElement : SmithyElement, PsiNameIdentifierOwner {
    override fun getName(): String
    override fun getNameIdentifier(): PsiElement
}

abstract class SmithySyntheticElement : FakePsiElement(), SmithyElement {
    override fun getLanguage() = SmithyLanguage
    override fun getTextOffset() = parent?.node?.startOffset ?: 0
    override fun getTextRange() = TextRange.from(textOffset, textLength)
}

interface SmithyStatement : SmithyElement {
    val type: String get() = typeIdentifier.text
    val typeIdentifier: PsiElement get() = firstChild
}

interface SmithyAppliedTraitExt : SmithyStatement {
    val memberId: SmithyMemberId?
    val shapeId: SmithyShapeId?
    val target: SmithyDefinition?
    val traits: List<SmithyTrait>
}

abstract class SmithyAppliedTraitMixin(node: ASTNode) : SmithyPsiElement(node), SmithyAppliedTrait {
    override val memberId: SmithyMemberId? get() = findChildByClass(SmithyMemberId::class.java)
    override val shapeId: SmithyShapeId? get() = findChildByClass(SmithyShapeId::class.java)
    override val target get() = memberId?.resolve() ?: shapeId?.resolve()
    override val traits: List<SmithyTrait>
        get() = getChildOfType(this, SmithyTrait::class.java)?.let { listOf(it) }
            ?: getChildOfType(this, SmithyAppliedTraitBody::class.java)?.traits
            ?: emptyList()
}

interface SmithyArrayExt : SmithyValue {
    override val reference: SmithyShapeReference
}

abstract class SmithyArrayMixin(node: ASTNode) : SmithyValueImpl(node), SmithyArray {
    override val type = SmithyValueType.ARRAY
    override val reference by lazy { SmithyShapeReference(this) }
}

interface SmithyBooleanExt : SmithyPrimitive {
    override val reference: SmithyShapeReference
    override fun asBoolean(): Boolean
}

abstract class SmithyBooleanMixin(node: ASTNode) : SmithyPrimitiveImpl(node), SmithyBoolean {
    override val type = SmithyValueType.BOOLEAN
    override val reference by lazy { SmithyShapeReference(this) }
    override fun asBoolean() = text.toBoolean()
}

interface SmithyContainerMemberExt : SmithyNamedElement, SmithyMemberDefinition {
    override val enclosingShape: SmithyShape
    override val declaredTarget: SmithyShapeTarget?
    override val declaredTraits: List<SmithyTrait>
}

abstract class SmithyContainerMemberMixin(node: ASTNode) : SmithyPsiElement(node), SmithyContainerMember {
    private var _syntheticTraits: List<SmithyTraitDefinition>? = null
    override val enclosingShape: SmithyContainerShape get() = getParentOfType(this, SmithyContainerShape::class.java)!!
    override val syntheticTraits: List<SmithyTraitDefinition>
        get() = _syntheticTraits ?: mutableListOf<SmithyTraitDefinition>().also {
            getChildOfType(this, SmithyDocumentation::class.java)?.let { docs ->
                it +=
                    SmithySyntheticTrait(
                        this,
                        "smithy.api",
                        "documentation",
                        SmithySyntheticValue.String(docs.toDocString())
                    )
            }
            initializer?.value?.let { default -> it += SmithySyntheticTrait(this, "smithy.api", "default", default) }
            memberIndex?.index?.let { idx ->
                it += SmithySyntheticTrait(
                    this, "smithy.protocols", "idx", SmithySyntheticValue.Number(idx.toBigDecimal())
                )
            }
            _syntheticTraits = it
        }

    override fun getName(): String = nameIdentifier.text
    override fun setName(newName: String) = setName<SmithyContainerMember>(this, newName)
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = resolvedTarget?.let { "$name: ${it.shapeName}" } ?: name
        override fun getLocationString() = enclosingShape.shapeName
        override fun getIcon(unused: Boolean) = getIcon(0)
    }

    override fun subtreeChanged() {
        _syntheticTraits = null
    }
}

abstract class SmithyContainerShapeMixin(node: ASTNode) : SmithyShapeImpl(node), SmithyContainerShape {
    override val declaredMembers get() = body.members
}

interface SmithyControlExt : SmithyStatement
abstract class SmithyControlMixin(node: ASTNode) : SmithyKeyedElementImpl(node), SmithyControl

interface SmithyDocumentationExt : PsiDocCommentBase, SmithyElement {
    fun toDocString(): String
}

abstract class SmithyDocumentationMixin(node: ASTNode) : SmithyPsiElement(node), SmithyDocumentation {
    companion object {
        private val DOCUMENTATION_LINES = TokenSet.create(SmithyTypes.TOKEN_DOCUMENTATION_LINE)
    }

    override fun getOwner(): PsiElement = parent
    override fun getTokenType(): IElementType = SmithyTypes.DOCUMENTATION

    //see: https://awslabs.github.io/smithy/1.0/spec/core/idl.html#documentation-comment
    override fun toDocString() = node.getChildren(DOCUMENTATION_LINES).joinToString("\n") { node ->
        node.text.let { it.substring(if (it.length > 3 && it[3] == ' ') 4 else 3) }
    }
}

interface SmithyElidedMemberExt : SmithyNamedElement, SmithyMemberDefinition {
    val declaration: SmithyElidedMemberTarget?
    override val enclosingShape: SmithyShape
    override val declaredTraits: List<SmithyTrait>
}

abstract class SmithyElidedMemberMixin(node: ASTNode) : SmithyPsiElement(node), SmithyElidedMember {
    private var _syntheticTraits: List<SmithyTraitDefinition>? = null
    override val declaration get() = getDeclaration(this)
    override val enclosingShape: SmithyShape get() = getParentOfType(this, SmithyShape::class.java)!!
    override val declaredTarget: SmithyShapeTarget? = null
    override val resolvedTarget: SmithyShapeTarget? get() = declaration?.resolvedTarget
    override val syntheticTraits: List<SmithyTraitDefinition>
        get() = _syntheticTraits ?: mutableListOf<SmithyTraitDefinition>().also {
            getChildOfType(this, SmithyDocumentation::class.java)?.let { docs ->
                it += SmithySyntheticTrait(
                    this,
                    "smithy.api",
                    "documentation",
                    SmithySyntheticValue.String(docs.toDocString())
                )
            }
            initializer?.value?.let { default -> it += SmithySyntheticTrait(this, "smithy.api", "default", default) }
            memberIndex?.index?.let { idx ->
                it += SmithySyntheticTrait(
                    this, "smithy.protocols", "idx", SmithySyntheticValue.Number(idx.toBigDecimal())
                )
            }
            _syntheticTraits = it
        }

    override fun getName(): String = nameIdentifier.text
    override fun setName(newName: String) = setName<SmithyElidedMember>(this, newName)
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = resolvedTarget?.let { "$name: ${it.shapeName}" } ?: name
        override fun getLocationString() = enclosingShape.shapeName
        override fun getIcon(unused: Boolean) = getIcon(0)
    }

    override fun subtreeChanged() {
        _syntheticTraits = null
    }
}

interface SmithyEntryExt : SmithyElement {
    fun resolve(): SmithyMemberDefinition?
}

abstract class SmithyEntryMixin(node: ASTNode) : SmithyKeyedElementImpl(node), SmithyEntry {
    override fun resolve() = key.reference.resolve()
}

abstract class SmithyEnumMixin(node: ASTNode) : SmithyShapeImpl(node), SmithyEnum {
    override val declaredMembers get() = body.members
}

interface SmithyEnumMemberExt : SmithyNamedElement, SmithyMemberDefinition {
    override val enclosingShape: SmithyEnum
    override val declaredTarget: SmithySyntheticShapeTarget
    override val resolvedTarget: SmithySyntheticShapeTarget
    override val declaredTraits: List<SmithyTrait>
}

abstract class SmithyEnumMemberMixin(node: ASTNode) : SmithyPsiElement(node), SmithyEnumMember {
    private var _syntheticTraits: List<SmithyTraitDefinition>? = null
    override val declaredTarget = SmithySyntheticShapeTarget(this, "smithy.api", "Unit", "string")
    override val resolvedTarget = declaredTarget
    override val enclosingShape: SmithyEnum get() = getParentOfType(this, SmithyEnum::class.java)!!
    override val syntheticTraits: List<SmithyTraitDefinition>
        get() = _syntheticTraits ?: mutableListOf<SmithyTraitDefinition>().also {
            getChildOfType(this, SmithyDocumentation::class.java)?.let { docs ->
                it += SmithySyntheticTrait(
                    this,
                    "smithy.api",
                    "documentation",
                    SmithySyntheticValue.String(docs.toDocString())
                )
            }
            initializer?.value?.let { value -> it += SmithySyntheticTrait(this, "smithy.api", "enumValue", value) }
            _syntheticTraits = it
        }

    override fun getName(): String = nameIdentifier.text
    override fun setName(newName: String) = setName<SmithyEnumMember>(this, newName)
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = name
        override fun getLocationString() = enclosingShape.shapeName
        override fun getIcon(unused: Boolean) = getIcon(0)
    }

    override fun subtreeChanged() {
        _syntheticTraits = null
    }
}

abstract class SmithyIdMixin(node: ASTNode) : SmithyPsiElement(node), SmithyId {
    val reference by lazy { SmithyIdReference(this) }
}

interface SmithyImportExt : SmithyStatement
abstract class SmithyImportMixin(node: ASTNode) : SmithyPsiElement(node), SmithyImport

interface SmithyIncompleteAppliedTraitExt : SmithyStatement
abstract class SmithyIncompleteAppliedTraitMixin(node: ASTNode) : SmithyPsiElement(node), SmithyIncompleteAppliedTrait

interface SmithyInputExt : SmithyContainerShape, SmithyShapeTarget {
    override val href: String get() = super<SmithyContainerShape>.href
    override val resource: SmithyShapeId?
    val operation: SmithyOperation
}

abstract class SmithyInputMixin(node: ASTNode) : SmithyContainerShapeImpl(node), SmithyInput {
    private var _syntheticTraits: List<SmithyTraitDefinition>? = null
    private val id = object : SmithySyntheticElement() {
        override fun getParent() = this@SmithyInputMixin
        override fun getTextOffset() = body.textOffset
        override fun getText() =
            "${operation.name}${(containingFile as? SmithyFile)?.model?.operationInputSuffix ?: "Input"}"
    }
    override val operation get() = getParentOfType(this, SmithyOperation::class.java)!!
    override val resource get() = resourceReference?.shapeId
    override val declaredNamespace = namespace
    override val resolvedNamespace = namespace
    override val syntheticTraits: List<SmithyTraitDefinition>
        get() = _syntheticTraits ?: mutableListOf<SmithyTraitDefinition>().also {
            getChildOfType(this, SmithyDocumentation::class.java)?.let { docs ->
                it += SmithySyntheticTrait(
                    this,
                    "smithy.api",
                    "documentation",
                    SmithySyntheticValue.String(docs.toDocString())
                )
            }
            it += SmithySyntheticTrait(this, "smithy.api", "input")
            _syntheticTraits = it
        }
    override val typeIdentifier = object : SmithySyntheticElement() {
        override fun getParent() = this@SmithyInputMixin
        override fun getText() = "structure"
    }

    override fun getNameIdentifier() = id
    override fun resolve() = this
    override fun subtreeChanged() {
        _syntheticTraits = null
    }
}

abstract class SmithyIntEnumMixin(node: ASTNode) : SmithyShapeImpl(node), SmithyIntEnum {
    override val declaredMembers get() = body.members
}

interface SmithyIntEnumMemberExt : SmithyNamedElement, SmithyMemberDefinition {
    override val enclosingShape: SmithyIntEnum
    override val declaredTarget: SmithySyntheticShapeTarget
    override val resolvedTarget: SmithySyntheticShapeTarget
    override val declaredTraits: List<SmithyTrait>
}

abstract class SmithyIntEnumMemberMixin(node: ASTNode) : SmithyPsiElement(node), SmithyIntEnumMember {
    private var _syntheticTraits: List<SmithyTraitDefinition>? = null
    override val declaredTarget = SmithySyntheticShapeTarget(this, "smithy.api", "Unit", "integer")
    override val resolvedTarget = declaredTarget
    override val enclosingShape: SmithyIntEnum get() = getParentOfType(this, SmithyIntEnum::class.java)!!
    override val syntheticTraits: List<SmithyTraitDefinition>
        get() = _syntheticTraits ?: mutableListOf<SmithyTraitDefinition>().also {
            getChildOfType(this, SmithyDocumentation::class.java)?.let { docs ->
                it += SmithySyntheticTrait(
                    this,
                    "smithy.api",
                    "documentation",
                    SmithySyntheticValue.String(docs.toDocString())
                )
            }
            initializer?.value?.let { value -> it += SmithySyntheticTrait(this, "smithy.api", "enumValue", value) }
            _syntheticTraits = it
        }

    override fun getName(): String = nameIdentifier.text
    override fun setName(newName: String) = setName<SmithyIntEnumMember>(this, newName)
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = name
        override fun getLocationString() = enclosingShape.shapeName
        override fun getIcon(unused: Boolean) = getIcon(0)
    }

    override fun subtreeChanged() {
        _syntheticTraits = null
    }
}

interface SmithyKeyExt : SmithyElement {
    val reference: SmithyKeyReference
    fun stringValue(): String
}

abstract class SmithyKeyMixin(node: ASTNode) : SmithyPsiElement(node), SmithyKey {
    override val reference by lazy { SmithyKeyReference(this) }
    override fun stringValue(): String = id?.text ?: string?.asString() ?: text
}

interface SmithyKeyedElementExt : SmithyNamedElement {
    val nameIdentifier: SmithyKey
}

abstract class SmithyKeyedElementMixin(node: ASTNode) : SmithyPsiElement(node), SmithyKeyedElement {
    override val nameIdentifier get() = key
    override fun getName(): String = key.stringValue()
    override fun setName(newName: String) = setName<SmithyKeyedElement>(this, newName)
    override fun getTextOffset() = key.textOffset
}

abstract class SmithyListMixin(node: ASTNode) : SmithyContainerShapeImpl(node), SmithyList {
    override val requiredMembers = setOf("member")
}

abstract class SmithyMapMixin(node: ASTNode) : SmithyContainerShapeImpl(node), SmithyMap {
    override val requiredMembers = setOf("key", "value")
}

interface SmithyMemberBodyExt : SmithyElement {
    val members: List<SmithyMemberDefinition>
}

abstract class SmithyMemberBodyMixin(node: ASTNode) : SmithyPsiElement(node), SmithyMemberBodyExt {
    override val members: List<SmithyMemberDefinition>
        get() = getChildrenOfTypeAsList(this, SmithyMemberDefinition::class.java)
}

interface SmithyMemberIdExt : SmithyCharSequence {
    override val reference: SmithyMemberReference
    val memberName: String
    fun resolve() = reference.resolve()
}

abstract class SmithyMemberIdMixin(node: ASTNode) : SmithyPsiElement(node), SmithyMemberId {
    override val memberName: String get() = member.text
    override val reference by lazy { SmithyMemberReference(this) }
    override fun asString() = shapeId.asString()?.let { "$it$$memberName" } ?: memberName
}

interface SmithyMemberInitializerExt : SmithyElement {
    val enclosingMember: SmithyMemberDefinition
}

abstract class SmithyMemberInitializerMixin(node: ASTNode) : SmithyPsiElement(node), SmithyMemberInitializer {
    override val enclosingMember: SmithyMemberDefinition get() = parent as SmithyMemberDefinition
}

interface SmithyMemberIndexExt : SmithyElement {
    val enclosingMember: SmithyMemberDefinition
    val index: Int?
}

abstract class SmithyMemberIndexMixin(node: ASTNode) : SmithyPsiElement(node), SmithyMemberIndex {
    override val enclosingMember: SmithyMemberDefinition get() = parent as SmithyMemberDefinition
    //Note: the token is a positive integer followed by a period (e.g. "1."), so the trailing period is dropped.
    //The value may exceed Int range for pathological inputs; those are left for the assembler to reject, so parsing
    //returns null rather than throwing.
    override val index: Int? get() = text.removeSuffix(".").toIntOrNull()
}

interface SmithyMetadataExt : SmithyStatement
abstract class SmithyMetadataMixin(node: ASTNode) : SmithyKeyedElementImpl(node), SmithyMetadata

interface SmithyMixinsExt : SmithyElement {
    val enclosingShape: SmithyShape
}

abstract class SmithyMixinsMixin(node: ASTNode) : SmithyPsiElement(node), SmithyMixins {
    override val enclosingShape: SmithyShape get() = parent as SmithyShape
}

interface SmithyModelExt : SmithyElement {
    val namespace: String?
    val version: String?
    val operationInputSuffix: String
    val operationOutputSuffix: String
    val control: List<SmithyControl>
    val metadata: List<SmithyMetadata>
    val shapes: List<SmithyShape>
}

abstract class SmithyModelMixin(node: ASTNode) : SmithyPsiElement(node), SmithyModel {
    override val namespace get() = getChildOfType(this, SmithyNamespace::class.java)?.namespaceId?.id
    override val version get() = control.firstNotNullOfOrNull { if (it.name == "version") it.value.asString() else null }
    override val operationInputSuffix
        get() = control.firstNotNullOfOrNull {
            if (it.name == "operationInputSuffix") it.value.asString() else null
        } ?: "Input"
    override val operationOutputSuffix
        get() = control.firstNotNullOfOrNull {
            if (it.name == "operationOutputSuffix") it.value.asString() else null
        } ?: "Output"
    override val control: List<SmithyControl> get() = getChildrenOfTypeAsList(this, SmithyControl::class.java)
    override val metadata: List<SmithyMetadata> get() = getChildrenOfTypeAsList(this, SmithyMetadata::class.java)
    override val shapes: List<SmithyShape>
        get() = mutableListOf<SmithyShape>().also { shapes ->
            firstChild?.siblings()?.forEach {
                if (it is SmithyShape) {
                    shapes += it
                    if (it is SmithyOperation) {
                        it.input?.shape?.let { input -> shapes += input }
                        it.output?.shape?.let { output -> shapes += output }
                    }
                }
            }
        }
}

interface SmithyNamespaceExt : SmithyStatement
abstract class SmithyNamespaceMixin(node: ASTNode) : SmithyPsiElement(node), SmithyNamespace

interface SmithyNamespaceIdExt : SmithyElement {
    val id: String
}

abstract class SmithyNamespaceIdMixin(node: ASTNode) : SmithyPsiElement(node), SmithyNamespaceId {
    override val id get() = parts.joinToString(".") { it.text }
    override fun toString() = id
}

interface SmithyNullExt : SmithyPrimitive {
    override val reference: SmithyShapeReference
}

abstract class SmithyNullMixin(node: ASTNode) : SmithyPrimitiveImpl(node), SmithyNull {
    override val type = SmithyValueType.NULL
    override val reference by lazy { SmithyShapeReference(this) }
}

interface SmithyNumberExt : SmithyPrimitive {
    override val reference: SmithyShapeReference
    fun byteValue(): Byte = bigDecimalValue().byteValueExact()
    fun shortValue(): Short = bigDecimalValue().shortValueExact()
    fun intValue(): Int = bigDecimalValue().intValueExact()
    fun floatValue(): Float = bigDecimalValue().toFloat()
    fun doubleValue(): Double = bigDecimalValue().toDouble()
    fun longValue(): Long = bigDecimalValue().longValueExact()
    fun bigDecimalValue(): BigDecimal
    fun bigIntegerValue(): BigInteger = bigDecimalValue().toBigIntegerExact()
}

abstract class SmithyNumberMixin(node: ASTNode) : SmithyPrimitiveImpl(node), SmithyNumber {
    override val type = SmithyValueType.NUMBER
    override val reference by lazy { SmithyShapeReference(this) }
    override fun asNumber() = bigDecimalValue()
    override fun bigDecimalValue() = BigDecimal(text)
}

interface SmithyObjectExt : SmithyValue {
    override val reference: SmithyShapeReference
}

abstract class SmithyObjectMixin(node: ASTNode) : SmithyValueImpl(node), SmithyObject {
    private var parsed = false
    private var value: Map<String, SmithyValue> = emptyMap()
    override val type = SmithyValueType.OBJECT
    override val fields
        get() = if (parsed) value else entries.associate { it.name to it.value }.also {
            value = it
            parsed = true
        }
    override val reference by lazy { SmithyShapeReference(this) }

    override fun subtreeChanged() {
        parsed = false
    }
}

interface SmithyOperationExt : SmithyShape {
    override val input: SmithyOperationInput?
    override val output: SmithyOperationOutput?
    val errors: SmithyOperationErrors?
}

abstract class SmithyOperationMixin(node: ASTNode) : SmithyShapeImpl(node), SmithyOperation {
    override val input get() = body.properties.firstNotNullOfOrNull { it as? SmithyOperationInput }
    override val output get() = body.properties.firstNotNullOfOrNull { it as? SmithyOperationOutput }
    override val errors get() = body.properties.firstNotNullOfOrNull { it as? SmithyOperationErrors }
}

interface SmithyOperationPropertyExt : SmithyNamedElement {
    val enclosingShape: SmithyOperation
}

abstract class SmithyOperationPropertyMixin(node: ASTNode) : SmithyPsiElement(node), SmithyOperationProperty {
    override val enclosingShape: SmithyOperation get() = getParentOfType(this, SmithyOperation::class.java)!!
    override fun getName(): String = nameIdentifier.text
    override fun setName(newName: String): PsiElement? = null
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = name
        override fun getLocationString() = enclosingShape.shapeName
        override fun getIcon(unused: Boolean) = getIcon(0)
    }
}

interface SmithyOperationErrorsExt : SmithyOperationPropertyExt

abstract class SmithyOperationErrorsMixin(node: ASTNode) : SmithyOperationPropertyImpl(node), SmithyOperationErrors {
    override fun getNameIdentifier(): PsiElement = findChildByType(SmithyTypes.TOKEN_ERRORS)!!
}

interface SmithyOperationInputExt : SmithyOperationPropertyExt, SmithyShapeTarget {
    val target: SmithyShapeTarget
}

abstract class SmithyOperationInputMixin(node: ASTNode) : SmithyOperationPropertyImpl(node), SmithyOperationInput {
    override val target: SmithyShapeTarget get() = shapeId ?: shape!!
    override val href get() = target.href
    override val shapeName get() = target.shapeName
    override val declaredNamespace get() = target.declaredNamespace
    override val resolvedNamespace get() = target.resolvedNamespace
    override fun getNameIdentifier(): PsiElement = findChildByType(SmithyTypes.TOKEN_INPUT)!!
    override fun resolve() = target.resolve()
}

interface SmithyOperationOutputExt : SmithyOperationPropertyExt, SmithyShapeTarget {
    val target: SmithyShapeTarget
}

abstract class SmithyOperationOutputMixin(node: ASTNode) : SmithyOperationPropertyImpl(node), SmithyOperationOutput {
    override val target: SmithyShapeTarget get() = shapeId ?: shape!!
    override val href get() = target.href
    override val shapeName get() = target.shapeName
    override val declaredNamespace get() = target.declaredNamespace
    override val resolvedNamespace get() = target.resolvedNamespace
    override fun getNameIdentifier(): PsiElement = findChildByType(SmithyTypes.TOKEN_OUTPUT)!!
    override fun resolve() = target.resolve()
}

interface SmithyOutputExt : SmithyContainerShape, SmithyShapeTarget {
    override val href: String get() = super<SmithyContainerShape>.href
    override val resource: SmithyShapeId?
    val operation: SmithyOperation
}

abstract class SmithyOutputMixin(node: ASTNode) : SmithyContainerShapeImpl(node), SmithyOutput {
    private var _syntheticTraits: List<SmithyTraitDefinition>? = null
    private val id = object : SmithySyntheticElement() {
        override fun getParent() = this@SmithyOutputMixin
        override fun getTextOffset() = body.textOffset
        override fun getText() =
            "${operation.name}${(containingFile as? SmithyFile)?.model?.operationOutputSuffix ?: "Output"}"
    }
    override val operation get() = getParentOfType(this, SmithyOperation::class.java)!!
    override val resource get() = resourceReference?.shapeId
    override val declaredNamespace = namespace
    override val resolvedNamespace = namespace
    override val syntheticTraits: List<SmithyTraitDefinition>
        get() = _syntheticTraits ?: mutableListOf<SmithyTraitDefinition>().also {
            getChildOfType(this, SmithyDocumentation::class.java)?.let { docs ->
                it += SmithySyntheticTrait(
                    this,
                    "smithy.api",
                    "documentation",
                    SmithySyntheticValue.String(docs.toDocString())
                )
            }
            it += SmithySyntheticTrait(this, "smithy.api", "output")
            _syntheticTraits = it
        }
    override val typeIdentifier = object : SmithySyntheticElement() {
        override fun getParent() = this@SmithyOutputMixin
        override fun getText() = "structure"
    }

    override fun getNameIdentifier() = id
    override fun resolve() = this
    override fun subtreeChanged() {
        _syntheticTraits = null
    }
}

interface SmithyResourceExt : SmithyShape {
    override val identifiers: List<SmithyResourceIdentifier>
    val create: SmithyShapeId?
    val put: SmithyShapeId?
    val read: SmithyShapeId?
    val update: SmithyShapeId?
    val delete: SmithyShapeId?
    val list: SmithyShapeId?
    val operations: List<SmithyShapeId>
    val collectionOperations: List<SmithyShapeId>
    val resources: List<SmithyShapeId>
}

abstract class SmithyResourceMixin(node: ASTNode) : SmithyShapeImpl(node), SmithyResource {
    override val identifiers: List<SmithyResourceIdentifier>
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceIdentifiers
        }?.identifiers ?: emptyList()
    override val properties: List<SmithyResourceProperty>
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceProperties
        }?.properties ?: emptyList()
    override val create: SmithyShapeId?
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceCreateOperation
        }?.shapeId
    override val put: SmithyShapeId?
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourcePutOperation
        }?.shapeId
    override val read: SmithyShapeId?
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceReadOperation
        }?.shapeId
    override val update: SmithyShapeId?
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceUpdateOperation
        }?.shapeId
    override val delete: SmithyShapeId?
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceDeleteOperation
        }?.shapeId
    override val list: SmithyShapeId?
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceListOperation
        }?.shapeId
    override val operations: List<SmithyShapeId>
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceOperations
        }?.shapes ?: emptyList()
    override val collectionOperations: List<SmithyShapeId>
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceCollectionOperations
        }?.shapes ?: emptyList()
    override val resources: List<SmithyShapeId>
        get() = body.entries.firstNotNullOfOrNull {
            it as? SmithyResourceResources
        }?.shapes ?: emptyList()
}

interface SmithyResourceEntryExt : SmithyElement {
    val enclosingResource: SmithyResource
}

abstract class SmithyResourceEntryMixin(node: ASTNode) : SmithyPsiElement(node), SmithyResourceEntry {
    override val enclosingResource: SmithyResource get() = parent.parent as SmithyResource
}

interface SmithyResourceIdentifierExt : SmithyResourceIdentifierDefinition {
    override val enclosingShape: SmithyResource
}

abstract class SmithyResourceIdentifierMixin(node: ASTNode) : SmithyPsiElement(node), SmithyResourceIdentifier {
    override val enclosingShape: SmithyResource get() = (parent as SmithyResourceIdentifiers).enclosingResource
    override val resolvedTarget: SmithyShapeId get() = declaredTarget
    override fun getName(): String = nameIdentifier.text
    override fun setName(newName: String) = setName<SmithyResourceIdentifier>(this, newName)
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = "$name: ${resolvedTarget.shapeName}"
        override fun getLocationString() = enclosingShape.shapeName
        override fun getIcon(unused: Boolean) = getIcon(0)
    }
}

interface SmithyResourcePropertyExt : SmithyResourcePropertyDefinition {
    override val enclosingShape: SmithyResource
}

abstract class SmithyResourcePropertyMixin(node: ASTNode) : SmithyPsiElement(node), SmithyResourceProperty {
    override val enclosingShape: SmithyResource get() = (parent as SmithyResourceProperties).enclosingResource
    override val resolvedTarget: SmithyShapeId get() = declaredTarget
    override fun getName(): String = nameIdentifier.text
    override fun setName(newName: String) = setName<SmithyResourceProperty>(this, newName)
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = "$name: ${resolvedTarget.shapeName}"
        override fun getLocationString() = enclosingShape.shapeName
        override fun getIcon(unused: Boolean) = getIcon(0)
    }
}

abstract class SmithyServiceMixin(node: ASTNode) : SmithyShapeImpl(node), SmithyService

abstract class SmithySetMixin(node: ASTNode) : SmithyContainerShapeImpl(node), SmithySet {
    override val requiredMembers = setOf("member")
}

interface SmithyShapeExt : SmithyNamedElement, SmithyShapeDefinition, SmithyStatement {
    override val type get() = super.type
    override val declaredTraits: List<SmithyTrait>
    override val mixins: List<SmithyShapeId>
    val model: SmithyModel
    val requiredMembers: Set<String> get() = emptySet()
    val supportedMembers: Set<String>? get() = requiredMembers.takeIf { it.isNotEmpty() }
}

abstract class SmithyShapeMixin(node: ASTNode) : SmithyPsiElement(node), SmithyShape {
    private var _syntheticTraits: List<SmithyTraitDefinition>? = null
    override val typeIdentifier
        get() = nameIdentifier.siblings(forward = false, withSelf = false).first {
            it !is PsiWhiteSpace && it !is PsiComment
        }
    override val namespace get() = model.namespace!!
    override val shapeName: String get() = nameIdentifier.text
    override val shapeId get() = "$namespace#$shapeName"
    override val model get() = (containingFile as? SmithyFile)?.model!!
    override val mixins: List<SmithyShapeId>
        get() = getChildOfType(this, SmithyMixins::class.java)?.shapes ?: emptyList()
    override val declaredMembers get(): List<@JvmWildcard SmithyMemberDefinition> = emptyList<SmithyMemberDefinition>()
    override val declaredTraits: List<SmithyTrait> get() = getChildrenOfTypeAsList(this, SmithyTrait::class.java)
    override val syntheticTraits: List<SmithyTraitDefinition>
        get() = _syntheticTraits ?: (getChildOfType(this, SmithyDocumentation::class.java)?.let {
            listOf(
                SmithySyntheticTrait(
                    this,
                    "smithy.api",
                    "documentation",
                    SmithySyntheticValue.String(it.toDocString())
                )
            )
        } ?: emptyList()).also { _syntheticTraits = it }

    override fun getMember(name: String): SmithyMemberDefinition? = members.find { it.name == name }
    override fun getName() = shapeName
    override fun setName(newName: String) = setName<SmithyShape>(this, newName)
    override fun getNameIdentifier(): PsiElement = getChildOfType(this, SmithyShapeName::class.java)!!
    override fun getTextOffset() = nameIdentifier.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = shapeName
        override fun getLocationString(): String = namespace
        override fun getIcon(unused: Boolean) = getIcon(0)
    }

    override fun subtreeChanged() {
        _syntheticTraits = null
    }
}

interface SmithyShapeIdExt : SmithyCharSequence, SmithyShapeTarget, PsiNamedElement {
    override val reference: SmithyShapeReference
    val enclosingNamespace: String?
}

abstract class SmithyShapeIdMixin(node: ASTNode) : SmithyPrimitiveImpl(node), SmithyShapeId {
    override val reference by lazy { SmithyShapeReference(this) }
    override val shapeName: String get() = id.text
    override val declaredNamespace get() = namespaceId?.id
    override val enclosingNamespace get() = (containingFile as SmithyFile).model?.namespace
    override val resolvedNamespace: String? get() = declaredNamespace ?: getNamespace(this, shapeName)
    override fun asString() = resolvedNamespace?.let { "$it#$shapeName" } ?: shapeName
    override fun getName() = shapeName
    override fun setName(newName: String) = setName<SmithyShapeId>(this, id, newName)
    override fun getTextOffset() = id.textOffset
    override fun resolve() = reference.resolve()
}

interface SmithyMemberTargetExt : SmithyShapeTarget {
    /** The underlying target: a [SmithyShapeId], [SmithyInlineListTarget], or [SmithyInlineMapTarget]. */
    val target: SmithyShapeTarget?
}

abstract class SmithyMemberTargetMixin(node: ASTNode) : SmithyPsiElement(node), SmithyMemberTarget {
    override val target: SmithyShapeTarget? get() = shapeId ?: inlineListTarget ?: inlineMapTarget
    override val shapeName: String get() = target?.shapeName ?: text
    override val declaredNamespace: String? get() = target?.declaredNamespace
    override val resolvedNamespace: String? get() = target?.resolvedNamespace
    override fun resolve(): SmithyShapeDefinition? = target?.resolve()
}

interface SmithyInlineListTargetExt : SmithyShapeTarget {
    val element: SmithyShapeTarget?
}abstract class SmithyInlineListTargetMixin(node: ASTNode) : SmithyPsiElement(node), SmithyInlineListTarget {
    private val enclosingNamespace get() = (containingFile as? SmithyFile)?.model?.namespace
    override val declaredNamespace get() = enclosingNamespace
    override val resolvedNamespace get() = enclosingNamespace
    override val shapeName: String
        get() {
            val target = element
            val token = SmithyInlineTargets.token(
                enclosingNamespace, target?.resolve()?.namespace ?: target?.resolvedNamespace,
                target?.shapeName ?: "Unknown"
            )
            return SmithyInlineTargets.listName(token)
        }
    override fun resolve(): SmithyShapeDefinition? =
        enclosingNamespace?.let { SmithySyntheticShape(this, it, shapeName, "list") }
}

interface SmithyInlineMapTargetExt : SmithyShapeTarget {
    val key: SmithyShapeTarget?
    val value: SmithyShapeTarget?
}

abstract class SmithyInlineMapTargetMixin(node: ASTNode) : SmithyPsiElement(node), SmithyInlineMapTarget {
    private val enclosingNamespace get() = (containingFile as? SmithyFile)?.model?.namespace
    override val declaredNamespace get() = enclosingNamespace
    override val resolvedNamespace get() = enclosingNamespace
    override val shapeName: String
        get() {
            val keyToken = SmithyInlineTargets.token(
                enclosingNamespace, key?.resolve()?.namespace ?: key?.resolvedNamespace, key?.shapeName ?: "Unknown"
            )
            val valueToken = SmithyInlineTargets.token(
                enclosingNamespace, value?.resolve()?.namespace ?: value?.resolvedNamespace,
                value?.shapeName ?: "Unknown"
            )
            return SmithyInlineTargets.mapName(keyToken, valueToken)
        }
    override fun resolve(): SmithyShapeDefinition? =
        enclosingNamespace?.let { SmithySyntheticShape(this, it, shapeName, "map") }
}

private fun parseInnerText(text: String, start: Int = 0, end: Int = text.length - start): String? = buildString {
    var i = start
    val lastIndex = end.takeIf { it >= i } ?: return null
    while (i < lastIndex) {
        val n = i + 1
        val c = text[i]
        if (c == '\r') {
            append('\n').also { i += if (n < lastIndex && text[n] == '\n') 2 else 1 }
        } else if (c != '\\') {
            append(c).also { i++ }
        } else if (i == lastIndex - 1) {
            return null //invalid string (closing quote is escaped)
        } else when (text[n]) {
            '\n' -> i += 2 //escaped new-line expands to nothing
            '\r' -> i += if (n < lastIndex - 1 && text[n + 1] == '\n') 3 else 2 //escaped new-line expands to nothing
            '"' -> append('"').also { i += 2 }
            '\\' -> append('\\').also { i += 2 }
            '/' -> append('/').also { i += 2 }
            'b' -> append('\b').also { i += 2 }
            'f' -> append('\u000c').also { i += 2 }
            'n' -> append('\n').also { i += 2 }
            'r' -> append('\r').also { i += 2 }
            't' -> append('\t').also { i += 2 }
            'u' -> {
                if (n >= lastIndex - 4) return null //invalid string (less than 4 digits in the unicode escape sequence)
                var unicode = 0
                (1..4).forEach {
                    unicode = when (val u = text[n + it]) {
                        in '0'..'9' -> (unicode shl 4) or (u - '0')
                        in 'a'..'f' -> (unicode shl 4) or (10 + (u - 'a'))
                        in 'A'..'F' -> (unicode shl 4) or (10 + (u - 'A'))
                        else -> return null //invalid string (non-digit in unicode escape sequence)
                    }
                }
                append(unicode.toChar()).also { i += 6 }
            }

            else -> return null //invalid string (unknown escape sequence)
        }
    }
}

interface SmithyCharSequence : SmithyPrimitive {
    override val type get() = SmithyValueType.STRING
    val valid: Boolean get() = asString() != null
}

interface SmithyStringExt : SmithyCharSequence {
    override val reference: SmithyShapeReference
}

abstract class SmithyStringMixin(node: ASTNode) : SmithyPrimitiveImpl(node), SmithyString {
    private var parsed = false
    private var value: String? = null
    override fun asString(): String? =
        if (parsed) value else parseInnerText(text, 1).also {
            value = it
            parsed = true
        }

    override val reference by lazy { SmithyShapeReference(this) }

    override fun subtreeChanged() {
        parsed = false
    }
}

interface SmithyTextBlockExt : SmithyCharSequence {
    override val reference: SmithyShapeReference
}

abstract class SmithyTextBlockMixin(node: ASTNode) : SmithyPrimitiveImpl(node), SmithyTextBlock {
    private var parsed = false
    private var value: String? = null
    override fun asString(): String? =
        if (parsed) value else text.let { text ->
            val normalized = if ('\r' in text) text.replace("\r\n", "\n") else text
            if (normalized.length < 7) return@let null
            val lines = normalized.substring(4, normalized.length - 3).split('\n')
            val leadingSpaces = lines.filterIndexed { i, line ->
                i == lines.lastIndex || line.any { it != ' ' }
            }.minOfOrNull { line -> line.indexOfFirst { it != ' ' }.takeIf { it != -1 } ?: line.length } ?: 0
            val trimmed = lines.joinToString("\n") { line ->
                line.let { if (line.length < leadingSpaces) line else line.substring(leadingSpaces) }.trimEnd(' ')
            }
            parseInnerText(trimmed)
        }.also {
            value = it
            parsed = true
        }

    override val reference by lazy { SmithyShapeReference(this) }

    override fun subtreeChanged() {
        parsed = false
    }
}

interface SmithyTaggedStringExt : SmithyCharSequence {
    /** The tag name (without the leading `#` or trailing whitespace), e.g. `re`, `b`, `hex`, `timestamp`. */
    val tag: String
}

abstract class SmithyTaggedStringMixin(node: ASTNode) : SmithyPrimitiveImpl(node), SmithyTaggedString {
    override val tag: String get() = firstChild.text.removePrefix("#").trim()

    //Highlighting only: a tagged literal uses tag-specific escape/encoding rules (e.g. #re takes backslashes
    //literally, #b/#hex decode bytes), so the standard string escape processing in SmithyString/SmithyTextBlock must
    //NOT be applied to its content - doing so would mangle or null out the value (e.g. "\d" is not a valid plain-string
    //escape). We do not decode; asString() returns the raw inner text verbatim, which is the correct value for #re and
    //a safe, non-mangling best effort for the others.
    override fun asString(): String? {
        val raw = (content as? PsiElement)?.text ?: return null
        return when {
            raw.startsWith("\"\"\"") && raw.endsWith("\"\"\"") && raw.length >= 6 -> {
                //Text block: drop the opening """ (and the required leading newline) and the closing """.
                raw.substring(3, raw.length - 3).removePrefix("\r\n").removePrefix("\n")
            }
            raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 -> raw.substring(1, raw.length - 1)
            else -> raw
        }
    }
}

interface SmithyStructureExt : SmithyContainerShape {
    override val resource: SmithyShapeId?
}

abstract class SmithyStructureMixin(node: ASTNode) : SmithyContainerShapeImpl(node), SmithyStructure {
    override val resource get() = resourceReference?.shapeId
}

interface SmithyTraitExt : SmithyTraitDefinition {
    val target: SmithyDefinition?
}

abstract class SmithyTraitMixin(node: ASTNode) : SmithyPsiElement(node), SmithyTrait {
    private var parsed = false
    private var _value: SmithyValueDefinition = SmithySyntheticValue.Object().also { it.scope(this) }
    override val value: SmithyValueDefinition
        get() =
            if (parsed) _value else when (val body = body) {
                null -> SmithySyntheticValue.Object().also { it.scope(this) }
                else -> body.value ?: object : SmithySyntheticElement(), SmithyValueDefinition {
                    override val type = SmithyValueType.OBJECT
                    override val fields = body.entries.associate { it.name to it.value }
                    override fun getParent() = this@SmithyTraitMixin
                }
            }.also {
                _value = it
                parsed = true
            }
    override val shapeName get() = shape.shapeName
    override val declaredNamespace get() = shape.declaredNamespace
    override val resolvedNamespace get() = shape.resolvedNamespace
    override val target: SmithyDefinition?
        get() = when (val parent = parent) {
            is SmithyAppliedTrait -> parent.target
            is SmithyAppliedTraitBody -> (parent.parent as? SmithyAppliedTrait)?.target
            is SmithyDefinition -> parent
            else -> null
        }

    override fun getName() = shapeName
    override fun setName(newName: String) = also { shape.setName(newName) }
    override fun getTextOffset() = shape.textOffset
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText(): String = shapeName
        override fun getLocationString() = parent.let { if (it is SmithyDefinition) it.name else containingFile.name }
        override fun getIcon(unused: Boolean) = getIcon(0)
    }

    override fun subtreeChanged() {
        parsed = false
    }
}

interface SmithyValueExt : SmithyElement, SmithyValueDefinition {
    val reference: SmithyReference
}

abstract class SmithyValueMixin(node: ASTNode) : SmithyPsiElement(node), SmithyValue {
    override val reference by lazy { SmithyShapeReference(this) }
}

private fun <T : SmithyNamedElement> setName(element: T, newName: String?): T {
    return setName(element, element.nameIdentifier, newName)
}

private fun <T : PsiNamedElement> setName(element: T, nameIdentifier: PsiElement, newName: String?): T {
    val textRange = nameIdentifier.textRange
    val document = FileDocumentManager.getInstance().getDocument(element.containingFile.virtualFile)
    document!!.replaceString(textRange.startOffset, textRange.endOffset, newName!!)
    PsiDocumentManager.getInstance(element.project).commitDocument(document)
    return element
}