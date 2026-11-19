package app.daffodil.ink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/** Decodes imported pictures off the UI thread, downsampled to a sane texture size. */
class ImageCache(private val dir: File, private val onLoaded: () -> Unit) {
    private val bitmaps = HashMap<String, Bitmap>()
    private val loading = HashSet<String>()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun get(file: String): Bitmap? {
        bitmaps[file]?.let { return it }
        if (loading.add(file)) {
            exec.execute {
                val bmp = decode(File(dir, file), MAX_DIM)
                bmp?.prepareToDraw()
                main.post {
                    loading.remove(file)
                    if (bmp != null) { bitmaps[file] = bmp; onLoaded() }
                }
            }
        }
        return null
    }

    fun shutdown() { exec.shutdownNow() }

    companion object {
        const val MAX_DIM = 2560

        fun decode(f: File, maxDim: Int): Bitmap? {
            if (!f.exists()) return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            if (o.outWidth <= 0) return null
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > maxDim) sample *= 2
            return runCatching {
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }

        fun size(f: File): Pair<Int, Int>? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            return if (o.outWidth > 0) o.outWidth to o.outHeight else null
        }
    }
}
