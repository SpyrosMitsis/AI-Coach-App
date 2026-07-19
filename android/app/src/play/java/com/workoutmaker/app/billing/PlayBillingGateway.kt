package com.workoutmaker.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.workoutmaker.app.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

// Real Play Billing implementation (play flavor). One connection, reconnected
// lazily; purchases resolve through a one-shot listener because the Billing
// library reports results via a global callback, not per-call.
@Singleton
class PlayBillingGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) : BillingGateway, PurchasesUpdatedListener {

    override val supported = true

    // Set for the duration of one purchase() call.
    @Volatile
    private var purchaseWaiter: ((String?) -> Unit)? = null

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val token = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            purchases?.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                ?.purchaseToken
        } else {
            AppLog.w("billing", "purchase flow ended: ${result.responseCode} ${result.debugMessage}")
            null
        }
        purchaseWaiter?.invoke(token)
        purchaseWaiter = null
    }

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    // Lazy reconnect on the next call.
                }
            })
        }
    }

    private suspend fun proProductDetails(): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, details ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppLog.w("billing", "product query failed: ${result.debugMessage}")
                }
                cont.resume(details.firstOrNull())
            }
        }
    }

    override suspend fun purchase(activity: Activity, obfuscatedUserId: String): String? {
        if (!connect()) return null
        val product = proProductDetails() ?: return null
        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return null

        return suspendCancellableCoroutine { cont ->
            purchaseWaiter = { token -> if (cont.isActive) cont.resume(token) }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .setOfferToken(offerToken)
                            .build(),
                    ),
                )
                // Ties the purchase to this account server-side; verify-purchase
                // rejects tokens replayed from a different account.
                .setObfuscatedAccountId(obfuscatedUserId)
                .build()
            val launch = client.launchBillingFlow(activity, params)
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseWaiter = null
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { purchaseWaiter = null }
        }
    }

    override val tipsSupported = true

    // One query for all three tips, so the Support section fills in with a
    // single round trip. formattedPrice is already localized by Play.
    override suspend fun tipPrices(): Map<String, String> {
        if (!connect()) return emptyMap()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                TIP_PRODUCT_IDS.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, details ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppLog.w("billing", "tip price query failed: ${result.debugMessage}")
                }
                cont.resume(
                    details.mapNotNull { d ->
                        d.oneTimePurchaseOfferDetails?.formattedPrice?.let { d.productId to it }
                    }.toMap(),
                )
            }
        }
    }

    private suspend fun tipProductDetails(productId: String): ProductDetails? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, details ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppLog.w("billing", "tip product query failed: ${result.debugMessage}")
                }
                cont.resume(details.firstOrNull())
            }
        }
    }

    private suspend fun consume(purchaseToken: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val params = com.android.billingclient.api.ConsumeParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()
            client.consumeAsync(params) { result, _ ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppLog.w("billing", "consume failed: ${result.debugMessage}")
                }
                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }
        }

    // A tip whose consume failed (crash, offline) would auto-refund after 3
    // days; sweep and consume any lingering tip purchases before a new flow.
    private suspend fun consumePendingTips() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val pending: List<Purchase> = suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (!cont.isActive) return@queryPurchasesAsync
                cont.resume(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) purchases else emptyList(),
                )
            }
        }
        pending
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .filter { it.products.any { p -> p in TIP_PRODUCT_IDS } }
            .forEach { runCatching { consume(it.purchaseToken) } }
    }

    override suspend fun tip(activity: Activity, productId: String): Boolean {
        if (!connect()) return false
        consumePendingTips()
        val product = tipProductDetails(productId) ?: return false

        val token = suspendCancellableCoroutine<String?> { cont ->
            purchaseWaiter = { t -> if (cont.isActive) cont.resume(t) }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(product)
                            .build(),
                    ),
                )
                .build()
            val launch = client.launchBillingFlow(activity, params)
            if (launch.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseWaiter = null
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { purchaseWaiter = null }
        } ?: return false

        // Consume = acknowledge for one-time products, and makes it repeatable.
        runCatching { consume(token) }
        return true
    }

    override suspend fun currentPurchaseToken(): String? {
        if (!connect()) return null
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        return suspendCancellableCoroutine { cont ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (!cont.isActive) return@queryPurchasesAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resume(null)
                    return@queryPurchasesAsync
                }
                cont.resume(
                    purchases.firstOrNull {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            it.products.contains(PRO_PRODUCT_ID)
                    }?.purchaseToken,
                )
            }
        }
    }
}
