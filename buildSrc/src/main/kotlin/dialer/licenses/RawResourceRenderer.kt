package dialer.licenses

import java.io.ByteArrayOutputStream

/**
 * Renders the pair of raw resources com.android.dialer.about.Licenses reads: one blob holding
 * every license text back to back, and a metadata index of "byteOffset:byteLength Library name"
 * lines pointing into it. Each text is preceded by a "\n\nName:\n\n" header that the offset skips,
 * so the blob stays readable on its own, exactly as the checked-in AOSP resources were.
 */
internal object RawResourceRenderer {

    fun render(blocks: List<NoticeBlock>): RenderedResources {
        val blob = ByteArrayOutputStream()
        val metadata = StringBuilder()

        for (block in blocks) {
            val name = block.heading
            require(name.isNotBlank() && '\n' !in name) {
                "License name must be a non-blank single line, was: '$name'"
            }

            blob.writeText("\n\n$name:\n\n")

            val offset = blob.size()
            val text = "${block.body.trimEnd()}\n".toByteArray(Charsets.UTF_8)
            blob.write(text)

            metadata.append(offset).append(':').append(text.size).append(' ').append(name)
                .append('\n')
        }

        return RenderedResources(
            licenses = blob.toByteArray(),
            metadata = metadata.toString(),
        )
    }

    private fun ByteArrayOutputStream.writeText(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }
}

internal data class RenderedResources(
    val licenses: ByteArray,
    val metadata: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenderedResources) return false

        return licenses.contentEquals(other.licenses) && metadata == other.metadata
    }

    override fun hashCode(): Int {
        return 31 * licenses.contentHashCode() + metadata.hashCode()
    }
}
