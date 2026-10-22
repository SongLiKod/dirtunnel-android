package com.dirtunnel.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.storage.data.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 外壳级 UI 设置（底部导航栏不透明度等，随 DataStore 实时生效）。 */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val settings: SettingsStore,
) : ViewModel() {

    val navBarOpacity: StateFlow<Float> = settings.settings
        .map { it.navBarOpacity }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.9f)

    fun setNavBarOpacity(value: Float) {
        viewModelScope.launch { settings.setNavBarOpacity(value) }
    }
}
