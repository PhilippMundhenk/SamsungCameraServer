package org.mundhenk.cambackup.server

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Small previews for the status page, rendered once per file into `<data>/thumbs/<date>/<name>.jpg`.
 * JPEGs are read directly (subsampled, so a 20 MB camera JPEG does not need 20 MB of pixels);
 * a RAW file uses the JPEG the pipeline rendered next to it, if there is one; everything else
 * has no preview.
 */
class Thumbnails(private val dir: File, private val log: (String) -> Unit = {}) {
    /** The cached thumbnail for [entry], rendering it on first use; null when there is nothing to show. */
    fun thumbnail(entry: Ledger.Entry): File? {
        val cached = File(File(dir, entry.date), entry.name + ".jpg")
        if (cached.isFile) return cached
        val source = source(File(entry.path)) ?: return null
        val image = try { read(source) } catch (e: Exception) { log("No preview for ${entry.name}: $e"); null } ?: return null
        cached.parentFile?.mkdirs()
        val tmp = File(cached.path + ".part")
        return if (ImageIO.write(scale(image), "jpg", tmp) && tmp.renameTo(cached)) cached else { tmp.delete(); null }
    }

    /** The file to decode: the JPEG itself, or the JPEG rendered next to a RAW ("<name>.jpg"). */
    private fun source(file: File): File? {
        val lower = file.name.lowercase()
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return file.takeIf { it.isFile }
        val sibling = File(file.parentFile, file.name + ".jpg")
        return sibling.takeIf { it.isFile }
    }

    private fun read(file: File): BufferedImage? {
        ImageIO.createImageInputStream(file).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val w = reader.getWidth(0); val h = reader.getHeight(0)
                var sub = 1
                while (w / (sub * 2) >= SIZE && h / (sub * 2) >= SIZE) sub *= 2
                val params = reader.defaultReadParam.apply { setSourceSubsampling(sub, sub, 0, 0) }
                return reader.read(0, params)
            } finally { reader.dispose() }
        }
    }

    private fun scale(src: BufferedImage): BufferedImage {
        val factor = minOf(SIZE.toDouble() / src.width, SIZE.toDouble() / src.height, 1.0)
        val w = maxOf(1, (src.width * factor).toInt()); val h = maxOf(1, (src.height * factor).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    companion object { const val SIZE = 160 }
}
