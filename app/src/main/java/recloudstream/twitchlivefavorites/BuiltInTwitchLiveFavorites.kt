package recloudstream.twitchlivefavorites

import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.DataStoreHelper

object BuiltInTwitchLiveFavorites {
    const val PROVIDER_NAME = "Twitch Live Favorites API"
    private const val SOURCE_PLUGIN = "built-in:twitch-live-favorites"
    private const val TAG = "BuiltInTwitch"

    fun register(forceHomepage: Boolean = false): MainAPI {
        val provider = APIHolder.allProviders.firstOrNull {
            it.name == PROVIDER_NAME ||
                it::class.qualifiedName == TwitchApiLiveFavoritesProvider::class.qualifiedName
        } ?: TwitchApiLiveFavoritesProvider().apply {
            sourcePlugin = SOURCE_PLUGIN
        }.also {
            APIHolder.allProviders.add(it)
            Log.i(TAG, "Added $PROVIDER_NAME to allProviders")
        }

        val alreadyActive = APIHolder.apis.any {
            it.name == PROVIDER_NAME ||
                it::class.qualifiedName == TwitchApiLiveFavoritesProvider::class.qualifiedName
        }

        if (!alreadyActive) {
            APIHolder.addPluginMapping(provider)
            Log.i(TAG, "Added $PROVIDER_NAME to active apis")
        }

        if (forceHomepage) {
            val current = DataStoreHelper.currentHomePage
            if (current.isNullOrBlank() || current == "None") {
                DataStoreHelper.currentHomePage = PROVIDER_NAME
                Log.i(TAG, "Set homepage to $PROVIDER_NAME")
            }
        }

        return provider
    }
}
