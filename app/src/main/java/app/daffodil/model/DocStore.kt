package app.daffodil.model

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Everything is stored locally under filesDir/docs/<id>/. No network, no accounts. */
object DocStore {
    private const val MAGIC = 0x44414646 // "DAFF"
    private const val VERSION = 1

    fun root(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id)

    fun list(ctx: Context): List<DocMeta> =
        root(ctx).listFiles()?.mapNotNull { d -> readMeta(d) }?.sortedByDescending { it.modified } ?: emptyList()

    fun createCanvas(ctx: Context, title: String): DocMeta {
        val now = System.currentTimeMillis()
        val meta = DocMeta(UUID.randomUUID().toString(), title, DocKind.CANVAS, now, now)
        val d = dir(ctx, meta.id).apply { mkdirs() }
        writeMeta(d, meta)
        return meta
    }

    /** Copies the PDF into app storage so the note keeps working if the original moves. */
    fun importPdf(ctx: Context, uri: Uri): DocMeta {
        val now = System.currentTimeMillis()
        val name = displayName(ctx, uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "PDF"
        val meta = DocMeta(UUID.randomUUID().toString(), name, DocKind.PDF, now, now)
        val d = dir(ctx, meta.id).apply { mkdirs() }
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open PDF" }
            FileOutputStream(File(d, "source.pdf")).use { input.copyTo(it) }
        }
        writeMeta(d, meta)
        return meta
    }

    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun delete(ctx: Context, id: String) { dir(ctx, id).deleteRecursively() }

    fun rename(ctx: Context, meta: DocMeta, title: String) {
        meta.title = title
        writeMeta(dir(ctx, meta.id), meta)
    }

    fun thumbFile(ctx: Context, id: String) = File(dir(ctx, id), "thumb.png")

    // --- meta -------------------------------------------------------------------------------

    fun readMeta(d: File): DocMeta? = runCatching {
        val j = JSONObject(File(d, "meta.json").readText())
        DocMeta(
            id = j.getString("id"),
            title = j.optString("title", "未命名"),
            kind = DocKind.valueOf(j.optString("kind", "CANVAS")),
            created = j.optLong("created"),
            modified = j.optLong("modified"),
            pageCount = j.optInt("pages", 0),
            viewCx = j.optDouble("cx", Double.NaN).toFloat(),
            viewCy = j.optDouble("cy", Double.NaN).toFloat(),
            viewZoom = j.optDouble("zoom", Double.NaN).toFloat(),
        )
    }.getOrNull()

    fun writeMeta(d: File, m: DocMeta) {
        val j = JSONObject()
            .put("id", m.id).put("title", m.title).put("kind", m.kind.name)
            .put("created", m.created).put("modified", m.modified).put("pages", m.pageCount)
        if (!m.viewCx.isNaN()) j.put("cx", m.viewCx.toDouble()).put("cy", m.viewCy.toDouble()).put("zoom", m.viewZoom.toDouble())
        atomicWrite(File(d, "meta.json")) { it.write(j.toString().toByteArray()) }
    }

    // --- ink --------------------------------------------------------------------------------

    fun load(ctx: Context, id: String): Document {
        val d = dir(ctx, id)
        val meta = readMeta(d) ?: error("Missing note")
        val doc = Document(d, meta)
        val f = File(d, "ink.bin")
        if (f.exists()) {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { inp ->
                require(inp.readInt() == MAGIC) { "Bad file" }
                inp.readInt() // version
                doc.nextId = inp.readLong()
                val ns = inp.readInt()
                repeat(ns) {
                    val sid = inp.readLong()
                    val color = inp.readInt()
                    val n = inp.readInt()
                    val xs = FloatArray(n); val ys = FloatArray(n); val rs = FloatArray(n)
                    for (i in 0 until n) { xs[i] = inp.readFloat(); ys[i] = inp.readFloat(); rs[i] = inp.readFloat() }
                    doc.strokes.add(Stroke(sid, color, xs, ys, rs))
                }
                val ni = inp.readInt()
                repeat(ni) {
                    val iid = inp.readLong()
                    val file = inp.readUTF()
                    val r = RectF(inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat())
                    doc.images.add(ImageItem(iid, file, r))
                }
            }
        }
        return doc
    }

    /** Snapshot of the document contents, safe to write from a background thread. */
    class Snapshot(val dir: File, val meta: DocMeta, val nextId: Long, val strokes: List<Stroke>, val images: List<ImageItem>)

    fun snapshot(doc: Document) = Snapshot(doc.dir, doc.meta, doc.nextId, ArrayList(doc.strokes), ArrayList(doc.images))

    fun save(s: Snapshot, cleanup: Boolean = false) {
        atomicWrite(File(s.dir, "ink.bin")) { os ->
            val out = DataOutputStream(BufferedOutputStream(os, 1 shl 16))
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(s.nextId)
            out.writeInt(s.strokes.size)
            for (st in s.strokes) {
                out.writeLong(st.id); out.writeInt(st.color); out.writeInt(st.size)
                for (i in 0 until st.size) { out.writeFloat(st.xs[i]); out.writeFloat(st.ys[i]); out.writeFloat(st.rs[i]) }
            }
            out.writeInt(s.images.size)
            for (im in s.images) {
                out.writeLong(im.id); out.writeUTF(im.file)
                out.writeFloat(im.rect.left); out.writeFloat(im.rect.top); out.writeFloat(im.rect.right); out.writeFloat(im.rect.bottom)
            }
            out.flush()
        }
        writeMeta(s.dir, s.meta)
        // On close, drop image files no longer referenced (undo history lives in memory only).
        if (!cleanup) return
        val used = s.images.mapTo(HashSet()) { it.file }
        s.dir.listFiles()?.forEach { f -> if (f.name.startsWith("img_") && f.name !in used) f.delete() }
    }

    private fun atomicWrite(target: File, block: (FileOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { fos -> block(fos); fos.fd.sync() }
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
}
