package app.daffodil.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.daffodil.R
import app.daffodil.model.DocKind
import app.daffodil.model.DocMeta
import app.daffodil.model.DocStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private val adapter = DocAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)

        val span = max(2, (resources.configuration.screenWidthDp / 190))
        list.layoutManager = GridLayoutManager(this, span)
        list.adapter = adapter

        findViewById<View>(R.id.btnNewCanvas).setOnClickListener {
            val title = "画布 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date())
            val meta = DocStore.createCanvas(this, title)
            open(meta)
        }
        findViewById<View>(R.id.btnImportPdf).setOnClickListener { openPdf.launch(arrayOf("application/pdf")) }

        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    /** "Open with Daffodil" from a file manager. */
    private fun handleViewIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) {
            val uri = i.data ?: return
            i.action = null
            importPdf(uri)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Thumbnails are written in the background when a note closes; pick them up.
        main.postDelayed({ if (!isFinishing) refresh() }, 900)
    }

    private fun refresh() {
        io.execute {
            val docs = DocStore.list(this)
            main.post {
                adapter.submit(docs)
                empty.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun open(meta: DocMeta) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, meta.id))
    }

    private fun importPdf(uri: Uri) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        io.execute {
            val meta = runCatching { DocStore.importPdf(this, uri) }.getOrNull()
            main.post {
                if (meta == null) Toast.makeText(this, "无法打开这个 PDF", Toast.LENGTH_LONG).show()
                else open(meta)
            }
        }
    }

    private fun showItemMenu(anchor: View, meta: DocMeta) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "重命名")
        pm.menu.add(0, 2, 1, "删除")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> rename(meta)
                2 -> confirmDelete(meta)
            }
            true
        }
        pm.show()
    }

    private fun rename(meta: DocMeta) {
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(meta.title); setSelection(text.length); setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名")
            .setView(box)
            .setPositiveButton("好") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { DocStore.rename(this, meta, t); refresh() }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(meta: DocMeta) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除「${meta.title}」？")
            .setMessage("删除后无法恢复。")
            .setPositiveButton("删除") { _, _ -> DocStore.delete(this, meta.id); refresh() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- adapter

    private inner class DocAdapter : RecyclerView.Adapter<DocHolder>() {
        private var items: List<DocMeta> = emptyList()

        fun submit(docs: List<DocMeta>) { items = docs; notifyDataSetChanged() }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            DocHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_doc, parent, false))

        override fun onBindViewHolder(h: DocHolder, position: Int) {
            val m = items[position]
            h.title.text = m.title
            val time = DateUtils.getRelativeTimeSpanString(m.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.subtitle.text = if (m.kind == DocKind.PDF && m.pageCount > 0) "$time · ${m.pageCount} 页" else time
            h.badge.visibility = if (m.kind == DocKind.PDF) View.VISIBLE else View.GONE
            h.bind(m)
            h.itemView.setOnClickListener { open(m) }
            h.itemView.setOnLongClickListener { showItemMenu(h.thumb, m); true }
        }
    }

    private inner class DocHolder(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val badge: TextView = v.findViewById(R.id.badge)
        private var boundId: String? = null

        fun bind(m: DocMeta) {
            boundId = m.id
            thumb.setImageDrawable(null)
            val f = DocStore.thumbFile(this@MainActivity, m.id)
            val stamp = f.lastModified()
            io.execute {
                val bmp: Bitmap? = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
                main.post { if (boundId == m.id && f.lastModified() == stamp) thumb.setImageBitmap(bmp) }
            }
        }
    }
}
