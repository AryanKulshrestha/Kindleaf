package com.kindleaf

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.kindleaf.data.NotificationSettingsStore
import com.kindleaf.data.SupabaseHelper
import com.kindleaf.notifications.OrderNotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KindleafApplication : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        OrderNotificationHelper.ensureChannel(this)
        if (SupabaseHelper.isConfigured) {
            SupabaseHelper.client
            Log.i(TAG, "Supabase connected: ${SupabaseHelper.url}")
            appScope.launch {
                runCatching { NotificationSettingsStore.refresh() }
                    .onFailure { Log.w(TAG, "Could not load notification settings: ${it.message}") }
            }
        } else {
            Log.w(TAG, "Supabase is not configured. Check the project .env file.")
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context).build()
    }

    companion object {
        private const val TAG = "Kindleaf"
    }
}
