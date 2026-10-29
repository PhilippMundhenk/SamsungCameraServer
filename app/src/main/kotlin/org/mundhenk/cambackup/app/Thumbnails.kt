package org.mundhenk.cambackup.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Small previews for the transfer list, decoded off the main thread and cached. Anything the
 * platform cannot decode (RAW, video, a file that is gone) falls back to the generic icon.
 */
object Thumbnails {
    private const val SIZE_PX = 160
    private val cache = LruCache<String, Bitmap>(64)
    private val misses = java.util.Collections.synchronizedSet(HashSet<String>())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "cambackup-thumbs").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Shows the cached preview at once, otherwise the icon and a preview as soon as it is decoded. */
    fun show(view: ImageView, t: Transfer, open: () -> java.io.InputStream?) {
        view.tag = t.key
        val cached = cache.get(t.key)
        if (cached != null) { view.setImageBitmap(cached); return }
        view.setImageResource(R.drawable.ic_file)
        if (!Uploader.mimeFor(t.name).startsWith("image/") || t.key in misses) return
        executor.execute {
            val bmp = runCatching { decode(open) }.getOrNull()
            if (bmp == null) misses.add(t.key) else cache.put(t.key, bmp)
            main.post { if (view.tag == t.key && bmp != null) view.setImageBitmap(bmp) }
        }
    }

    /** Decodes downscaled to about [SIZE_PX]; two passes (bounds, then pixels), so two opens. */
    internal fun decode(open: () -> java.io.InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val first = open() ?: return null
        first.use { BitmapFactory.decodeStream(it, null, bounds) }       // returns null by design in bounds-only mode
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE_PX && bounds.outHeight / (sample * 2) >= SIZE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Tests and "Clear list" drop the cache so a new file with an old name is decoded again. */
    fun clear() { cache.evictAll(); misses.clear() }
}
