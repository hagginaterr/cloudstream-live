package recloudstream.twitchlivefavorites

import com.lagradost.cloudstream3.APIHolder

object BuiltInTwitchLiveFavorites {
    private const val SOURCE_PLUGIN = "built-in:twitch-live-favorites"

    fun register() {
        val alreadyRegistered = APIHolder.allProviders.any {
            it.name == "Twitch Live Favorites API" ||
                it::class.qualifiedName == TwitchApiLiveFavoritesProvider::class.qualifiedName
        }

        if (alreadyRegistered) return

        val provider = TwitchApiLiveFavoritesProvider().apply {
            sourcePlugin = SOURCE_PLUGIN
        }

        APIHolder.allProviders.add(provider)
        APIHolder.addPluginMapping(provider)
    }
}
