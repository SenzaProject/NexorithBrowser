package com.nexorith.browser.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nexorith.browser.data.AppInfo
import com.nexorith.browser.data.AppInfoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeUiState(val appInfo: AppInfo)

class HomeViewModel(repository: AppInfoRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(repository.getAppInfo()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    companion object {
        fun factory(repository: AppInfoRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
