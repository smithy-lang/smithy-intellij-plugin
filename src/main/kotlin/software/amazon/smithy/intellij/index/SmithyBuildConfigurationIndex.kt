package software.amazon.smithy.intellij.index

import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.SingleEntryFileBasedIndexExtension
import com.intellij.util.indexing.SingleEntryIndexer
import com.intellij.util.io.DataExternalizer
import software.amazon.smithy.intellij.SmithyBuildConfiguration
import software.amazon.smithy.intellij.SmithyFile
import software.amazon.smithy.intellij.SmithyJson
import software.amazon.smithy.intellij.SmithyModule
import java.io.DataInput
import java.io.DataOutput

/**
 * A [SingleEntryFileBasedIndexExtension] which caches every [SmithyBuildConfiguration] in the project.
 *
 * @author Ian Caffey
 * @since 1.0
 * @see SmithyFile.buildConfig
 * @see SmithyModule.findBuildConfig
 */
class SmithyBuildConfigurationIndex : SingleEntryFileBasedIndexExtension<SmithyBuildConfiguration>() {
    companion object {
        val NAME = ID.create<Int, SmithyBuildConfiguration>("smithy.build-config")
        fun getConfig(file: VirtualFile, project: Project): SmithyBuildConfiguration? {
            if (DumbService.isDumb(project)) return null
            return FileBasedIndex.getInstance().getSingleEntryIndexData(NAME, file, project)
        }
    }

    override fun getName() = NAME
    override fun getIndexer() = object : SingleEntryIndexer<SmithyBuildConfiguration>(false) {
        override fun computeValue(inputData: FileContent) =
            try {
                SmithyJson.readValue<SmithyBuildConfiguration>(inputData.content)
            } catch (e: Exception) {
                null
            }
    }

    override fun getValueExternalizer(): DataExternalizer<SmithyBuildConfiguration> =
        object : DataExternalizer<SmithyBuildConfiguration> {
            override fun save(out: DataOutput, config: SmithyBuildConfiguration) {
                val bytes = SmithyJson.writeValueAsBytes(config)
                // First write the size so #read knows how many bytes it's reading
                out.writeInt(bytes.size)
                out.write(bytes)
            }

            override fun read(`in`: DataInput): SmithyBuildConfiguration {
                // First 4 bytes are the size of the value, written by #save
                val bytes = ByteArray(`in`.readInt())
                `in`.readFully(bytes)
                return SmithyJson.readValue<SmithyBuildConfiguration>(bytes)
            }
        }

    override fun getVersion() = 5
    override fun getInputFilter() = FileBasedIndex.InputFilter { it.name == "smithy-build.json" }
    override fun dependsOnFileContent() = true
    override fun traceKeyHashToVirtualFileMapping() = true
}