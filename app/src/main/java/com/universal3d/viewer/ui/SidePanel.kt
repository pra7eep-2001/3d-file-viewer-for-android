package com.universal3d.viewer.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.universal3d.viewer.R
import com.universal3d.viewer.app.ModelLoader
import com.universal3d.viewer.cad.CadConverter
import com.universal3d.viewer.model.Bounds
import com.universal3d.viewer.model.ModelUnits
import com.universal3d.viewer.model.Node
import com.universal3d.viewer.render.BackgroundPreset
import com.universal3d.viewer.render.UpAxis

/** The Info / Parts / Display panel. */
class SidePanel(private val a: MainActivity) {
    private val panel: View = a.findViewById(R.id.sidePanel)
    private val pageInfo: View = a.findViewById(R.id.pageInfo)
    private val pageParts: View = a.findViewById(R.id.pageParts)
    private val pageDisplay: View = a.findViewById(R.id.pageDisplay)
    private val tabInfo: Button = a.findViewById(R.id.tabInfo)
    private val tabParts: Button = a.findViewById(R.id.tabParts)
    private val tabDisplay: Button = a.findViewById(R.id.tabDisplay)
    private val infoContainer: LinearLayout = a.findViewById(R.id.infoContainer)
    private val displayContainer: LinearLayout = a.findViewById(R.id.displayContainer)
    private val partsList: ListView = a.findViewById(R.id.partsList)
    private val partsSummary: TextView = a.findViewById(R.id.partsSummary)
    private val adapter = NodeAdapter()
    private val expanded = HashSet<Int>()
    private var rows: List<Node> = emptyList()
    var tab = 0; private set

