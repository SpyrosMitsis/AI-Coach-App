package com.workoutmaker.app.billing

import android.app.Activity

// Seam between the app and Google Play Billing so the foss flavor can ship a
// stub (no Google code at all). Each flavor's BillingModule binds its own
// implementation; everything above this interface is flavor-agnostic.
interface BillingGateway {
    // False on the foss flavor (and when Play itself is unavailable) — the
    // Settings Pro section hides entirely.
    val supported: Boolean

    // Launches the Play subscribe flow for the Pro subscription. Returns the
    // purchase token to send to verify-purchase, or null if the user backed out.
    suspend fun purchase(activity: Activity, obfuscatedUserId: String): String?

    // Most recent Pro purchase token Play knows for this account, if any —
    // used on resume to re-verify (e.g. purchase completed while offline,
    // or verify-purchase failed after a successful buy).
    suspend fun currentPurchaseToken(): String?

    // One-time tip products through Play Billing. False on the foss flavor,
    // where the Support section opens Ko-fi instead.
    val tipsSupported: Boolean

    // Launches the tip purchase flow and consumes the purchase so the same
    // tip can be bought again. Returns true when the purchase completed.
    // A tip grants nothing, so there is no server verification.
    suspend fun tip(activity: Activity, productId: String): Boolean

    // Play's own price per tip product id, already formatted and localized
    // ("$2.99", "2,79 €"). Empty on the foss flavor or when Play can't be
    // reached; callers fall back to TIP_FALLBACK_PRICES. Play Console is the
    // source of truth for what is actually charged, so a label built from this
    // can never disagree with the checkout sheet.
    suspend fun tipPrices(): Map<String, String>
}

// The single subscription product; must match the Play Console product id.
const val PRO_PRODUCT_ID = "pro"

// One-time tip products (Support the developer); must match Play Console.
val TIP_PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")

// Shown only until Play answers with the real localized prices (and on a build
// that can't ask it). Keep in step with the Play Console prices.
val TIP_FALLBACK_PRICES = mapOf(
    "tip_small" to "$2.99",
    "tip_medium" to "$9.99",
    "tip_large" to "$19.99",
)
