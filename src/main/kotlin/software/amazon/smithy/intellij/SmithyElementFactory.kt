package software.amazon.smithy.intellij

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTreeUtil.getChildOfType
import software.amazon.smithy.intellij.SmithyModule.defaultNamespace
import software.amazon.smithy.intellij.psi.SmithyElement
import software.amazon.smithy.intellij.psi.SmithyControl
import software.amazon.smithy.intellij.psi.SmithyContainerMember
import software.amazon.smithy.intellij.psi.SmithyImport
import software.amazon.smithy.intellij.psi.SmithyMemberTarget
import software.amazon.smithy.intellij.psi.SmithyMemberIndex
import software.amazon.smithy.intellij.psi.SmithyNamespace
import software.amazon.smithy.intellij.psi.SmithyShape
import software.amazon.smithy.intellij.psi.SmithyStructure
import software.amazon.smithy.intellij.psi.SmithyTaggedString

/**
 * A utility class providing methods to create [SmithyElement].
 *
 * @author Ian Caffey
 * @since 1.0
 */
object SmithyElementFactory {
    fun addImport(file: SmithyFile, namespace: String, shapeName: String) {
        val model = file.model!!
        val imports = PsiTreeUtil.getChildrenOfTypeAsList(model, SmithyImport::class.java)
        if (imports.isNotEmpty()) {
            if (imports.any { shapeName == it.shapeId.shapeName && namespace == it.shapeId.declaredNamespace }) return
            val newImport = createImport(file.project, namespace, shapeName)
            val sortedImports = imports.toMutableList().plus(newImport).sortedWith(
                compareBy<SmithyImport> { it.shapeId.declaredNamespace }.thenBy { it.shapeId.shapeName }
            )
            val insertIndex = sortedImports.indexOf(newImport)
            if (insertIndex == 0) {
                model.addBefore(newImport, sortedImports[1])
            } else {
                model.addAfter(newImport, sortedImports[insertIndex - 1])
            }
        } else {
            model.addAfter(
                createImport(file.project, namespace, shapeName),
                getChildOfType(model, SmithyNamespace::class.java) ?: model.add(
                    createNamespace(file.project, defaultNamespace(file))
                )
            )
        }
    }

    fun createImport(project: Project, namespace: String?, shapeName: String): SmithyImport {
        val file = createFile(
            project, """
            namespace smithy.tmp
            
            use ${if (namespace != null) "$namespace#$shapeName" else shapeName}
        """.trimIndent()
        )
        return file.model!!.imports.first()
    }

    fun createNamespace(project: Project, namespace: String): SmithyNamespace {
        val file = createFile(project, "namespace $namespace")
        return getChildOfType(file.model!!, SmithyNamespace::class.java)!!
    }

    fun createShapeId(project: Project, namespace: String?, shapeName: String) =
        createImport(project, namespace, shapeName).shapeId

    fun createControl(project: Project, key: String, value: String): SmithyControl {
        val file = createFile(project, "\$$key: $value")
        return file.model!!.control.first()
    }

    fun createTaggedString(project: Project, tag: String, content: String): SmithyTaggedString {
        val file = createFile(project, "metadata tmp = #$tag \"$content\"")
        return file.model!!.metadata.first().value as SmithyTaggedString
    }

    fun createMemberTarget(project: Project, targetText: String): SmithyMemberTarget {
        val file = createFile(project, "namespace tmp\n\nstructure Tmp {\n    m: $targetText\n}")
        val structure = file.model!!.shapes.first() as SmithyStructure
        val member = structure.body.members.first() as SmithyContainerMember
        return PsiTreeUtil.getChildOfType(member, SmithyMemberTarget::class.java)!!
    }

    fun createMemberIndex(project: Project, index: Int): SmithyMemberIndex {
        val file = createFile(project, "namespace tmp\n\nstructure Tmp {\n    $index. m: Unit\n}")
        val structure = file.model!!.shapes.first() as SmithyStructure
        val member = structure.body.members.first() as SmithyContainerMember
        return PsiTreeUtil.getChildOfType(member, SmithyMemberIndex::class.java)!!
    }

    fun createShape(project: Project, shapeDeclaration: String): SmithyShape {
        val file = createFile(project, "namespace tmp\n\n$shapeDeclaration")
        return file.model!!.shapes.first()
    }

    fun createFile(project: Project, content: String) =
        PsiFileFactory.getInstance(project).createFileFromText("tmp.smithy", SmithyFileType, content) as SmithyFile
}