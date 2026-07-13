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
    override suspend fun purchase(activity: Activity, obfuscatedUserId: String): String? = null
    override suspend fun currentPurchaseToken(): String? = null
}

@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    @Provides
    @Singleton
    fun provideBillingGateway(): BillingGateway = NoBilling
}
