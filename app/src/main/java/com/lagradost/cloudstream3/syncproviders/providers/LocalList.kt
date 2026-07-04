package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllFavorites
import com.lagradost.cloudstream3.utils.txt

class LocalList : SyncAPI() {
    override val name = "Local"
    override val idPrefix = "local"
    override val icon: Int = R.drawable.ic_baseline_favorite_24
    override val requiresLogin = false
    override val createAccountUrl = null
    override var requireLibraryRefresh = true
    override val syncIdName = SyncIdName.LocalList

    override suspend fun library(auth: AuthData?): SyncAPI.LibraryMetadata? {
        val favorites = ioWork {
            getAllFavorites().mapNotNull { it.toLibraryItem() }
        }

        return SyncAPI.LibraryMetadata(
            listOf(
                SyncAPI.LibraryList(
                    txt(R.string.favorites_list_name),
                    favorites,
                ),
            ),
            setOf(
                ListSorting.AlphabeticalA,
                ListSorting.AlphabeticalZ,
                ListSorting.UpdatedNew,
                ListSorting.UpdatedOld,
                ListSorting.ReleaseDateNew,
                ListSorting.ReleaseDateOld,
            ),
        )
    }
}