package app.daffodil.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.daffodil.R
import app.daffodil.ink.Exporter
import app.daffodil.ink.ImageCache
import app.daffodil.ink.InkSurface
import app.daffodil.ink.PdfPages
import app.daffodil.ink.Tool
import app.daffodil.model.DocKind
import app.daffodil.model.DocStore
import app.daffodil.model.Document
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class EditorActivity : AppCompatActivity(), InkSurface.Listener {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var ink: InkSurface
    private lateinit var toolbar: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var hint: TextView
    private lateinit var penBlack: SwatchView
    private lateinit var penRed: SwatchView
    private lateinit var penBlue: SwatchView
    private lateinit var penSize: SizeView
    private lateinit var btnHighlighter: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var btnEraser: ImageView
    private lateinit var btnLasso: ImageView
    private lateinit var btnUndo: ImageView
    private lateinit var btnRedo: ImageView
    private lateinit var prefs: SharedPreferences

    private var doc: Document? = null
    private var pdf: PdfPages? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { saveAsync(false) }
    private var dirty = false
    private var toolbarBottom = false
    private var topInset = 0
    private var bottomInset = 0

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importImage(uri)
    }
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) export(uri, png = false)
    }
    private val exportPng = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) export(uri, png = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        prefs = getSharedPreferences("daffodil", Context.MODE_PRIVATE)

        ink = findViewById(R.id.ink)
        toolbar = findViewById(R.id.toolbar)
        selectionBar = findViewById(R.id.selectionBar)
        hint = findViewById(R.id.hint)
        penBlack = findViewById(R.id.penBlack)
        penRed = findViewById(R.id.penRed)
        penBlue = findViewById(R.id.penBlue)
        penSize = findViewById(R.id.penSize)
        btnHighlighter = findViewById(R.id.btnHighlighter)
        pageLabel = findViewById(R.id.pageLabel)
        btnEraser = findViewById(R.id.btnEraser)
        btnLasso = findViewById(R.id.btnLasso)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)

        goFullscreen()
        setupToolbar()
        setupSelectionBar()
        loadSettings()
        ink.listener = this

        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) { finish(); return }
        io.execute {
            try {
                val d = DocStore.load(this, id)
                val p = if (d.meta.kind == DocKind.PDF) PdfPages(File(d.dir, "source.pdf")) else null
                if (p != null) d.meta.pageCount = p.pageCount
                main.post {
                    if (isDestroyed) { p?.close(); return@post }
                    doc = d; pdf = p
                    ink.paper = d.meta.paper
                    ink.bind(d, p)
                    findViewById<ProgressBar>(R.id.loading).visibility = View.GONE
                    refreshHistory()
                    updatePageLabel()
                }
            } catch (t: Throwable) {
                main.post {
                    Toast.makeText(this, "无法打开：${t.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            topInset = cut.top; bottomInset = cut.bottom
            placeToolbar()
            insets
        }
    }

    private var wDown = 0
    private var wMove = 0
    private var wUp = 0
    private var wSamples = 0

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ink.debugSink != null) when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> wDown++
            android.view.MotionEvent.ACTION_MOVE -> { wMove++; wSamples += ev.historySize + 1 }
            android.view.MotionEvent.ACTION_UP -> wUp++
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun placeToolbar() {
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams
        val m = (6 * resources.displayMetrics.density).toInt()
        if (toolbarBottom) {
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.topMargin = 0; lp.bottomMargin = m + bottomInset
        } else {
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = m + topInset; lp.bottomMargin = 0
        }
        toolbar.layoutParams = lp
        val hp = hint.layoutParams as FrameLayout.LayoutParams
        hp.gravity = (if (toolbarBottom) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        hp.topMargin = if (toolbarBottom) 4 * m + topInset else 0
        hp.bottomMargin = if (toolbarBottom) 0 else 4 * m
        hint.layoutParams = hp
    }

    // ---------------------------------------------------------------- toolbar

    private fun setupToolbar() {
        penBlack.color = InkSurface.INK_BLACK
        penRed.color = InkSurface.INK_RED
        penBlue.color = InkSurface.INK_BLUE
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        for (sw in listOf(penBlack, penRed, penBlue)) {
            sw.setOnClickListener {
                if (ink.tool == Tool.PEN && ink.penColor == sw.color) cycleSize()
                ink.penColor = sw.color
                ink.setTool(Tool.PEN)
                refreshTools()
                saveSettings()
            }
        }
        penSize.setOnClickListener { cycleSize() }
        btnHighlighter.setOnClickListener { ink.setTool(Tool.HIGHLIGHTER); refreshTools() }
        pageLabel.setOnClickListener { askPage() }
        btnEraser.setOnClickListener {
            if (ink.tool == Tool.ERASER) {
                ink.partialEraser = !ink.partialEraser
                showHint(if (ink.partialEraser) "局部擦除" else "整笔擦除")
                saveSettings()
            }
            ink.setTool(Tool.ERASER)
            refreshTools()
        }
        btnLasso.setOnClickListener { ink.setTool(Tool.LASSO); refreshTools() }
        findViewById<View>(R.id.btnImage).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        repeatWhileHeld(btnUndo) { ink.undo() }
        repeatWhileHeld(btnRedo) { ink.redo() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }

        // Tooltips double as hover labels for a stylus.
        findViewById<View>(R.id.btnBack).tooltipText = "返回"
        penBlack.tooltipText = "黑色（再点一次换粗细）"
        penRed.tooltipText = "红色（再点一次换粗细）"
        penBlue.tooltipText = "蓝色（再点一次换粗细）"
        penSize.tooltipText = "粗细"
        btnHighlighter.tooltipText = "荧光笔"
        btnEraser.tooltipText = "橡皮（再点一次切换整笔/局部）"
        btnLasso.tooltipText = "套索"
        findViewById<View>(R.id.btnImage).tooltipText = "插入图片"
        btnUndo.tooltipText = "撤销（按住连续撤销）"
        btnRedo.tooltipText = "重做（按住连续重做）"
        findViewById<View>(R.id.btnMore).tooltipText = "更多"
    }

    /** Tap = once; hold = keep going, faster and faster. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun repeatWhileHeld(v: View, action: () -> Unit) {
        var repeated = false
        val runner = object : Runnable {
            var delay = 220L
            override fun run() {
                repeated = true
                action()
                delay = max(45L, (delay * 0.8f).toLong())
                v.postDelayed(this, delay)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    repeated = false; runner.delay = 220L
                    v.isPressed = true
                    v.postDelayed(runner, 450L)
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(runner); v.isPressed = false
                    if (!repeated) action()
                }
                android.view.MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(runner); v.isPressed = false }
            }
            true
        }
    }

    private fun updatePageLabel() {
        val p = pdf
        if (p == null) { pageLabel.visibility = View.GONE; return }
        val i = ink.currentPage()
        pageLabel.text = "${i + 1} / ${p.pageCount}"
        pageLabel.visibility = View.VISIBLE
    }

    private fun askPage() {
        val p = pdf ?: return
        val d = resources.displayMetrics.density
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "1 – ${p.pageCount}"
            setText((ink.currentPage() + 1).toString())
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(input)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("跳到第几页")
            .setView(box)
            .setPositiveButton("跳转") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                ink.goToPage((n - 1).coerceIn(0, p.pageCount - 1))
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun cycleSize() {
        ink.penSize = (ink.penSize + 1) % 3
        showHint(arrayOf("细", "中", "粗")[ink.penSize])
        refreshTools()
        saveSettings()
    }

    private fun refreshTools() {
        val t = ink.tool
        penBlack.active = t == Tool.PEN && ink.penColor == InkSurface.INK_BLACK
        penRed.active = t == Tool.PEN && ink.penColor == InkSurface.INK_RED
        penBlue.active = t == Tool.PEN && ink.penColor == InkSurface.INK_BLUE
        penBlack.isSelected = penBlack.active
        penRed.isSelected = penRed.active
        penBlue.isSelected = penBlue.active
        penSize.color = ink.penColor
        penSize.level = ink.penSize
        btnHighlighter.isSelected = t == Tool.HIGHLIGHTER
        btnEraser.isSelected = t == Tool.ERASER
        btnEraser.setImageResource(if (ink.partialEraser) R.drawable.ic_eraser_partial else R.drawable.ic_eraser)
        btnLasso.isSelected = t == Tool.LASSO
    }

    private fun refreshHistory() {
        val d = doc
        btnUndo.alpha = if (d?.canUndo == true) 1f else 0.35f
        btnRedo.alpha = if (d?.canRedo == true) 1f else 0.35f
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, 1, 0, if (pdf != null) "适应页宽" else "显示全部内容")
        m.add(0, 2, 1, "导出 PDF")
        if (pdf == null) {
            m.add(0, 3, 2, "导出 PNG 图片")
            val sub = m.addSubMenu(0, 20, 2, "画布背景")
            arrayOf("空白", "点阵", "横线", "方格").forEachIndexed { i, name ->
                sub.add(3, 30 + i, i, name).isChecked = (doc?.meta?.paper ?: 0) == i
            }
            sub.setGroupCheckable(3, true, true)
        }
        m.add(1, 10, 3, "套索操作后切回原来的笔").setCheckable(true).isChecked = ink.autoReturnAfterLasso
        m.add(1, 11, 4, "手指也能书写").setCheckable(true).isChecked = ink.fingerDraws
        m.add(1, 12, 5, "双指轻点撤销 / 三指重做").setCheckable(true).isChecked = ink.twoFingerTapUndo
        m.add(1, 13, 6, "工具栏放在底部").setCheckable(true).isChecked = toolbarBottom
        m.add(1, 14, 7, "显示触控调试信息").setCheckable(true).isChecked = ink.debugSink != null
        m.add(2, 15, 8, "实验：不合并触控事件").setCheckable(true).isChecked = ink.unbufferedInput
        m.add(2, 16, 9, "实验：前缓冲低延迟渲染").setCheckable(true).isChecked = ink.useFrontBuffer
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> ink.resetView()
                2 -> exportPdf.launch(safeName() + ".pdf")
                3 -> exportPng.launch(safeName() + ".png")
                10 -> { ink.autoReturnAfterLasso = !ink.autoReturnAfterLasso; saveSettings() }
                11 -> { ink.fingerDraws = !ink.fingerDraws; saveSettings(); showHint(if (ink.fingerDraws) "单指书写，双指移动" else "单指移动画布") }
                12 -> { ink.twoFingerTapUndo = !ink.twoFingerTapUndo; saveSettings() }
                13 -> { toolbarBottom = !toolbarBottom; placeToolbar(); saveSettings() }
                14 -> toggleDebug()
                15 -> { ink.unbufferedInput = !ink.unbufferedInput; saveSettings() }
                16 -> { ink.useFrontBuffer = !ink.useFrontBuffer; saveSettings() }
                in 30..33 -> doc?.let { d ->
                    d.meta.paper = item.itemId - 30
                    ink.paper = d.meta.paper
                    onContentChanged()
                }
            }
            true
        }
        pm.show()
    }

    private fun toggleDebug() {
        val tv = findViewById<TextView>(R.id.debug)
        if (ink.debugSink == null) {
            tv.visibility = View.VISIBLE
            tv.text = "用笔写几笔，这里会显示原始触控事件"
            ink.windowStats = { "DOWN $wDown  MOVE $wMove(采样 $wSamples)  UP $wUp" }
            ink.debugSink = { tv.text = it }
        } else {
            ink.debugSink = null
            tv.visibility = View.GONE
        }
    }

    private fun safeName() = (doc?.meta?.title ?: "Daffodil").replace(Regex("[\\\\/:*?\"<>|]"), "_")

    // ---------------------------------------------------------------- selection bar

    private fun setupSelectionBar() {
        val black = findViewById<SwatchView>(R.id.selBlack).apply { color = InkSurface.INK_BLACK }
        val red = findViewById<SwatchView>(R.id.selRed).apply { color = InkSurface.INK_RED }
        val blue = findViewById<SwatchView>(R.id.selBlue).apply { color = InkSurface.INK_BLUE }
        findViewById<View>(R.id.selDelete).setOnClickListener { ink.deleteSelection() }
        findViewById<View>(R.id.selCopy).setOnClickListener { ink.duplicateSelection() }
        black.setOnClickListener { ink.recolorSelection(InkSurface.INK_BLACK) }
        red.setOnClickListener { ink.recolorSelection(InkSurface.INK_RED) }
        blue.setOnClickListener { ink.recolorSelection(InkSurface.INK_BLUE) }
        findViewById<View>(R.id.selDelete).tooltipText = "删除"
        findViewById<View>(R.id.selCopy).tooltipText = "复制一份"
    }

    private fun placeSelectionBar() {
        val sel = ink.selection
        val r = ink.selectionScreenRect()
        if (sel == null || r == null) { selectionBar.visibility = View.GONE; return }
        val showColors = sel.hasStrokes
        for (id in intArrayOf(R.id.selDivider, R.id.selBlack, R.id.selRed, R.id.selBlue)) {
            findViewById<View>(id).visibility = if (showColors) View.VISIBLE else View.GONE
        }
        if (selectionBar.visibility != View.VISIBLE) selectionBar.visibility = View.VISIBLE
        selectionBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bw = selectionBar.measuredWidth.toFloat()
        val bh = selectionBar.measuredHeight.toFloat()
        val gap = 10 * resources.displayMetrics.density
        val rootW = ink.width.toFloat(); val rootH = ink.height.toFloat()
        val tbTop = toolbar.top.toFloat(); val tbBottom = toolbar.bottom.toFloat()
        var y = r.top - bh - gap
        val blocked = { yy: Float -> yy < tbBottom + 4 && yy + bh > tbTop - 4 }
        if (y < 0 || blocked(y)) y = r.bottom + gap
        if (y + bh > rootH || blocked(y)) y = max(tbBottom + gap, min(r.top + gap, rootH - bh - gap))
        val x = (r.centerX() - bw / 2).coerceIn(gap, max(gap, rootW - bw - gap))
        selectionBar.translationX = x
        selectionBar.translationY = y.coerceIn(0f, max(0f, rootH - bh))
    }

    // ---------------------------------------------------------------- InkSurface.Listener

    override fun onToolChanged() = refreshTools()
    override fun onSelectionChanged() = placeSelectionBar()
    override fun onViewportChanged() {
        if (ink.selection != null) placeSelectionBar()
        updatePageLabel()
    }
    override fun onHistoryChanged() = refreshHistory()
    override fun onHint(text: String) = showHint(text)

    override fun onContentChanged() {
        dirty = true
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        hint.animate().cancel()
        hint.alpha = 0f
        hint.animate().alpha(1f).setDuration(120).withEndAction {
            hint.animate().alpha(0f).setStartDelay(750).setDuration(260).withEndAction { hint.visibility = View.GONE }
        }
    }

    // ---------------------------------------------------------------- settings

    private fun loadSettings() {
        ink.penColor = prefs.getInt("penColor", InkSurface.INK_BLACK)
        ink.penSize = prefs.getInt("penSize", 1)
        ink.partialEraser = prefs.getBoolean("partialEraser", false)
        ink.autoReturnAfterLasso = prefs.getBoolean("autoReturn", true)
        ink.fingerDraws = prefs.getBoolean("fingerDraws", false)
        ink.twoFingerTapUndo = prefs.getBoolean("tapUndo", true)
        ink.unbufferedInput = prefs.getBoolean("unbuffered2", false)
        ink.useFrontBuffer = prefs.getBoolean("frontBuffer", true)
        toolbarBottom = prefs.getBoolean("toolbarBottom", false)
        placeToolbar()
        refreshTools()
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("penColor", ink.penColor).putInt("penSize", ink.penSize)
            .putBoolean("partialEraser", ink.partialEraser)
            .putBoolean("autoReturn", ink.autoReturnAfterLasso)
            .putBoolean("fingerDraws", ink.fingerDraws)
            .putBoolean("tapUndo", ink.twoFingerTapUndo)
            .putBoolean("unbuffered2", ink.unbufferedInput)
            .putBoolean("frontBuffer", ink.useFrontBuffer)
            .putBoolean("toolbarBottom", toolbarBottom)
            .apply()
    }

    // ---------------------------------------------------------------- saving

    private fun saveAsync(final: Boolean) {
        val d = doc ?: return
        ink.flushPendingStroke()
        ink.saveViewport()
        if (!dirty && !final) return
        val wasDirty = dirty
        dirty = false
        if (wasDirty) d.meta.modified = System.currentTimeMillis()
        val snap = DocStore.snapshot(d)
        io.execute {
            runCatching { DocStore.save(snap, cleanup = final) }
            if (final || wasDirty) runCatching { Exporter.writeThumbnail(snap) }
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(saveRunnable)
        saveAsync(isFinishing)
    }

    override fun onDestroy() {
        super.onDestroy()
        ink.unbind()
        val p = pdf
        io.execute { p?.close() }
        io.shutdown()
    }

    // ---------------------------------------------------------------- images & export

    private fun importImage(uri: Uri) {
        val d = doc ?: return
        io.execute {
            val result = runCatching { copyImage(uri, d.dir) }.getOrNull()
            main.post {
                if (result == null) Toast.makeText(this, "无法读取这张图片", Toast.LENGTH_SHORT).show()
                else ink.insertImage(result.first, result.second, result.third)
            }
        }
    }

    /** Copies the picture into the note folder, applying EXIF rotation and capping its size. */
    private fun copyImage(uri: Uri, dir: File): Triple<String, Int, Int> {
        val tmp = File(cacheDir, "import_" + UUID.randomUUID())
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        try {
            var bmp = ImageCache.decode(tmp, ImageCache.MAX_DIM) ?: error("decode")
            val orientation = runCatching {
                ExifInterface(tmp.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val png = bmp.hasAlpha()
            val name = "img_" + UUID.randomUUID().toString().take(12) + if (png) ".png" else ".jpg"
            FileOutputStream(File(dir, name)).use {
                bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
            }
            return Triple(name, bmp.width, bmp.height)
        } finally {
            tmp.delete()
        }
    }

    private fun export(uri: Uri, png: Boolean) {
        val d = doc ?: return
        ink.flushPendingStroke()
        val snap = DocStore.snapshot(d)
        showHint("正在导出…")
        io.execute {
            val ok = runCatching {
                contentResolver.openOutputStream(uri).use { out ->
                    requireNotNull(out)
                    if (png) Exporter.exportPng(snap, out) else Exporter.exportPdf(snap, out)
                }
            }.isSuccess
            main.post { showHint(if (ok) "已导出" else "导出失败") }
        }
    }
}
