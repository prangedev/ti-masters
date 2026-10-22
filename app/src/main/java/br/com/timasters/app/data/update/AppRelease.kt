package br.com.timasters.app.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One published version (table `app_releases`, written by the GitHub release workflow). */
@Serializable
data class AppRelease(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val notes: String = "",
    @SerialName("apk_path") val apkPath: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("min_supported_code") val minSupportedCode: Int = 0
) {
    fun isNewerThan(installedCode: Int): Boolean = versionCode > installedCode

    /** Installed versions below [minSupportedCode] cannot postpone the update. */
    fun isMandatoryFor(installedCode: Int): Boolean = installedCode < minSupportedCode
}

/** Where the update stands; drives the update dialog. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    /** Android needs the user's OK to install apps from TI Masters (one-time setting). */
    data object NeedsPermission : UpdatePhase
    data class Downloading(val progress: Float) : UpdatePhase
    /** Downloaded and verified; the Android installer was (or can be) opened. */
    data class ReadyToInstall(val apkPath: String) : UpdatePhase
    data class Failed(val reason: UpdateFailure) : UpdatePhase
}

enum class UpdateFailure { NETWORK, CORRUPTED, STORAGE }

/** Result of the last "Procurar atualização" tap, shown in Settings. */
enum class ManualCheckResult { CHECKING, UP_TO_DATE, FOUND, FAILED }

data class UpdateState(
    val available: AppRelease? = null,
    /** "Depois" hides the dialog until the next app start (or a newer version). */
    val postponedVersionCode: Int? = null,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val manualCheck: ManualCheckResult? = null
) {
    fun showDialog(installedCode: Int): Boolean {
        val release = available ?: return false
        return release.isMandatoryFor(installedCode) || postponedVersionCode != release.versionCode
    }
}
