package io.github.eladimany.spindle.ui.shuffle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.eladimany.spindle.data.prefs.SettingsRepository
import io.github.eladimany.spindle.playback.SmartShuffleRules
import io.github.eladimany.spindle.playback.SmartShuffler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmartShuffleViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val smartShuffler: SmartShuffler,
) : ViewModel() {

    val rules: StateFlow<SmartShuffleRules> =
        settings.smartShuffleRules.stateIn(viewModelScope, SharingStarted.Eagerly, SmartShuffleRules())

    private val _listenCount = MutableStateFlow<Int?>(null)
    val listenCount: StateFlow<Int?> = _listenCount.asStateFlow()

    /** Re-read each time the sheet opens — listens are written as songs end. */
    fun refreshListenCount() {
        viewModelScope.launch { _listenCount.value = smartShuffler.listenCount() }
    }

    // Applies from the next smart shuffle; the queue already playing isn't reordered.
    fun setRules(rules: SmartShuffleRules) {
        viewModelScope.launch { settings.setSmartShuffleRules(rules) }
    }
}
