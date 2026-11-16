package app.framelauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log

const val TAG = "FrameLauncher"

/**
 * Drives the frame: sets the alarm for the next scheduled switch and brings
 * the launcher to the front in the right state whenever a switch happens.
 */
object Engine {
    const val ACTION_TICK = "app.framelauncher.TICK"
    const val ACTION_BOOT_FRONT = "app.framelauncher.BOOT_FRONT"

    /** Re-evaluate the schedule, arm the next alarm, and make the frame match. */
    fun sync(ctx: Context, bringToFront: Boolean = true) {
        val cfg = Store.load(ctx)
        val now = System.currentTimeMillis()
        if (cfg.overridePresetId != null && now >= cfg.overrideUntil) {
            cfg.overridePresetId = null
            Store.save(ctx, cfg)
        }
        val st = Schedule.state(cfg, now)
        val overrideEnd = if (st.fromOverride && cfg.overrideUntil != Long.MAX_VALUE) cfg.overrideUntil else null
        arm(ctx, listOfNotNull(st.nextAt, overrideEnd).minOrNull())
        Log.i(TAG, "sync: active=${st.active?.name} override=${st.fromOverride} next=${st.nextAt}")

        if (st.active?.type != ModeType.OFF) wakeScreen(ctx)
        if (bringToFront) {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ).putExtra(MainActivity.EXTRA_FROM_ENGINE, true))
        }
    }

    private fun arm(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = tickIntent(ctx, ACTION_TICK)
        am.cancel(pi)
        if (at == null) return
        // +1s so the evaluation at fire time is safely past the boundary.
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at + 1000, pi)
    }

    /** One-off alarm used after boot to retake the screen from apps that auto-start. */
    fun armBootFront(ctx: Context, delayMs: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs,
            tickIntent(ctx, ACTION_BOOT_FRONT, delayMs.toInt())
        )
    }

    private fun tickIntent(ctx: Context, action: String, requestCode: Int = 1): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(
            ctx, requestCode,
            Intent(ctx, ScheduleReceiver::class.java).setAction(action), flags
        )
    }

    @Suppress("DEPRECATION")
    fun wakeScreen(ctx: Context) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) return
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "framelauncher:wake"
        )
        wl.acquire(5000)
    }

    fun adminComponent(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isAdmin(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent(ctx))
    }

    /** Turn the display off. Needs device admin; returns false if not granted. */
    fun screenOff(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isAdminActive(adminComponent(ctx))) return false
        dpm.lockNow()
        return true
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i(TAG, "receiver: ${intent.action}")
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Engine.sync(ctx)
                // Frameo (and others) launch themselves on boot; take the screen back.
                Engine.armBootFront(ctx, 20_000)
                Engine.armBootFront(ctx, 60_000)
            }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED ->
                Engine.sync(ctx, bringToFront = false)
            else -> Engine.sync(ctx)
        }
    }
}

class AdminReceiver : DeviceAdminReceiver()
