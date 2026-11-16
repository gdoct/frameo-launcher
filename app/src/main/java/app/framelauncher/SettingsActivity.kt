package app.framelauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var content: LinearLayout

    /** Display order Mon..Sun as Calendar.DAY_OF_WEEK values. */
    private val dayOrder = listOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
        Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    )
    private val shortDays = DateFormatSymbols.getInstance().shortWeekdays

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(48))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        cfg = Store.load(this)
        render()
    }

    private fun save() {
        Store.save(this, cfg)
        Engine.sync(this, bringToFront = false)
        render()
    }

    // ---------- rendering ----------

    private fun render() {
        content.removeAllViews()
        val st = Schedule.state(cfg)

        header("Now")
        val nowText = buildString {
            append("Showing: ${st.active?.name ?: "—"}")
            if (st.fromOverride) append("  (manual, until next switch)")
            if (st.nextAt != null) append("\nNext: ${st.nextPreset?.name} at ${fmtWhen(st.nextAt)}")
            else append("\nNo upcoming switches")
        }
        content.addView(text(nowText, 18f))
        row(
            button("Show now…") { pickPresetNow() },
            if (st.fromOverride) button("Resume schedule") {
                cfg.overridePresetId = null; cfg.overrideUntil = 0; save()
            } else null,
            button("Back to frame") { finish() },
        )

        header("Schedule")
        content.addView(text("At each time the frame switches mode and stays there until the next entry.", 14f, dim = true))
        val sorted = cfg.rules.sortedWith(compareBy({ it.minuteOfDay }, { firstDayIndex(it.days) }))
        for (r in sorted) {
            val p = cfg.preset(r.presetId)
            val sw = Switch(this).apply {
                isChecked = r.enabled
                setOnCheckedChangeListener { _, c -> r.enabled = c; save() }
            }
            val label = text("${hhmm(r.minuteOfDay)}   ${p?.name ?: "(missing mode)"}\n${daysLabel(r.days)}", 18f)
            listItem(label, sw) { editRule(r) }
        }
        row(button("+ Add schedule entry") {
            val first = cfg.presets.firstOrNull() ?: return@button toast("Add a mode first")
            editRule(Rule(presetId = first.id), isNew = true)
        })

        header("Modes")
        for (p in cfg.presets) {
            val desc = when (p.type) {
                ModeType.WEB -> if (p.url.isBlank()) "Web page — no URL set" else p.url
                ModeType.CLOCK -> "Clock"
                ModeType.OFF -> "Screen off"
            }
            val bright = if (p.type == ModeType.OFF) "" else "  ·  brightness ${p.brightness * 100 / 255}%"
            listItem(text("${p.name}\n$desc$bright", 18f), null) { editPreset(p) }
        }
        row(button("+ Add mode") {
            editPreset(Preset(name = "New mode", type = ModeType.WEB), isNew = true)
        })

        header("Device")
        val admin = Engine.isAdmin(this)
        content.addView(text(
            if (admin) "Screen-off control: enabled"
            else "Screen-off control: NOT enabled — \"Screen off\" will only dim until the system timeout.",
            15f, dim = admin
        ))
        if (!admin) row(button("Enable screen-off control") { requestAdmin() })

        val ssl = CheckBox(this).apply {
            text = "Accept self-signed HTTPS certificates (e.g. NAS)"
            isChecked = cfg.allowInsecureSsl
            textSize = 16f
            setOnCheckedChangeListener { _, c -> cfg.allowInsecureSsl = c; Store.save(this@SettingsActivity, cfg) }
        }
        content.addView(ssl)
        row(
            button(if (cfg.pin.isBlank()) "Set settings PIN" else "Change / remove PIN") { editPin() },
        )
        content.addView(text("Open settings from the frame: hold the top-left corner for 2.5 seconds.", 14f, dim = true))

        header("Other")
        row(
            button("Android settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) },
            button("Wi-Fi") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            button("Other apps…") { pickApp() },
        )
    }

    // ---------- editors ----------

    private fun editRule(r: Rule, isNew: Boolean = false) {
        if (cfg.presets.isEmpty()) return toast("Add a mode first")
        val box = vbox()
        val tp = TimePicker(this).apply {
            setIs24HourView(true)
            @Suppress("DEPRECATION") currentHour = r.minuteOfDay / 60
            @Suppress("DEPRECATION") currentMinute = r.minuteOfDay % 60
        }
        box.addView(tp)

        val dayBoxes = dayOrder.map { dow ->
            CheckBox(this).apply { text = shortDays[dow]; isChecked = r.appliesOn(dow) }
        }
        val daysRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        dayBoxes.forEach { daysRow.addView(it) }
        box.addView(daysRow)
        rowIn(box,
            smallButton("Every day") { dayBoxes.forEach { it.isChecked = true } },
            smallButton("Weekdays") { dayBoxes.forEachIndexed { i, c -> c.isChecked = i < 5 } },
            smallButton("Weekend") { dayBoxes.forEachIndexed { i, c -> c.isChecked = i >= 5 } },
        )

        val spinner = presetSpinner(r.presetId)
        box.addView(labelled("Switch to", spinner))

        val b = AlertDialog.Builder(this)
            .setTitle(if (isNew) "New schedule entry" else "Edit schedule entry")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                @Suppress("DEPRECATION")
                r.minuteOfDay = tp.currentHour * 60 + tp.currentMinute
                var mask = 0
                dayBoxes.forEachIndexed { i, c -> if (c.isChecked) mask = mask or (1 shl (dayOrder[i] - 1)) }
                if (mask == 0) return@setPositiveButton toast("Pick at least one day")
                r.days = mask
                r.presetId = cfg.presets[spinner.selectedItemPosition].id
                if (isNew) cfg.rules += r
                save()
            }
            .setNegativeButton("Cancel", null)
        if (!isNew) b.setNeutralButton("Delete") { _, _ -> cfg.rules.remove(r); save() }
        b.show()
    }

    private fun editPreset(p: Preset, isNew: Boolean = false) {
        val box = vbox()
        val name = EditText(this).apply { setText(p.name); setSingleLine() }
        box.addView(labelled("Name", name))

        val types = ModeType.values()
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, types.map { it.label })
            setSelection(types.indexOf(p.type))
        }
        box.addView(labelled("Type", type))

        val url = EditText(this).apply {
            setText(p.url); setSingleLine(); hint = "http://nas.local:8080/slideshow"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val urlRow = labelled("URL", url)
        box.addView(urlRow)

        val reload = EditText(this).apply {
            setText(if (p.reloadMinutes > 0) p.reloadMinutes.toString() else "")
            hint = "never"; inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine()
        }
        val reloadRow = labelled("Reload every (min)", reload)
        box.addView(reloadRow)

        val brightLabel = text("", 15f)
        val bright = SeekBar(this).apply {
            max = 254; progress = (p.brightness - 1).coerceIn(0, 254)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, u: Boolean) {
                    brightLabel.text = "Brightness: ${(v + 1) * 100 / 255}%"
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        brightLabel.text = "Brightness: ${p.brightness * 100 / 255}%"
        val brightRow = vbox().apply { addView(brightLabel); addView(bright) }
        box.addView(brightRow)

        fun syncVisibility() {
            val t = types[type.selectedItemPosition]
            urlRow.visibility = if (t == ModeType.WEB) View.VISIBLE else View.GONE
            reloadRow.visibility = if (t == ModeType.WEB) View.VISIBLE else View.GONE
            brightRow.visibility = if (t == ModeType.OFF) View.GONE else View.VISIBLE
        }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = syncVisibility()
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        syncVisibility()

        val b = AlertDialog.Builder(this)
            .setTitle(if (isNew) "New mode" else "Edit mode")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                p.name = name.text.toString().trim().ifEmpty { "Mode" }
                p.type = types[type.selectedItemPosition]
                var u = url.text.toString().trim()
                if (u.isNotEmpty() && !u.contains("://")) u = "http://$u"
                p.url = u
                p.reloadMinutes = reload.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                p.brightness = bright.progress + 1
                if (isNew) cfg.presets += p
                save()
            }
            .setNegativeButton("Cancel", null)
        if (!isNew) b.setNeutralButton("Delete") { _, _ ->
            val used = cfg.rules.count { it.presetId == p.id }
            if (used > 0) {
                AlertDialog.Builder(this)
                    .setMessage("\"${p.name}\" is used by $used schedule entr${if (used == 1) "y" else "ies"}. Delete the mode and those entries?")
                    .setPositiveButton("Delete") { _, _ ->
                        cfg.rules.removeAll { it.presetId == p.id }; cfg.presets.remove(p); save()
                    }
                    .setNegativeButton("Cancel", null).show()
            } else {
                cfg.presets.remove(p); save()
            }
        }
        b.show()
    }

    private fun pickPresetNow() {
        if (cfg.presets.isEmpty()) return toast("Add a mode first")
        AlertDialog.Builder(this)
            .setTitle("Show until the next scheduled switch")
            .setItems(cfg.presets.map { it.name }.toTypedArray()) { _, i ->
                val st = Schedule.state(cfg)
                cfg.overridePresetId = cfg.presets[i].id
                cfg.overrideUntil = st.nextAt ?: Long.MAX_VALUE
                Store.save(this, cfg)
                Engine.sync(this, bringToFront = true)
                finish()
            }
            .show()
    }

    private fun editPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "leave empty for no PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Settings PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> cfg.pin = input.text.toString().trim(); save() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestAdmin() {
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Engine.adminComponent(this))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_desc)))
    }

    private fun pickApp() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.ROOT) }
        AlertDialog.Builder(this)
            .setTitle("Open app")
            .setItems(apps.map { it.loadLabel(pm) }.toTypedArray()) { _, i ->
                val ai = apps[i].activityInfo
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            .show()
    }

    // ---------- helpers ----------

    private fun presetSpinner(selectedId: String) = Spinner(this).apply {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, cfg.presets.map { it.name })
        setSelection(cfg.presets.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
    }

    private fun firstDayIndex(mask: Int) = dayOrder.indexOfFirst { mask and (1 shl (it - 1)) != 0 }

    private fun daysLabel(mask: Int): String {
        val on = dayOrder.map { mask and (1 shl (it - 1)) != 0 }
        return when {
            on.all { it } -> "Every day"
            on.take(5).all { it } && on.drop(5).none { it } -> "Weekdays"
            on.take(5).none { it } && on.drop(5).all { it } -> "Weekend"
            else -> dayOrder.filterIndexed { i, _ -> on[i] }.joinToString(" ") { shortDays[it] }
        }
    }

    private fun hhmm(m: Int) = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

    private fun fmtWhen(t: Long): String {
        val today = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = today.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "EEE HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vbox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    private fun header(s: String) {
        content.addView(TextView(this).apply {
            text = s; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF4FC3F7.toInt())
            setPadding(0, dp(24), 0, dp(8))
        })
    }

    private fun text(s: String, size: Float, dim: Boolean = false) = TextView(this).apply {
        text = s; textSize = size
        setTextColor(if (dim) 0xFF90A4AE.toInt() else Color.WHITE)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = button(label, onClick).apply { textSize = 14f }

    private fun row(vararg views: View?) = rowIn(content, *views)

    private fun rowIn(parent: ViewGroup, vararg views: View?) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.filterNotNull().forEach {
            r.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) })
        }
        parent.addView(r)
    }

    private fun labelled(label: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            text = label; textSize = 16f; minWidth = dp(150)
        })
        addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun listItem(main: View, trailing: View?, onClick: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF1E272C.toInt())
            isClickable = true
            setOnClickListener { onClick() }
        }
        r.addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { r.addView(it) }
        content.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }
}
