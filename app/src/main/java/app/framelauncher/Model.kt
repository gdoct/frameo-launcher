package app.framelauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class ModeType(val label: String) {
    WEB("Web page"),
    CLOCK("Clock"),
    OFF("Screen off");
}

/** A named way the frame can look: a web page, a clock, or a dark screen. */
class Preset(
    val id: String = newId(),
    var name: String,
    var type: ModeType,
    var url: String = "",
    /** 1..255; ignored for OFF. */
    var brightness: Int = 180,
    /** Reload the page every N minutes (0 = never). WEB only. */
    var reloadMinutes: Int = 0,
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("url", url).put("brightness", brightness).put("reloadMinutes", reloadMinutes)

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            id = o.getString("id"),
            name = o.optString("name", "Mode"),
            type = runCatching { ModeType.valueOf(o.getString("type")) }.getOrDefault(ModeType.CLOCK),
            url = o.optString("url", ""),
            brightness = o.optInt("brightness", 180),
            reloadMinutes = o.optInt("reloadMinutes", 0),
        )
    }
}

/** "On these days, at this time, switch to this preset." */
class Rule(
    val id: String = newId(),
    /** Bit (Calendar.DAY_OF_WEEK - 1): bit0 = Sunday ... bit6 = Saturday. */
    var days: Int = ALL_DAYS,
    var minuteOfDay: Int = 8 * 60,
    var presetId: String,
    var enabled: Boolean = true,
) {
    fun toJson() = JSONObject()
        .put("id", id).put("days", days).put("minuteOfDay", minuteOfDay)
        .put("presetId", presetId).put("enabled", enabled)

    fun appliesOn(dayOfWeek: Int) = days and (1 shl (dayOfWeek - 1)) != 0

    companion object {
        const val ALL_DAYS = 0b1111111
        fun fromJson(o: JSONObject) = Rule(
            id = o.getString("id"),
            days = o.optInt("days", ALL_DAYS),
            minuteOfDay = o.optInt("minuteOfDay", 0),
            presetId = o.getString("presetId"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

class Config(
    val presets: MutableList<Preset>,
    val rules: MutableList<Rule>,
    /** Manual "show this now" preset, valid until [overrideUntil]. */
    var overridePresetId: String? = null,
    var overrideUntil: Long = 0,
    var allowInsecureSsl: Boolean = true,
    var pin: String = "",
) {
    fun preset(id: String?) = presets.firstOrNull { it.id == id }

    fun toJson(): JSONObject = JSONObject()
        .put("presets", JSONArray().apply { presets.forEach { put(it.toJson()) } })
        .put("rules", JSONArray().apply { rules.forEach { put(it.toJson()) } })
        .put("overridePresetId", overridePresetId ?: JSONObject.NULL)
        .put("overrideUntil", overrideUntil)
        .put("allowInsecureSsl", allowInsecureSsl)
        .put("pin", pin)

    companion object {
        fun fromJson(o: JSONObject): Config {
            val presets = o.optJSONArray("presets").objects().map(Preset::fromJson).toMutableList()
            val rules = o.optJSONArray("rules").objects().map(Rule::fromJson).toMutableList()
            return Config(
                presets, rules,
                overridePresetId = if (o.isNull("overridePresetId")) null else o.optString("overridePresetId"),
                overrideUntil = o.optLong("overrideUntil", 0),
                allowInsecureSsl = o.optBoolean("allowInsecureSsl", true),
                pin = o.optString("pin", ""),
            )
        }

        fun defaults(): Config {
            val photos = Preset(name = "Photos", type = ModeType.WEB, url = "", brightness = 200)
            val dashboard = Preset(name = "Dashboard", type = ModeType.WEB, url = "", brightness = 200)
            val night = Preset(name = "Night clock", type = ModeType.CLOCK, brightness = 8)
            val off = Preset(name = "Screen off", type = ModeType.OFF, brightness = 0)
            val rules = mutableListOf(
                Rule(minuteOfDay = 7 * 60, presetId = dashboard.id),
                Rule(minuteOfDay = 18 * 60, presetId = photos.id),
                Rule(minuteOfDay = 22 * 60 + 30, presetId = night.id),
                Rule(minuteOfDay = 23 * 60 + 30, presetId = off.id),
            )
            return Config(mutableListOf(photos, dashboard, night, off), rules)
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

object Store {
    private const val PREFS = "frame"
    private const val KEY = "config"

    fun load(ctx: Context): Config {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return raw?.let { runCatching { Config.fromJson(JSONObject(it)) }.getOrNull() }
            ?: Config.defaults().also { save(ctx, it) }
    }

    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, cfg.toJson().toString()).commit()
    }
}

/** Result of evaluating the schedule at a moment in time. */
class ScheduleState(
    val active: Preset?,
    val fromOverride: Boolean,
    /** Epoch millis of the next scheduled switch, or null if none. */
    val nextAt: Long?,
    val nextPreset: Preset?,
)

object Schedule {
    /** Every enabled rule firing between [from] and [to] (inclusive), sorted by time. */
    private fun occurrences(cfg: Config, from: Long, to: Long): List<Pair<Long, Rule>> {
        val out = ArrayList<Pair<Long, Rule>>()
        val day = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (day.timeInMillis <= to) {
            val dow = day.get(Calendar.DAY_OF_WEEK)
            for (r in cfg.rules) {
                if (!r.enabled || !r.appliesOn(dow) || cfg.preset(r.presetId) == null) continue
                val c = day.clone() as Calendar
                c.set(Calendar.HOUR_OF_DAY, r.minuteOfDay / 60)
                c.set(Calendar.MINUTE, r.minuteOfDay % 60)
                val t = c.timeInMillis
                if (t in from..to) out += t to r
            }
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        out.sortBy { it.first }
        return out
    }

    /** The preset the schedule alone says should be showing at [now], plus the next switch. */
    fun scheduled(cfg: Config, now: Long): Triple<Preset?, Long?, Preset?> {
        val week = 8L * 24 * 3600 * 1000
        val occ = occurrences(cfg, now - week, now + week)
        val last = occ.lastOrNull { it.first <= now }
        val next = occ.firstOrNull { it.first > now }
        val active = last?.let { cfg.preset(it.second.presetId) } ?: cfg.presets.firstOrNull()
        return Triple(active, next?.first, next?.let { cfg.preset(it.second.presetId) })
    }

    fun state(cfg: Config, now: Long = System.currentTimeMillis()): ScheduleState {
        val (active, nextAt, nextPreset) = scheduled(cfg, now)
        val ov = cfg.preset(cfg.overridePresetId)
        if (ov != null && now < cfg.overrideUntil) {
            return ScheduleState(ov, true, nextAt, nextPreset)
        }
        return ScheduleState(active, false, nextAt, nextPreset)
    }
}
