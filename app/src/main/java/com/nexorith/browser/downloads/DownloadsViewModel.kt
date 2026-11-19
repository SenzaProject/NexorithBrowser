package com.nexorith.browser.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow

/** Thin layer between the downloads screen and [DownloadCenter]. */
class DownloadsViewModel(private val center: DownloadCenter) : ViewModel() {

    val items: StateFlow<List<DownloadItem>> = center.items

    fun pause(id: Long) = center.pause(id)
    fun resume(id: Long) = center.resume(id)
    fun cancel(id: Long) = center.cancel(id)
    fun retry(id: Long) = center.retry(id)
    fun remove(id: Long, deleteFile: Boolean) = center.remove(id, deleteFile)
    fun clearFinished() = center.clearFinished()

    companion object {
        fun factory(center: DownloadCenter): ViewModelProvider.Factory = viewModelFactory {
            initializer { DownloadsViewModel(center) }
        }
    }
}
