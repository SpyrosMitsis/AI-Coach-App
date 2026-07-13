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
}

// The single subscription product; must match the Play Console product id.
const val PRO_PRODUCT_ID = "pro"
