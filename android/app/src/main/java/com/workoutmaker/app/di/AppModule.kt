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
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // httpConfig is @SupabaseInternal, but it is the only seam that reaches the
    // underlying Ktor client — and supabase-kt's own timeout knob (requestTimeout)
    // provably cannot set the socket timeout we need. See the comment below.
    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    @Provides
    @Singleton
    fun provideSupabase(backend: BackendConfig): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = backend.url,
            supabaseKey = backend.anonKey,
        ) {
            // A week's worth of AI planning (plan-week) can run for a while; give
            // function calls plenty of headroom over the ~10s default.
            requestTimeout = 150.seconds
            // requestTimeout alone is NOT enough: supabase-kt forwards it only to
            // HttpTimeout.requestTimeoutMillis and never sets socketTimeoutMillis,
            // so Ktor leaves OkHttp's 10s readTimeout in force and every coach turn
            // that thinks for >10s dies with "Socket timeout has expired
            // [socket_timeout=unknown]". functions.invoke sends no bytes until the
            // whole turn is done, so the socket MUST be allowed to stay quiet at
            // least as long as requestTimeout, which stays the real bound. A dead
            // network still fails fast on connect.
            httpConfig {
                install(HttpTimeout) {
                    socketTimeoutMillis = 150.seconds.inWholeMilliseconds
                    connectTimeoutMillis = 15.seconds.inWholeMilliseconds
                }
            }
            install(Auth) {
                // Email links (confirmation, password recovery) deep-link back
                // into the app as workoutmaker://auth — MainActivity feeds them
                // to handleDeeplinks, which imports the session.
                scheme = "workoutmaker"
                host = "auth"
            }
            install(Postgrest)
            install(Realtime)
            install(Functions)
        }
}
