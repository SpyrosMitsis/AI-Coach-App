package com.workoutmaker.app.billing

import android.app.Activity
import com.workoutmaker.app.data.WorkoutRepository

// One purchase story, shared by Settings and onboarding: launch the Play flow
// tagged with the buyer's user id, then have the server verify + flip the plan.
sealed interface ProPurchaseResult {
    data class Success(val plan: String) : ProPurchaseResult
    // User backed out of the Play sheet; not an error, show nothing.
    data object Cancelled : ProPurchaseResult
    data class Failed(val message: String) : ProPurchaseResult
}

suspend fun purchaseAndVerify(
    activity: Activity,
    billing: BillingGateway,
    repo: WorkoutRepository,
): ProPurchaseResult {
    val userId = repo.auth.currentUserOrNull()?.id
        ?: return ProPurchaseResult.Failed("Not signed in.")
    val token = runCatching { billing.purchase(activity, userId) }
        .getOrElse { return ProPurchaseResult.Failed(it.message ?: "Purchase failed. Try again.") }
        ?: return ProPurchaseResult.Cancelled
    return runCatching { repo.verifyPurchase(token) }
        .fold(
            onSuccess = { ProPurchaseResult.Success(it) },
            onFailure = { ProPurchaseResult.Failed(it.message ?: "Could not verify the purchase. Try Restore purchase in Settings.") },
        )
}