    init {
        tabInfo.setOnClickListener { showTab(0) }; tabParts.setOnClickListener { showTab(1) }; tabDisplay.setOnClickListener { showTab(2) }
        a.findViewById<View>(R.id.btnPanelClose).setOnClickListener { a.togglePanel(false) }
        partsList.adapter = adapter
        partsList.setOnItemClickListener { _, _, pos, _ -> val n = rows[pos]; a.selectNode(if (a.glView.settings.selectedNodeId == n.id) null else n); adapter.notifyDataSetChanged() }
        partsList.setOnItemLongClickListener { _, v, pos, _ -> nodeMenu(v, rows[pos]); true }
        a.findViewById<View>(R.id.btnShowAll).setOnClickListener { a.currentModel()?.nodes?.forEach { it.visible = true }; refreshParts(); a.glView.requestRender() }
        a.findViewById<View>(R.id.btnIsolate).setOnClickListener {
            val m = a.currentModel() ?: return@setOnClickListener
            val sel = m.nodes.firstOrNull { it.id == a.glView.settings.selectedNodeId }
            if (sel == null) { Toast.makeText(a, "Select a part first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            m.nodes.forEach { it.visible = false }
            var n: Node? = sel; while (n != null) { n.visible = true; n = n.parent }
            sel.walk { it.visible = true }
            refreshParts(); a.glView.requestRender()
        }
        showTab(0)
        buildDisplayPage()
    }

    fun showTab(i: Int) {
        tab = i
        pageInfo.visibility = if (i == 0) View.VISIBLE else View.GONE
        pageParts.visibility = if (i == 1) View.VISIBLE else View.GONE
        pageDisplay.visibility = if (i == 2) View.VISIBLE else View.GONE
        tabInfo.isSelected = i == 0; tabParts.isSelected = i == 1; tabDisplay.isSelected = i == 2
        for ((b, s) in listOf(tabInfo to (i == 0), tabParts to (i == 1), tabDisplay to (i == 2))) b.setTextColor(a.getColor(if (s) R.color.accent_text else R.color.text_secondary))
    }

    // ---------------- Info ----------------
    fun refreshInfo(result: ModelLoader.LoadResult?) {
        infoContainer.removeAllViews()
        if (result == null) { infoContainer.addView(caption("No model loaded")); return }
        val m = result.model
        val fu = a.fileUnits; val du = a.glView.settings.displayUnits
        section("File")
        row("Name", m.fileName); row("Format", "${result.format.name} · ${result.format.notes.substringBefore('.')}".take(120)); row("Size", Formatters.bytes(m.fileSize)); row("Load time", Formatters.duration(result.loadMillis))
        section("Geometry")
        row("Parts", Formatters.count(m.meshNodes.size)); row("Nodes", Formatters.count(m.nodes.size)); row("Triangles", Formatters.count(m.triangleCount)); row("Vertices", Formatters.count(m.vertexCount))
        if (m.edgeSegmentCount > 0) row("Edge segments", Formatters.count(m.edgeSegmentCount))
        section("Dimensions (${du.label})")
        val b = m.bounds
        row("Size X", Formatters.length(b.sizeX.toDouble(), fu, du)); row("Size Y", Formatters.length(b.sizeY.toDouble(), fu, du)); row("Size Z", Formatters.length(b.sizeZ.toDouble(), fu, du))
        row("Diagonal", Formatters.length(b.diagonal.toDouble(), fu, du))
        row("Min", "(${Formatters.length(b.minX.toDouble(), fu, du, 2)}, ${Formatters.length(b.minY.toDouble(), fu, du, 2)}, ${Formatters.length(b.minZ.toDouble(), fu, du, 2)})")
        row("Max", "(${Formatters.length(b.maxX.toDouble(), fu, du, 2)}, ${Formatters.length(b.maxY.toDouble(), fu, du, 2)}, ${Formatters.length(b.maxZ.toDouble(), fu, du, 2)})")
        row("Center", "(${Formatters.length(b.centerX.toDouble(), fu, du, 2)}, ${Formatters.length(b.centerY.toDouble(), fu, du, 2)}, ${Formatters.length(b.centerZ.toDouble(), fu, du, 2)})")
        section("Units")
        row("Declared in file", if (m.declaredUnits == ModelUnits.UNKNOWN) "none (assumed ${fu.label})" else m.declaredUnits.label)
        row("Interpreted as", fu.label); row("Displayed in", du.label)
        if (m.notes.isNotEmpty()) { section("Notes"); for (n in m.notes) infoContainer.addView(caption("• $n")) }
    }

    private fun section(t: String) { infoContainer.addView(TextView(a).apply { text = t; setTextAppearance(android.R.style.TextAppearance_Material_Small); setTextColor(a.getColor(R.color.text_tertiary)); textSize = 11f; isAllCaps = true; letterSpacing = 0.08f; setPadding(0, Dialogs.dp(a, 12f), 0, Dialogs.dp(a, 4f)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }) }
    private fun row(k: String, v: String) {
        val l = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Dialogs.dp(a, 3f), 0, Dialogs.dp(a, 3f)) }
        l.addView(TextView(a).apply { text = k; setTextColor(a.getColor(R.color.text_secondary)); textSize = 13f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f))
        l.addView(TextView(a).apply { text = v; setTextColor(a.getColor(R.color.text_primary)); textSize = 13f; gravity = Gravity.END; setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
        infoContainer.addView(l)
    }
    private fun caption(t: String) = TextView(a).apply { text = t; setTextColor(a.getColor(R.color.text_secondary)); textSize = 12.5f; setPadding(0, Dialogs.dp(a, 2f), 0, Dialogs.dp(a, 2f)) }

    // ---------------- Parts ----------------
    fun refreshParts() {
        val m = a.currentModel()
        if (m == null) { rows = emptyList(); adapter.notifyDataSetChanged(); partsSummary.text = ""; return }
        if (expanded.isEmpty()) { // expand the first two levels by default
            m.nodes.forEach { if (it.depth < 2 && it.children.isNotEmpty()) expanded.add(it.id) }
        }
        val out = ArrayList<Node>()
        fun visit(n: Node) { out.add(n); if (expanded.contains(n.id)) n.children.forEach { visit(it) } }
        m.roots.forEach { visit(it) }
        rows = out
        val hidden = m.meshNodes.count { !it.effectivelyVisible }
        partsSummary.text = "${m.meshNodes.size} parts" + if (hidden > 0) " · $hidden hidden" else ""
        adapter.notifyDataSetChanged()
    }
    fun resetExpansion() { expanded.clear() }

    private fun nodeMenu(anchor: View, n: Node) {
        val pm = PopupMenu(a, anchor)
        pm.menu.add(0, 1, 0, "Fit view to part"); pm.menu.add(0, 2, 1, if (n.visible) "Hide" else "Show"); pm.menu.add(0, 3, 2, "Isolate"); pm.menu.add(0, 4, 3, "Show all")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> a.frameNode(n)
                2 -> { n.visible = !n.visible; refreshParts(); a.glView.requestRender() }
                3 -> { a.currentModel()?.nodes?.forEach { it.visible = false }; var p: Node? = n; while (p != null) { p.visible = true; p = p.parent }; n.walk { it.visible = true }; refreshParts(); a.glView.requestRender() }
                4 -> { a.currentModel()?.nodes?.forEach { it.visible = true }; refreshParts(); a.glView.requestRender() }
            }
            true
        }
        pm.show()
    }

    private inner class NodeAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = rows[p].id.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: LayoutInflater.from(a).inflate(R.layout.item_node, parent, false)
            val n = rows[p]
            v.findViewById<View>(R.id.nodeIndent).layoutParams = LinearLayout.LayoutParams(Dialogs.dp(a, 8f + 16f * n.depth.coerceAtMost(8)), 1)
            val exp = v.findViewById<ImageView>(R.id.nodeExpand)
            if (n.children.isNotEmpty()) {
                exp.visibility = View.VISIBLE; exp.rotation = if (expanded.contains(n.id)) 90f else 0f
                exp.setImageResource(R.drawable.ic_parts); exp.alpha = 1f
                exp.setOnClickListener { if (!expanded.remove(n.id)) expanded.add(n.id); refreshParts() }
            } else { exp.visibility = View.VISIBLE; exp.rotation = 0f; exp.setImageResource(R.drawable.ic_cube); exp.alpha = 0.5f; exp.setOnClickListener(null); exp.isClickable = false }
            val dot = v.findViewById<View>(R.id.nodeColor)
            dot.setBackgroundColor(if (n.color != 0) n.color else a.glView.settings.defaultPartColor)
            dot.visibility = if (n.mesh != null) View.VISIBLE else View.INVISIBLE
            v.findViewById<TextView>(R.id.nodeName).apply { text = n.name; setTextColor(a.getColor(if (n.id == a.glView.settings.selectedNodeId) R.color.accent_text else R.color.text_primary)); alpha = if (n.effectivelyVisible) 1f else 0.45f }
            v.findViewById<TextView>(R.id.nodeMeta).text = n.mesh?.let { if (it.triangleCount > 0) "${Formatters.count(it.triangleCount)} tri" else "${it.edgeSegmentCount} edges" } ?: "${n.children.size} items"
            val eye = v.findViewById<ImageView>(R.id.nodeVisible)
            eye.setImageResource(if (n.visible) R.drawable.ic_eye else R.drawable.ic_eye_off)
            eye.alpha = if (n.effectivelyVisible) 1f else 0.45f
            eye.setOnClickListener { n.visible = !n.visible; refreshParts(); a.glView.requestRender() }
            v.isActivated = n.id == a.glView.settings.selectedNodeId
            return v
        }
    }

    // ---------------- Display ----------------
    private lateinit var fileUnitsSpinner: Spinner
    private lateinit var displayUnitsSpinner: Spinner

    private fun buildDisplayPage() {
        val c = displayContainer; val s = a.glView.settings
        c.removeAllViews()
        fun title(t: String) { c.addView(TextView(a).apply { text = t; setTextColor(a.getColor(R.color.text_tertiary)); textSize = 11f; isAllCaps = true; letterSpacing = 0.08f; setPadding(0, Dialogs.dp(a, 14f), 0, Dialogs.dp(a, 6f)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }) }
        fun chips(options: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): List<Button> {
            val rowV = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            val scroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(rowV) }
            val buttons = options.mapIndexed { i, label ->
                Button(a, null, 0, R.style.U3D_ToggleChip).apply { text = label; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Dialogs.dp(a, 34f)).also { it.marginEnd = Dialogs.dp(a, 6f) } }
            }
            fun sync() { val sel = selected(); buttons.forEachIndexed { i, b -> b.isSelected = i == sel; b.setTextColor(a.getColor(if (i == sel) R.color.accent_text else R.color.text_secondary)) } }
            buttons.forEachIndexed { i, b -> rowV.addView(b); b.setOnClickListener { onSelect(i); sync(); a.glView.requestRender(); a.saveSettings() } }
            sync(); c.addView(scroll)
            return buttons
        }
        fun seek(label: String, max: Int, get: () -> Int, set: (Int) -> Unit) {
            val l = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            l.addView(TextView(a).apply { text = label; setTextColor(a.getColor(R.color.text_secondary)); textSize = 13f }, LinearLayout.LayoutParams(Dialogs.dp(a, 92f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val sb = SeekBar(a).apply { this.max = max; progress = get() }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) { set(p); a.glView.requestRender() }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { a.saveSettings() }
            })
            l.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(l)
        }
        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val sw = Switch(a).apply { text = label; setTextColor(a.getColor(R.color.text_primary)); textSize = 14f; isChecked = get(); setPadding(0, Dialogs.dp(a, 8f), 0, Dialogs.dp(a, 8f)) }
            sw.setOnCheckedChangeListener { _, v -> set(v); a.glView.requestRender(); a.saveSettings() }
            c.addView(sw)
        }

        title("Units")
        val unitOptions = listOf(ModelUnits.MILLIMETER, ModelUnits.CENTIMETER, ModelUnits.METER, ModelUnits.INCH, ModelUnits.FOOT)
        val unitLabels = listOf("Millimetres (mm)", "Centimetres (cm)", "Metres (m)", "Inches (in)", "Feet (ft)")
        fun spinnerRow(label: String, init: ModelUnits, onPick: (ModelUnits) -> Unit): Spinner {
            val l = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            l.addView(TextView(a).apply { text = label; setTextColor(a.getColor(R.color.text_secondary)); textSize = 13f }, LinearLayout.LayoutParams(Dialogs.dp(a, 120f), ViewGroup.LayoutParams.WRAP_CONTENT))
            val sp = Spinner(a)
            sp.adapter = ArrayAdapter(a, android.R.layout.simple_spinner_dropdown_item, unitLabels)
            sp.setSelection(unitOptions.indexOf(init).coerceAtLeast(0))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPick(unitOptions[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            l.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(l)
            return sp
        }
        fileUnitsSpinner = spinnerRow("File units", a.fileUnits) { a.fileUnits = it; a.applyUnits() }
        displayUnitsSpinner = spinnerRow("Display units", s.displayUnits) { s.displayUnits = it; a.applyUnits() }
        c.addView(caption("File units tell the viewer what one unit in the file means (STL/OBJ carry no unit). Geometry is never rescaled."))

        title("Orientation")
        chips(UpAxis.values().map { it.label }, { s.upAxis.ordinal }) { s.upAxis = UpAxis.values()[it]; a.currentModel()?.let { m -> a.glView.renderer.camera.frame(m.bounds, true) } }

        title("Background")
        chips(BackgroundPreset.ALL.map { it.name }, { BackgroundPreset.ALL.indexOf(s.background) }) { s.background = BackgroundPreset.ALL[it] }

        title("Lighting")
        seek("Intensity", 100, { ((s.lightIntensity - 0.2f) / 1.8f * 100).toInt() }) { s.lightIntensity = 0.2f + it / 100f * 1.8f }
        seek("Ambient", 100, { (s.ambient * 100).toInt() }) { s.ambient = it / 100f }
        seek("Specular", 100, { (s.specular * 100).toInt() }) { s.specular = it / 100f }
        toggle("Headlight (light follows camera)", { s.headlight }) { s.headlight = it }
        seek("X-ray opacity", 100, { (s.xrayAlpha * 100).toInt() }) { s.xrayAlpha = (it / 100f).coerceIn(0.05f, 0.95f) }

        title("Overlays")
        toggle("Navigation triad", { s.showTriad }) { s.showTriad = it; a.updateTriadLabels() }
        toggle("Bounding box", { s.showBoundingBox }) { s.showBoundingBox = it; a.syncToggles() }

        title("CAD import (STEP / IGES)")
        chips(CadConverter.Quality.values().map { it.label }, { a.loader.cadQuality.ordinal }) { a.loader.cadQuality = CadConverter.Quality.values()[it] }
        c.addView(caption("Tessellation quality for the next STEP/IGES file. Finer settings show small holes and fillets more accurately but take longer and use more memory."))
    }

    fun syncUnitSpinners() {
        val unitOptions = listOf(ModelUnits.MILLIMETER, ModelUnits.CENTIMETER, ModelUnits.METER, ModelUnits.INCH, ModelUnits.FOOT)
        if (::fileUnitsSpinner.isInitialized) { fileUnitsSpinner.setSelection(unitOptions.indexOf(a.fileUnits).coerceAtLeast(0)); displayUnitsSpinner.setSelection(unitOptions.indexOf(a.glView.settings.displayUnits).coerceAtLeast(0)) }
    }

    fun boundsOf(n: Node): Bounds { val b = Bounds(); n.walk { c -> c.mesh?.let { b.add(it.bounds) } }; return b }
}
