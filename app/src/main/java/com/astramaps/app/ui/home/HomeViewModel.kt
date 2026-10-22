package com.astramaps.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.astramaps.app.data.local.PackDao
import com.astramaps.app.data.map.LocalPack
import com.astramaps.app.data.map.PackLocator
import com.astramaps.app.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    packDao: PackDao,
    packLocator: PackLocator,
) : ViewModel() {

    val installedPackIds: StateFlow<List<String>> = packDao
        .allPacks()
        .map { packs -> packs.map { it.packId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * The pack the map draws, or `null` while none is installed.
     *
     * Scanning the packs directories touches the filesystem, so it happens on
     * the IO dispatcher inside [PackLocator] rather than during composition.
     */
    private val _mapPack = MutableStateFlow<LocalPack?>(null)
    val mapPack: StateFlow<LocalPack?> = _mapPack.asStateFlow()

    init {
        viewModelScope.launch { _mapPack.value = packLocator.find() }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return HomeViewModel(
                        packDao = container.database.packDao(),
                        packLocator = container.packLocator,
                    ) as T
                }
            }
    }
}
