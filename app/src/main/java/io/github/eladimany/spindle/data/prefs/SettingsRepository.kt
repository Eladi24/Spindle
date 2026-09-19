package io.github.eladimany.spindle.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val excludedFolderIdsKey = stringSetPreferencesKey("excluded_folder_ids")

    val excludedFolderIds: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        prefs[excludedFolderIdsKey]?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }

    suspend fun setFolderExcluded(folderId: Long, excluded: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[excludedFolderIdsKey]?.toMutableSet() ?: mutableSetOf()
            if (excluded) current += folderId.toString() else current -= folderId.toString()
            prefs[excludedFolderIdsKey] = current
        }
    }
}
