package com.universal3d.viewer.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.universal3d.viewer.R
import com.universal3d.viewer.app.ModelLoader
import com.universal3d.viewer.app.RecentFiles
import com.universal3d.viewer.app.ViewerApp
import com.universal3d.viewer.io.FormatRegistry
import com.universal3d.viewer.model.Bounds
import com.universal3d.viewer.model.Model
import com.universal3d.viewer.model.ModelUnits
import com.universal3d.viewer.model.Node
import com.universal3d.viewer.render.*
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : Activity() {
    lateinit var glView: GlView; private set
    lateinit var loader: ModelLoader; private set
    private lateinit var recent: RecentFiles
    private lateinit var panel: SidePanel
    private var current: ModelLoader.LoadResult? = null
    private var pendingUri: Uri? = null
    var fileUnits: ModelUnits = ModelUnits.MILLIMETER

    // views
    private lateinit var title: TextView; private lateinit var subtitle: TextView
    private lateinit var emptyState: View; private lateinit var loadingOverlay: View
    private lateinit var sidePanel: View; private lateinit var bottomStack: View; private lateinit var rail: View; private lateinit var topBar: View
    private lateinit var measureHud: View; private lateinit var sectionHud: View
    private lateinit var measureValue: TextView; private lateinit var measureDelta: TextView
    private lateinit var triadLabels: Array<TextView>
    private val modeButtons = HashMap<ViewMode, Button>()

    // measurement
    private var measureMode = false
    private val picks = ArrayList<PickResult>()
    private val pickExecutor = Executors.newSingleThreadExecutor()

    fun currentModel(): Model? = current?.model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ViewerApp
        loader = app.loader; recent = app.recent
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN }
        setContentView(R.layout.activity_main)
        glView = findViewById(R.id.glView)
        title = findViewById(R.id.title); subtitle = findViewById(R.id.subtitle)
        emptyState = findViewById(R.id.emptyState); loadingOverlay = findViewById(R.id.loadingOverlay)
        sidePanel = findViewById(R.id.sidePanel); bottomStack = findViewById(R.id.bottomStack); rail = findViewById(R.id.rail); topBar = findViewById(R.id.topBar)
        measureHud = findViewById(R.id.measureHud); sectionHud = findViewById(R.id.sectionHud)
        measureValue = findViewById(R.id.measureValue); measureDelta = findViewById(R.id.measureDelta)
        triadLabels = arrayOf(findViewById(R.id.triadX), findViewById(R.id.triadY), findViewById(R.id.triadZ))
        loadSettings()
        panel = SidePanel(this)

        // status bar inset for the top bar
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            val top: Int; val bottom: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else @Suppress("DEPRECATION") { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom }
            val newTop = top + Dialogs.dp(this, 6f); val newBottom = bottom + Dialogs.dp(this, 10f)
            if (topBar.paddingTop != newTop || bottomStack.paddingBottom != newBottom) {
                topBar.setPadding(topBar.paddingLeft, newTop, topBar.paddingRight, topBar.paddingBottom)
                bottomStack.setPadding(bottomStack.paddingLeft, 0, bottomStack.paddingRight, newBottom)
                emptyState.setPadding(0, top, 0, bottom)
                layoutForOrientation()
            }
            insets
        }

        findViewById<View>(R.id.btnOpen).setOnClickListener { openPicker() }
        findViewById<View>(R.id.btnOpenBig).setOnClickListener { openPicker() }
        findViewById<View>(R.id.btnFormats).setOnClickListener { Dialogs.formats(this) }
        findViewById<View>(R.id.btnRecent).setOnClickListener { showRecentDialog() }
        findViewById<View>(R.id.btnPanel).setOnClickListener { togglePanel(sidePanel.visibility != View.VISIBLE) }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnFit).setOnClickListener { fitView() }
        findViewById<View>(R.id.btnReset).setOnClickListener { current?.let { glView.renderer.camera.reset(it.model.bounds, true); glView.requestRender(); scheduleTriadUpdates() } }
        findViewById<View>(R.id.btnViews).setOnClickListener { showViewsMenu(it) }
        findViewById<View>(R.id.btnProjection).setOnClickListener { toggleProjection() }
        findViewById<View>(R.id.btnMeasure).setOnClickListener { setMeasureMode(!measureMode) }
        findViewById<View>(R.id.btnSection).setOnClickListener { sectionHud.visibility = if (sectionHud.visibility == View.VISIBLE) View.GONE else View.VISIBLE; if (sectionHud.visibility == View.VISIBLE && glView.settings.clipAxis == ClipAxis.NONE) setClip(ClipAxis.Z); if (sectionHud.visibility != View.VISIBLE) setClip(ClipAxis.NONE); syncToggles() }
        findViewById<View>(R.id.btnSectionClose).setOnClickListener { sectionHud.visibility = View.GONE; setClip(ClipAxis.NONE) }
        findViewById<View>(R.id.btnMeasureClose).setOnClickListener { setMeasureMode(false) }
        findViewById<View>(R.id.btnMeasureClear).setOnClickListener { picks.clear(); updateMeasureHud() }
        findViewById<View>(R.id.btnCancelLoad).setOnClickListener { loader.cancel() }
        findViewById<View>(R.id.clipOff).setOnClickListener { setClip(ClipAxis.NONE) }
        findViewById<View>(R.id.clipX).setOnClickListener { setClip(ClipAxis.X) }
        findViewById<View>(R.id.clipY).setOnClickListener { setClip(ClipAxis.Y) }
        findViewById<View>(R.id.clipZ).setOnClickListener { setClip(ClipAxis.Z) }
        findViewById<View>(R.id.clipFlip).setOnClickListener { glView.settings.clipFlip = !glView.settings.clipFlip; syncClipChips(); glView.requestRender() }
        findViewById<SeekBar>(R.id.clipSeek).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { glView.settings.clipOffset = p / 1000f; glView.requestRender() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        modeButtons[ViewMode.SOLID] = findViewById(R.id.modeSolid); modeButtons[ViewMode.SOLID_EDGES] = findViewById(R.id.modeEdges)
        modeButtons[ViewMode.WIREFRAME] = findViewById(R.id.modeWire); modeButtons[ViewMode.XRAY] = findViewById(R.id.modeXray)
        for ((m, b) in modeButtons) b.setOnClickListener { setMode(m) }
        findViewById<View>(R.id.toggleGrid).setOnClickListener { glView.settings.showGrid = !glView.settings.showGrid; syncToggles(); glView.requestRender(); saveSettings() }
        findViewById<View>(R.id.toggleAxes).setOnClickListener { glView.settings.showAxes = !glView.settings.showAxes; syncToggles(); glView.requestRender(); saveSettings() }
        findViewById<View>(R.id.toggleBox).setOnClickListener { glView.settings.showBoundingBox = !glView.settings.showBoundingBox; syncToggles(); glView.requestRender(); saveSettings() }

        glView.onTap = { x, y -> onTap(x, y) }
        glView.onDoubleTap = { fitView() }
        glView.onCameraChanged = { updateTriadLabels() }
        syncToggles(); syncClipChips(); refreshRecentList(); layoutForOrientation()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); glView.onResume(); scheduleTriadUpdates() }
    override fun onPause() { super.onPause(); glView.onPause() }
    override fun onDestroy() { super.onDestroy(); if (isFinishing) loader.cancel(); pickExecutor.shutdownNow() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); layoutForOrientation(); glView.post { updateTriadLabels() } }

    override fun onBackPressed() {
        when {
            loadingOverlay.visibility == View.VISIBLE -> loader.cancel()
            sidePanel.visibility == View.VISIBLE -> togglePanel(false)
            measureMode -> setMeasureMode(false)
            sectionHud.visibility == View.VISIBLE -> { sectionHud.visibility = View.GONE; setClip(ClipAxis.NONE) }
            else -> super.onBackPressed()
        }
    }

    // ---------------- intents & files ----------------
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        }
        if (uri != null) { intent.action = null; open(uri) }
    }

    private fun openPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, 1) } catch (e: Exception) { Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { open(it) }
    }

    fun open(uri: Uri) {
        pendingUri = uri
        loadingOverlay.visibility = View.VISIBLE
        findViewById<TextView>(R.id.loadingTitle).text = getString(R.string.loading)
        findViewById<TextView>(R.id.loadingMessage).text = ""
        findViewById<ProgressBar>(R.id.loadingProgress).progress = 0
        findViewById<TextView>(R.id.loadingPercent).text = ""
        loader.load(uri, object : ModelLoader.Listener {
            override fun onProgress(fraction: Float, message: String) {
                if (isFinishing || isDestroyed) return
                findViewById<TextView>(R.id.loadingMessage).text = message
                val pb = findViewById<ProgressBar>(R.id.loadingProgress)
                if (fraction >= 0) { pb.isIndeterminate = false; pb.progress = (fraction * 1000).toInt(); findViewById<TextView>(R.id.loadingPercent).text = "${(fraction * 100).toInt()}%" } else pb.isIndeterminate = true
            }
            override fun onLoaded(result: ModelLoader.LoadResult) { if (isFinishing || isDestroyed) return; loadingOverlay.visibility = View.GONE; showModel(result) }
            override fun onFailed(error: String, details: String?) {
                if (isFinishing || isDestroyed) return
                loadingOverlay.visibility = View.GONE
                Dialogs.error(this@MainActivity, getString(R.string.error_title), error, details) { pendingUri?.let { open(it) } }
            }
            override fun onCancelled() { if (!isFinishing && !isDestroyed) loadingOverlay.visibility = View.GONE }
        }) { node -> glView.renderer.edgesUpdated(node) }
    }

    private fun showModel(result: ModelLoader.LoadResult) {
        current = result
        val m = result.model
        picks.clear(); updateMeasureHud()
        glView.settings.selectedNodeId = -1
        fileUnits = if (m.declaredUnits == ModelUnits.UNKNOWN) ModelUnits.MILLIMETER else m.declaredUnits
        applyUnits()
        glView.renderer.setModel(m)
        glView.renderer.camera.reset(m.bounds, false)
        glView.requestRender()
        title.text = m.fileName
        subtitle.visibility = View.VISIBLE
        subtitle.text = "${result.format.name} · ${Formatters.count(m.triangleCount)} triangles · ${m.meshNodes.size} parts · ${Formatters.bytes(m.fileSize)}"
        emptyState.visibility = View.GONE
        panel.resetExpansion(); panel.refreshInfo(result); panel.refreshParts(); panel.syncUnitSpinners()
        recent.add(result.uri, m.fileName, m.fileSize, result.format.name, m.triangleCount)
        refreshRecentList()
        scheduleTriadUpdates()
        if (m.notes.any { it.contains("not supported", true) || it.contains("ignored", true) }) Toast.makeText(this, m.notes.first { it.contains("not supported", true) || it.contains("ignored", true) }, Toast.LENGTH_LONG).show()
    }

    fun applyUnits() {
        glView.settings.modelToDisplayScale = fileUnits.toMm / glView.settings.displayUnits.toMm
        if (::panel.isInitialized) panel.refreshInfo(current)
        updateMeasureHud(); saveSettings()
    }

    // ---------------- camera & modes ----------------
    private fun fitView() { current?.let { glView.renderer.camera.frame(it.model.bounds, true); glView.requestRender(); scheduleTriadUpdates() } }

    fun frameNode(n: Node) { val b = panel.boundsOf(n); val m = currentModel() ?: return; if (!b.isEmpty) { glView.renderer.camera.frame(b, true, m.bounds); glView.requestRender(); scheduleTriadUpdates() } }

    private fun showViewsMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        StandardView.values().forEachIndexed { i, v -> pm.menu.add(0, i, i, v.label) }
        pm.setOnMenuItemClickListener { item -> current?.let { glView.renderer.camera.standardView(StandardView.values()[item.itemId], it.model.bounds, true); glView.requestRender(); scheduleTriadUpdates() }; true }
        pm.show()
    }

    private fun toggleProjection() {
        val s = glView.settings
        s.projection = if (s.projection == Projection.PERSPECTIVE) Projection.ORTHOGRAPHIC else Projection.PERSPECTIVE
        Toast.makeText(this, s.projection.label, Toast.LENGTH_SHORT).show()
        syncToggles(); glView.requestRender(); saveSettings()
    }

    private fun setMode(m: ViewMode) { glView.settings.viewMode = m; syncToggles(); glView.requestRender(); saveSettings() }

    fun syncToggles() {
        val s = glView.settings
        for ((m, b) in modeButtons) { b.isSelected = s.viewMode == m; b.setTextColor(getColor(if (s.viewMode == m) R.color.accent_text else R.color.text_secondary)) }
        fun tint(id: Int, on: Boolean) = findViewById<ImageButton>(id).setColorFilter(getColor(if (on) R.color.accent else R.color.text_primary))
        tint(R.id.toggleGrid, s.showGrid); tint(R.id.toggleAxes, s.showAxes); tint(R.id.toggleBox, s.showBoundingBox)
        tint(R.id.btnProjection, s.projection == Projection.ORTHOGRAPHIC)
        findViewById<ImageButton>(R.id.btnMeasure).apply { setBackgroundResource(if (measureMode) R.drawable.bg_rail_button_active else R.drawable.bg_rail_button); setColorFilter(getColor(if (measureMode) R.color.accent else R.color.text_primary)) }
        findViewById<ImageButton>(R.id.btnSection).apply { val on = s.clipAxis != ClipAxis.NONE; setBackgroundResource(if (on) R.drawable.bg_rail_button_active else R.drawable.bg_rail_button); setColorFilter(getColor(if (on) R.color.accent else R.color.text_primary)) }
        findViewById<ImageButton>(R.id.btnPanel).setColorFilter(getColor(if (sidePanel.visibility == View.VISIBLE) R.color.accent else R.color.text_primary))
    }

    private fun setClip(axis: ClipAxis) { glView.settings.clipAxis = axis; syncClipChips(); syncToggles(); glView.requestRender() }
    private fun syncClipChips() {
        val s = glView.settings
        val map = mapOf(R.id.clipOff to ClipAxis.NONE, R.id.clipX to ClipAxis.X, R.id.clipY to ClipAxis.Y, R.id.clipZ to ClipAxis.Z)
        for ((id, ax) in map) { val b = findViewById<Button>(id); b.isSelected = s.clipAxis == ax; b.setTextColor(getColor(if (s.clipAxis == ax) R.color.accent_text else R.color.text_secondary)) }
        findViewById<Button>(R.id.clipFlip).apply { isSelected = s.clipFlip; setTextColor(getColor(if (s.clipFlip) R.color.accent_text else R.color.text_secondary)) }
        findViewById<SeekBar>(R.id.clipSeek).progress = (s.clipOffset * 1000).toInt()
    }

    // ---------------- picking & measurement ----------------
    private fun setMeasureMode(on: Boolean) {
        measureMode = on
        measureHud.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) { picks.clear(); updateMeasureHud() }
        syncToggles()
    }

    private fun onTap(x: Float, y: Float) {
        val m = currentModel() ?: return
        val ray = glView.renderer.camera.pickRay(x, y)
        val clip = glView.renderer.clipPlane()
        pickExecutor.execute {
            val hit = Picker.pick(m, ray, clip, clip != null)
            runOnUiThread { onPicked(hit) }
        }
    }

    private fun onPicked(hit: PickResult?) {
        if (measureMode) {
            if (hit == null) { Toast.makeText(this, "Tap on the model surface", Toast.LENGTH_SHORT).show(); return }
            if (picks.size >= 2) picks.clear()
            picks.add(hit); updateMeasureHud()
        } else {
            selectNode(hit?.node)
            if (hit != null) Toast.makeText(this, hit.node.name, Toast.LENGTH_SHORT).show()
        }
    }

    fun selectNode(n: Node?) {
        glView.settings.selectedNodeId = n?.id ?: -1
        glView.requestRender(); panel.refreshParts()
    }

    private fun updateMeasureHud() {
        val pts = FloatArray(picks.size * 3)
        picks.forEachIndexed { i, p -> pts[i * 3] = p.x; pts[i * 3 + 1] = p.y; pts[i * 3 + 2] = p.z }
        glView.renderer.measurePoints = pts
        glView.requestRender()
        if (picks.size < 2) {
            measureValue.text = if (picks.isEmpty()) getString(R.string.measure_hint) else "Point 1 set · tap the second point"
            measureDelta.visibility = View.GONE
            return
        }
        val a = picks[0]; val b = picks[1]
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val dz = (b.z - a.z).toDouble()
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val du = glView.settings.displayUnits
        measureValue.text = "Distance ${Formatters.length(d, fileUnits, du)}"
        measureDelta.visibility = View.VISIBLE
        measureDelta.text = "ΔX ${Formatters.length(kotlin.math.abs(dx), fileUnits, du)}   ΔY ${Formatters.length(kotlin.math.abs(dy), fileUnits, du)}   ΔZ ${Formatters.length(kotlin.math.abs(dz), fileUnits, du)}"
    }

    // ---------------- panel & layout ----------------
    fun togglePanel(show: Boolean) {
        sidePanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { panel.refreshInfo(current); panel.refreshParts() }
        layoutForOrientation(); syncToggles()
    }

    private fun layoutForOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dm = resources.displayMetrics
        val lp = sidePanel.layoutParams as FrameLayout.LayoutParams
        val topInset = topBar.paddingTop + Dialogs.dp(this, 50f)
        if (landscape) {
            lp.width = minOf(Dialogs.dp(this, 380f), dm.widthPixels / 2); lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.END or Gravity.TOP
            lp.setMargins(0, topInset, Dialogs.dp(this, 10f), Dialogs.dp(this, 10f))
            bottomStack.visibility = View.VISIBLE
            (rail.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; bottomMargin = 0; marginEnd = if (sidePanel.visibility == View.VISIBLE) lp.width + Dialogs.dp(this@MainActivity, 18f) else Dialogs.dp(this@MainActivity, 10f) }
            (bottomStack.layoutParams as FrameLayout.LayoutParams).apply { marginEnd = if (sidePanel.visibility == View.VISIBLE) lp.width + Dialogs.dp(this@MainActivity, 10f) else 0 }
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = (dm.heightPixels * 0.52f).toInt()
            lp.gravity = Gravity.BOTTOM
            lp.setMargins(Dialogs.dp(this, 8f), 0, Dialogs.dp(this, 8f), Dialogs.dp(this, 8f))
            bottomStack.visibility = if (sidePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            (rail.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; marginEnd = Dialogs.dp(this@MainActivity, 10f); bottomMargin = if (sidePanel.visibility == View.VISIBLE) lp.height else 0 }
            (bottomStack.layoutParams as FrameLayout.LayoutParams).marginEnd = 0
        }
        sidePanel.layoutParams = lp; rail.requestLayout(); bottomStack.requestLayout()
    }

    // ---------------- triad labels ----------------
    fun updateTriadLabels() {
        val show = glView.settings.showTriad && current != null
        val pos = glView.renderer.triadLabelPositions()
        for (i in 0 until 3) {
            val t = triadLabels[i]
            t.visibility = if (show) View.VISIBLE else View.GONE
            if (!show) continue
            t.x = pos[i * 2] - t.width / 2f + glView.left
            t.y = pos[i * 2 + 1] - t.height / 2f + glView.top
        }
    }

    private var triadTicks = 0
    private fun scheduleTriadUpdates() {
        triadTicks = 25
        val r = object : Runnable { override fun run() { updateTriadLabels(); if (--triadTicks > 0 || glView.renderer.camera.isAnimating) glView.postDelayed(this, 16) } }
        glView.post(r)
    }

    // ---------------- menu, recents ----------------
    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, getString(R.string.open_file)); pm.menu.add(0, 2, 1, getString(R.string.recent_files))
        pm.menu.add(0, 3, 2, getString(R.string.supported_formats)); pm.menu.add(0, 5, 3, "Share this file"); pm.menu.add(0, 4, 4, getString(R.string.about))
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) { 1 -> openPicker(); 2 -> showRecentDialog(); 3 -> Dialogs.formats(this); 4 -> Dialogs.about(this); 5 -> current?.let { r -> try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, r.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share model")) } catch (e: Exception) { Toast.makeText(this, "Cannot share this file", Toast.LENGTH_SHORT).show() } } }
            true
        }
        pm.show()
    }

    private fun refreshRecentList() {
        val c = findViewById<LinearLayout>(R.id.recentContainer)
        c.removeAllViews()
        val items = recent.list()
        findViewById<View>(R.id.recentTitle).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        c.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        for (e in items.take(8)) c.addView(recentRow(e, c) { refreshRecentList() })
    }

    private fun recentRow(e: RecentFiles.Entry, parent: ViewGroup, onChange: () -> Unit): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_recent, parent, false)
        v.findViewById<TextView>(R.id.recentBadge).text = e.format.take(5).uppercase()
        v.findViewById<TextView>(R.id.recentName).text = e.name
        v.findViewById<TextView>(R.id.recentMeta).text = "${Formatters.bytes(e.size)} · ${Formatters.count(e.triangles)} tri · ${android.text.format.DateUtils.getRelativeTimeSpanString(e.openedAt)}"
        v.setOnClickListener { open(Uri.parse(e.uri)) }
        v.findViewById<View>(R.id.recentRemove).setOnClickListener { recent.remove(e.uri); onChange() }
        return v
    }

    private fun showRecentDialog() {
        val items = recent.list()
        if (items.isEmpty()) { Toast.makeText(this, R.string.no_recent, Toast.LENGTH_SHORT).show(); return }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Dialogs.dp(this@MainActivity, 8f), Dialogs.dp(this@MainActivity, 8f), Dialogs.dp(this@MainActivity, 8f), Dialogs.dp(this@MainActivity, 8f)) }
        val scroll = ScrollView(this).apply { addView(list) }
        val d = android.app.AlertDialog.Builder(this).setTitle(R.string.recent_files).setView(scroll).setPositiveButton(R.string.close, null).setNeutralButton(R.string.clear) { _, _ -> recent.clear(); refreshRecentList() }.create()
        fun fill() { list.removeAllViews(); for (e in recent.list()) list.addView(recentRow(e, list) { fill(); refreshRecentList() }.also { row -> row.setOnClickListener { d.dismiss(); open(Uri.parse(e.uri)) } }) }
        fill(); d.show()
    }

    // ---------------- settings persistence ----------------
    fun saveSettings() {
        val s = glView.settings
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("viewMode", s.viewMode.name).putString("projection", s.projection.name).putString("upAxis", s.upAxis.name)
            .putInt("background", BackgroundPreset.ALL.indexOf(s.background)).putBoolean("grid", s.showGrid).putBoolean("axes", s.showAxes)
            .putBoolean("bbox", s.showBoundingBox).putBoolean("triad", s.showTriad).putFloat("intensity", s.lightIntensity).putFloat("ambient", s.ambient)
            .putFloat("specular", s.specular).putBoolean("headlight", s.headlight).putFloat("xray", s.xrayAlpha).putString("displayUnits", s.displayUnits.name)
            .putString("cadQuality", loader.cadQuality.name).apply()
    }

    private fun loadSettings() {
        val p = getSharedPreferences("settings", MODE_PRIVATE); val s = glView.settings
        fun <T : Enum<T>> e(key: String, def: T, values: Array<T>): T = values.firstOrNull { it.name == p.getString(key, null) } ?: def
        s.viewMode = e("viewMode", ViewMode.SOLID_EDGES, ViewMode.values()); s.projection = e("projection", Projection.PERSPECTIVE, Projection.values())
        s.upAxis = e("upAxis", UpAxis.Z, UpAxis.values()); s.background = BackgroundPreset.ALL.getOrElse(p.getInt("background", 0)) { BackgroundPreset.ALL[0] }
        s.showGrid = p.getBoolean("grid", true); s.showAxes = p.getBoolean("axes", true); s.showBoundingBox = p.getBoolean("bbox", false); s.showTriad = p.getBoolean("triad", true)
        s.lightIntensity = p.getFloat("intensity", 1f); s.ambient = p.getFloat("ambient", 0.35f); s.specular = p.getFloat("specular", 0.35f); s.headlight = p.getBoolean("headlight", true)
        s.xrayAlpha = p.getFloat("xray", 0.32f); s.displayUnits = e("displayUnits", ModelUnits.MILLIMETER, ModelUnits.values())
        loader.cadQuality = e("cadQuality", com.universal3d.viewer.cad.CadConverter.Quality.MEDIUM, com.universal3d.viewer.cad.CadConverter.Quality.values())
    }
}
