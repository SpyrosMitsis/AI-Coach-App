package com.workoutmaker.app.billing

import android.app.Activity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// foss flavor: no Google Play code at all. supported=false hides every Pro
// surface; BYO keys (the free tier) is the whole product here.
private object NoBilling : BillingGateway {
    override val supported = false
    override val tipsSupported = false
    override suspend fun purchase(activity: Activity, obfuscatedUserId: String): String? = null
    override suspend fun currentPurchaseToken(): String? = null
    override suspend fun tip(activity: Activity, productId: String): Boolean = false

    // No Play to ask; this flavor's Support section takes the Ko-fi branch and
    // never reads these anyway.
    override suspend fun tipPrices(): Map<String, String> = emptyMap()
}

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    @Provides
    @Singleton
    fun provideBillingGateway(): BillingGateway = NoBilling
}
