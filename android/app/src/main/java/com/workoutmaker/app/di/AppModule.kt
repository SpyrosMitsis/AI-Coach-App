package com.workoutmaker.app.di

import com.workoutmaker.app.data.BackendConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSupabase(backend: BackendConfig): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = backend.url,
            supabaseKey = backend.anonKey,
        ) {
            // Multi-week block planning (plan-block) can run for a while; give
            // function calls plenty of headroom over the ~10s default.
            requestTimeout = 150.seconds
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Functions)
        }
}
