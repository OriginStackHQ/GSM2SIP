package com.callagent.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The gateway's own number, for one subscription.
 *
 * Both directions of the bridge have to agree on this value: CallOrchestrator
 * uses it to address an inbound call, GatewayService uses it to address an
 * SMS.  A message and a call that disagree about which SIM they were on would
 * be mapped to two different assistants by the same server, so the lookup is
 * written once here rather than twice and left to drift.
 *
 * The platform-published number wins when there is one: it is what the network
 * actually knows, and a configured value that is wrong but still valid would
 * otherwise send inbound calls to the wrong assistant.  Many carriers publish
 * no number at all, which is why the configured per-SIM value exists as the
 * fallback.
 */
object OwnNumber {

    private const val TAG = "OwnNumber"

    /** Active subscriptions, or an empty list when phone-state access is absent. */
    fun activeSubscriptions(context: Context): List<SubscriptionInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE is not granted; cannot enumerate subscriptions")
            return emptyList()
        }
        return runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                .orEmpty()
        }.getOrElse {
            Log.w(TAG, "Could not enumerate subscriptions: ${it.message}")
            emptyList()
        }
    }

    /** SIM slot for a subscription, or null when it cannot be queried. */
    fun simSlotForSubscription(context: Context, subId: Int): Int? {
        val subscription = if (isValidSubscription(subId)) {
            subId
        } else {
            SubscriptionManager.getDefaultSubscriptionId()
        }
        if (!isValidSubscription(subscription) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subscription)
                ?.simSlotIndex
        }.getOrNull()
    }

    /**
     * The MSISDN the platform publishes for [subId], or null when it publishes
     * none — which most carriers do not, and which is why this can only ever
     * be one of the sources.
     *
     * On Android 13 and above [SubscriptionManager.getPhoneNumber] takes the
     * subscription directly.  Below that the only accessor is
     * `TelephonyManager.line1Number`, and the handle returned by
     * `getSystemService(TELEPHONY_SERVICE)` is bound to the **default**
     * subscription: it answers with SIM 1's number for a call that arrived on
     * SIM 2.  [TelephonyManager.createForSubscriptionId] rebinds the handle to
     * the subscription the call actually came in on.
     *
     * [subId] of [SubscriptionManager.INVALID_SUBSCRIPTION_ID] means the caller
     * had no SIM to name, which keeps the single-SIM behaviour of asking about
     * the default subscription.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun fromSim(context: Context, subId: Int): String? = try {
        val number = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val subMgr = context.getSystemService(SubscriptionManager::class.java)
            if (isValidSubscription(subId)) subMgr?.getPhoneNumber(subId)
            else subMgr?.getPhoneNumber(SubscriptionManager.getDefaultSubscriptionId())
        } else {
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val scoped = if (isValidSubscription(subId)) {
                runCatching { base.createForSubscriptionId(subId) }
                    .onFailure { Log.w(TAG, "createForSubscriptionId($subId) failed; no SIM number: ${it.message}") }
                    .getOrNull()
                    ?: return null
            } else base
            scoped.line1Number
        }
        number?.let { toE164(context, it, subId) }
    } catch (e: Exception) {
        Log.w(TAG, "SIM number unavailable: ${e.message}")
        null
    }

    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

    fun isE164(number: String): Boolean = E164.matches(number.trim())

    /**
     * Convert national, spaced, 00-prefixed, or already international input to
     * strict +E.164 using the selected SIM's country. Returns null rather than
     * forwarding an ambiguous value when the platform cannot normalize it.
     */
    fun toE164(
        context: Context,
        number: String,
        subId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    ): String? {
        val trimmed = number.trim().filterNot { it in " -/()" }
        if (isE164(trimmed)) return trimmed
        val international = if (trimmed.startsWith("00") && trimmed.length > 4) {
            "+${trimmed.drop(2).filter(Char::isDigit)}"
        } else {
            trimmed
        }
        if (isE164(international)) return international
        if (trimmed.isEmpty() || trimmed.any { !it.isDigit() && it != '+' }) return null

        val iso = runCatching {
            val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val scoped = if (isValidSubscription(subId)) {
                runCatching { base.createForSubscriptionId(subId) }
                    .onFailure { Log.w(TAG, "createForSubscriptionId($subId) failed; no country ISO: ${it.message}") }
                    .getOrNull()
                    ?: return@runCatching null
            } else {
                base
            }
            scoped.simCountryIso?.uppercase()?.ifEmpty { null }
        }.getOrNull()
        if (iso == null) return null
        return PhoneNumberUtils.formatNumberToE164(trimmed, iso)
            ?.takeIf(::isE164)
    }

    /** The configured per-SIM number, normalized, or null when unset/invalid. */
    private fun configuredNumber(context: Context, subId: Int): String? {
        if (!isValidSubscription(subId)) return null
        val slot = simSlotForSubscription(context, subId) ?: return null
        return prefs(context).getString("own_number_slot_$slot", "")
            ?.takeIf { it.isNotBlank() }
            ?.let { toE164(context, it, subId) }
            ?.takeIf(::isE164)
    }

    /**
     * The number this gateway addresses traffic with: the platform value first,
     * then the configured per-SIM value.
     *
     * The platform value is authoritative when present.  A configured value
     * that is wrong but still valid would otherwise misroute inbound calls,
     * so the setting is a fallback for carriers that publish no number.
     *
     * The configured value is keyed by slot, matching the settings field.
     */
    @SuppressLint("MissingPermission")
    fun sourceNumberForSubscriptionId(context: Context, subId: Int): String? {
        if (!isValidSubscription(subId)) return null
        return fromSim(context, subId)?.takeIf(::isE164) ?: configuredNumber(context, subId)
    }

    /**
     * Every number a SIM can be recognised by: the platform value and the
     * configured value.
     *
     * Addressing uses the platform number when it exists and falls back to the
     * configured value otherwise.  Matching still has to accept either: an
     * operator may address the SIM by the configured number, while outbound
     * headers and Telecom account addresses carry the platform number.
     */
    private fun matchCandidates(context: Context, subId: Int): List<String> =
        listOfNotNull(
            fromSim(context, subId)?.takeIf(::isE164),
            configuredNumber(context, subId),
        ).distinct()

    /** Match a SIM by any number it is known by (configured or platform). */
    fun subscriptionIdForNumber(context: Context, number: String): Int? =
        matchSubscriptions(context, number) { subId -> matchCandidates(context, subId) }

    /**
     * Resolve an unknown subId only when exactly one SIM is active.
     *
     * A multi-SIM gateway must never guess: naming the wrong SIM routes a call
     * or message to the wrong assistant.
     */
    fun resolveSubscriptionId(context: Context, subId: Int): Int {
        if (isValidSubscription(subId)) return subId
        val active = activeSubscriptions(context)
        return if (active.size == 1) active.single().subscriptionId
        else SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    /**
     * Normalize the target per candidate subscription, exactly as the old
     * lookup did: that is what lets a national-formatted `X-GSM-From` resolve
     * on dual-SIM, using each SIM's own country.
     */
    private fun matchSubscriptions(
        context: Context,
        number: String,
        candidates: (Int) -> List<String>,
    ): Int? {
        val matches = activeSubscriptions(context).filter { info ->
            val subId = info.subscriptionId
            val target = toE164(context, number, subId) ?: return@filter false
            candidates(subId).any { candidate ->
                val normalized = toE164(context, candidate, subId) ?: candidate
                numbersMatch(target, normalized)
            }
        }
        return when (matches.size) {
            1 -> matches.single().subscriptionId
            0 -> {
                Log.w(TAG, "No SIM matches source number $number")
                null
            }
            else -> {
                Log.w(TAG, "Source number $number matches ${matches.size} SIMs")
                null
            }
        }
    }

    private fun numbersMatch(target: String, candidate: String?): Boolean {
        if (candidate == null) return false
        // Two E.164 numbers match only exactly.  The suffix rules below exist
        // for national-formatted input, and applying them to E.164 would let
        // +4915112345678 and +491511234567 match.
        if (isE164(target) && isE164(candidate)) return target == candidate
        val targetDigits = target.filter(Char::isDigit)
        val candidateDigits = candidate.filter(Char::isDigit)
        val targetNational = targetDigits.trimStart('0').ifEmpty { targetDigits }
        val candidateNational = candidateDigits.trimStart('0').ifEmpty { candidateDigits }
        return targetDigits.isNotEmpty() && candidateDigits.isNotEmpty() &&
            (targetDigits == candidateDigits ||
                candidateDigits == targetNational ||
                targetDigits == candidateNational ||
                targetDigits.endsWith(candidateNational) ||
                candidateDigits.endsWith(targetNational))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    /**
     * Whether [subId] identifies a subscription that can be queried.
     *
     * The invalid and negative sentinel values mean "ask about the default
     * subscription" rather than naming a specific SIM.
     */
    fun isValidSubscription(subId: Int): Boolean =
        subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            SubscriptionManager.isValidSubscriptionId(subId)
}
