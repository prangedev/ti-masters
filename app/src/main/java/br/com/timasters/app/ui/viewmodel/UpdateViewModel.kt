package br.com.timasters.app.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import br.com.timasters.app.data.update.AppUpdater
import br.com.timasters.app.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Thin UI access to the app-wide [AppUpdater] (used by the update dialog and Settings). */
@HiltViewModel
class UpdateViewModel @Inject constructor(private val updater: AppUpdater) : ViewModel() {

    val state: StateFlow<UpdateState> = updater.state
    val isSupported: Boolean = updater.isSupported
    val installedVersionCode: Int = updater.installedVersionCode
    val installedVersionName: String = updater.installedVersionName

    fun checkAutomatically() = updater.checkAutomatically()
    fun checkNow() = updater.checkNow()
    fun postpone() = updater.postpone()
    fun startUpdate() = updater.startUpdate()
    fun onReturnedToApp() = updater.onReturnedToApp()
    fun installPermissionIntent(): Intent = updater.installPermissionIntent()
    fun installIntent(apkPath: String): Intent = updater.installIntent(apkPath)
}
