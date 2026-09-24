package io.github.eladimany.spindle.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.eladimany.spindle.playback.SmartShuffleRules
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

    // The one-time "keep playing with the screen off" sheet, shown on the first
    // switch to a Node while battery use is still restricted.
    private val batterySetupShownKey = booleanPreferencesKey("battery_setup_shown")

    val batterySetupShown: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[batterySetupShownKey] ?: false
    }

    suspend fun markBatterySetupShown() {
        context.dataStore.edit { prefs -> prefs[batterySetupShownKey] = true }
    }

    private val spreadArtistsKey = booleanPreferencesKey("smart_shuffle_spread_artists")
    private val favouritesKey = booleanPreferencesKey("smart_shuffle_favourites")
    private val holdBackSkippedKey = booleanPreferencesKey("smart_shuffle_hold_back_skipped")
    private val rediscoverKey = booleanPreferencesKey("smart_shuffle_rediscover")
    private val boostedRuleKey = booleanPreferencesKey("smart_shuffle_boosted")
    private val boostedTrackKeysKey = stringSetPreferencesKey("boosted_track_keys")

    val smartShuffleRules: Flow<SmartShuffleRules> = context.dataStore.data.map { prefs ->
        SmartShuffleRules(
            spreadArtists = prefs[spreadArtistsKey] ?: true,
            favourites = prefs[favouritesKey] ?: true,
            holdBackSkipped = prefs[holdBackSkippedKey] ?: true,
            rediscover = prefs[rediscoverKey] ?: true,
            boosted = prefs[boostedRuleKey] ?: true,
        )
    }

    suspend fun setSmartShuffleRules(rules: SmartShuffleRules) {
        context.dataStore.edit { prefs ->
            prefs[spreadArtistsKey] = rules.spreadArtists
            prefs[favouritesKey] = rules.favourites
            prefs[holdBackSkippedKey] = rules.holdBackSkipped
            prefs[rediscoverKey] = rules.rediscover
            prefs[boostedRuleKey] = rules.boosted
        }
    }

    /** History [io.github.eladimany.spindle.data.history.trackKey]s of tracks swiped right
     * in the queue — a handful of numbers, so DataStore rather than a table. */
    val boostedTrackKeys: Flow<Set<Long>> = context.dataStore.data.map { prefs ->
        prefs[boostedTrackKeysKey].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    }

    suspend fun setBoosted(trackKey: Long, boosted: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[boostedTrackKeysKey].orEmpty()
            prefs[boostedTrackKeysKey] = if (boosted) current + trackKey.toString() else current - trackKey.toString()
        }
    }

    suspend fun setFolderExcluded(folderId: Long, excluded: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[excludedFolderIdsKey]?.toMutableSet() ?: mutableSetOf()
            if (excluded) current += folderId.toString() else current -= folderId.toString()
            prefs[excludedFolderIdsKey] = current
        }
    }
}
