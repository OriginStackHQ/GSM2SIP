package com.callagent.gateway.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.callagent.gateway.R
import com.callagent.gateway.gsm.GsmCallManager
import com.callagent.gateway.service.GatewayService
import java.util.Locale

/** Owns the active-call card, call timer, mute, monitor, and end-call actions. */
class HomeCallController(
    private val activity: AppCompatActivity,
    private val appendLog: (String) -> Unit,
    private val renderTraffic: () -> Unit,
    private val ownNumber: () -> String,
) {
    private val card: View = activity.findViewById(R.id.homeCallCard)
    private val direction: TextView = activity.findViewById(R.id.tvHomeCallDirection)
    private val timer: TextView = activity.findViewById(R.id.tvHomeCallTimer)
    private val from: TextView = activity.findViewById(R.id.tvHomeCallFrom)
    private val to: TextView = activity.findViewById(R.id.tvHomeCallTo)
    private val statusPill: TextView = activity.findViewById(R.id.tvHomeStatusPill)
    private val tlsBadge: TextView = activity.findViewById(R.id.tvHomeTlsBadge)
    private val srtpBadge: TextView = activity.findViewById(R.id.tvHomeSrtpBadge)
    private val monitorBadge: TextView = activity.findViewById(R.id.tvHomeMonitorBadge)
    private val muteButton: Button = activity.findViewById(R.id.btnHomeMute)
    private val monitorButton: Button = activity.findViewById(R.id.btnHomeSnoop)
    private val endButton: Button = activity.findViewById(R.id.btnHomeEnd)
    private var liveCall = false

    private var muted = false
    private var monitoring = false
    private var online = false
    private var registered = false
    private var callStart = 0L
    private var serviceCallStart = 0L
    private var timerHandle = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (callStart <= 0) return
            val elapsedMs = System.currentTimeMillis() - callStart
            val elapsed = elapsedMs / 1000
            timer.text = if (elapsed >= 3600) {
                String.format(Locale.US, "%d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
            } else {
                String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
            }
            timerHandle.postDelayed(this, 1000 - (elapsedMs % 1000))
        }
    }

    fun bind() {
        muteButton.setOnClickListener { toggleMute() }
        monitorButton.setOnClickListener { toggleMonitor() }
        monitorBadge.setOnClickListener { toggleCallMonitor() }
        refreshMonitorBadge()
        endButton.setOnClickListener { endCall() }
    }

    fun isOnline(): Boolean = online

    fun isRegistered(): Boolean = registered

    fun onGatewayStatus(state: String, info: String, isRegistered: Boolean, callStartMillis: Long) {
        registered = isRegistered
        serviceCallStart = callStartMillis
        val isOnline = when (state) {
            "STOPPED", "ERROR", "STARTING" -> false
            "IDLE" -> isRegistered
            else -> true
        }
        online = isOnline
        // The globe (set in the layout) is the old Online/Offline pill as an
        // icon, so the header fits a 540px-wide screen (S4 Mini): green
        // registered, yellow still connecting or retrying, red stopped or
        // failed.  (Upstream 3581011, 1.4.1.)
        val connecting = !isOnline && (state == "STARTING" || state == "IDLE")
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            statusPill, ColorStateList.valueOf(Color.parseColor(
                when { isOnline -> "#34D399"; connecting -> "#FBBF24"; else -> "#F87171" }))
        )
        statusPill.contentDescription =
            when { isOnline -> "Online"; connecting -> "Connecting"; else -> "Offline" }
        val config = activity.getSharedPreferences("gateway", android.content.Context.MODE_PRIVATE)
        val tls = config.getBoolean("sip_tls", false)
        tlsBadge.visibility = if (tls) View.VISIBLE else View.GONE
        srtpBadge.visibility = if (tls && config.getBoolean("srtp_enabled", false)) View.VISIBLE else View.GONE
        refreshMonitorBadge()

        val callVisible = state == "BRIDGED" && GsmCallManager.isCallActive
        if (callVisible) {
            card.visibility = View.VISIBLE
            from.text = GsmCallManager.currentNumber ?: info
            val destination = ownNumber()
            to.text = if (destination.isNotEmpty()) "Connected to $destination" else "Connected"
            direction.text = "GSM → SIP"
            startTimer()
            // Call monitor: the service has already switched Snoop on for this
            // call (CallOrchestrator), so the button has to say Off from the
            // start.  (Upstream 87246af.)
            if (!liveCall && activity.getSharedPreferences("gateway", android.content.Context.MODE_PRIVATE)
                    .getBoolean("call_monitor", false)) {
                monitoring = true
                updateMonitorButton()
            }
            liveCall = true
        } else {
            card.visibility = View.GONE
            liveCall = false
            callStart = 0L
            timerHandle.removeCallbacks(timerTick)
            if (muted) {
                muted = false
                setButton(muteButton, "Mute", R.drawable.ic_fa_volume_xmark)
            }
            if (monitoring) {
                monitoring = false
                updateMonitorButton()
            }
            renderTraffic()
        }
    }

    fun resume() {
        if (callStart > 0) timerTick.run()
    }

    fun pause() {
        timerHandle.removeCallbacks(timerTick)
    }

    private fun startTimer() {
        callStart = if (serviceCallStart > 0) serviceCallStart else System.currentTimeMillis()
        timerHandle.removeCallbacks(timerTick)
        timerTick.run()
    }

    private fun toggleMute() {
        muted = !muted
        activity.startService(Intent(activity, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MUTE_AGENT
            putExtra(GatewayService.EXTRA_MUTE_ON, muted)
        })
        setButton(
            muteButton,
            if (muted) "Unmute" else "Mute",
            if (muted) R.drawable.ic_fa_volume_high else R.drawable.ic_fa_volume_xmark,
        )
        appendLog(if (muted) "Agent muted to caller" else "Agent unmuted")
    }

    private fun toggleMonitor() {
        monitoring = !monitoring
        activity.startService(Intent(activity, GatewayService::class.java).apply {
            action = GatewayService.ACTION_MONITOR
            putExtra(GatewayService.EXTRA_MONITOR_ON, monitoring)
        })
        updateMonitorButton()
        appendLog(if (monitoring) "Snoop on — both sides on the speaker" else "Snoop off")
    }

    private fun updateMonitorButton() {
        setButton(
            monitorButton,
            // "Off", not "Stop": it switches the speaker off, it does not
            // stop anything about the call.  (Upstream b8e53d5, 1.3.10.)
            if (monitoring) "Off" else "Snoop",
            if (monitoring) R.drawable.ic_fa_volume_xmark else R.drawable.ic_fa_headphones,
        )
    }

    /**
     * The Call monitor badge is always in the header and is the setting's
     * switch: red while every call is put on the speaker, grey while not —
     * the same value as Settings → Call monitor.  (Upstream 7316080/01d5786.)
     */
    private fun refreshMonitorBadge() {
        val on = activity.getSharedPreferences("gateway", android.content.Context.MODE_PRIVATE)
            .getBoolean("call_monitor", false)
        val tint = ContextCompat.getColor(
            activity, if (on) R.color.monitor_badge else R.color.text_secondary
        )
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
            monitorBadge, ColorStateList.valueOf(tint)
        )
        monitorBadge.contentDescription =
            if (on) "Call monitor on: every call plays on the speaker. Tap to turn off."
            else "Call monitor off. Tap to put every call on the speaker."
    }

    /** Badge tap: flip Call monitor, and take the call in progress with it. */
    private fun toggleCallMonitor() {
        val prefs = activity.getSharedPreferences("gateway", android.content.Context.MODE_PRIVATE)
        val on = !prefs.getBoolean("call_monitor", false)
        prefs.edit().putBoolean("call_monitor", on).apply()
        // Here, not in refreshMonitorBadge: that runs on every status update
        // and would undo an unsaved tick in the settings screen.
        activity.findViewById<android.widget.CheckBox>(R.id.cbCfgCallMonitor)?.isChecked = on
        refreshMonitorBadge()
        if (liveCall && monitoring != on) toggleMonitor()
        appendLog(if (on) "Call monitor on — every call on the speaker" else "Call monitor off")
        Toast.makeText(
            activity,
            if (on) "Call monitor on" else "Call monitor off",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun endCall() {
        if (GsmCallManager.activeCall != null) GsmCallManager.hangupCall()
    }

    private fun setButton(button: Button, label: String, icon: Int) {
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, icon, 0, 0)
    }
}
