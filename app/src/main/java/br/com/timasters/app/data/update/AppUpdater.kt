package br.com.timasters.app.data.update

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import br.com.timasters.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * In-app updates for the test channel: asks Supabase for the newest release, downloads the APK
 * from the private "app-releases" bucket, checks its SHA-256 and hands it to Android's installer.
 * State is app-wide so the dialog and the Settings screen show the same thing.
 */
@Singleton
class AppUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val supabase: SupabaseClient,
    httpClient: OkHttpClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloadClient = httpClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val updatesDir get() = File(context.cacheDir, UPDATES_DIR)

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var lastCheckMillis = 0L
    private var downloadJob: Job? = null

    val installedVersionCode: Int = BuildConfig.VERSION_CODE
    val installedVersionName: String = BuildConfig.VERSION_NAME

    /** Off in Play Store builds and when Google Play installed the app (Play updates it). */
    val isSupported: Boolean = BuildConfig.SELF_UPDATE && installerPackage() != PLAY_STORE

    /** On app start/foreground; at most once per [AUTO_CHECK_INTERVAL_MS]. Failures are silent. */
    fun checkAutomatically() {
        if (!isSupported) return
        val now = System.currentTimeMillis()
        if (lastCheckMillis != 0L && now - lastCheckMillis < AUTO_CHECK_INTERVAL_MS) return
        lastCheckMillis = now
        scope.launch {
            runCatching { fetchLatest() }.onSuccess { publish(it) }
            deleteOldDownloads()
        }
    }

    /** "Procurar atualização" in Settings: always asks the server and reopens a postponed dialog. */
    fun checkNow() {
        if (!isSupported || _state.value.manualCheck == ManualCheckResult.CHECKING) return
        _state.update { it.copy(manualCheck = ManualCheckResult.CHECKING, postponedVersionCode = null) }
        scope.launch {
            val result = try {
                fetchLatest()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(manualCheck = ManualCheckResult.FAILED) }
                return@launch
            }
            lastCheckMillis = System.currentTimeMillis()
            publish(result)
            _state.update { it.copy(manualCheck = if (result != null) ManualCheckResult.FOUND else ManualCheckResult.UP_TO_DATE) }
        }
    }

    fun postpone() {
        val release = _state.value.available ?: return
        if (release.isMandatoryFor(installedVersionCode)) return
        downloadJob?.cancel()
        _state.update { it.copy(postponedVersionCode = release.versionCode, phase = UpdatePhase.Idle) }
    }

    /** "Atualizar": asks for the install permission if needed, then downloads and verifies. */
    fun startUpdate() {
        val release = _state.value.available ?: return
        if (!canInstallPackages()) {
            _state.update { it.copy(phase = UpdatePhase.NeedsPermission) }
            return
        }
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            _state.update { it.copy(phase = UpdatePhase.Downloading(0f)) }
            val phase = try {
                UpdatePhase.ReadyToInstall(download(release).path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: CorruptedDownloadException) {
                UpdatePhase.Failed(UpdateFailure.CORRUPTED)
            } catch (_: StorageException) {
                UpdatePhase.Failed(UpdateFailure.STORAGE)
            } catch (_: Exception) {
                UpdatePhase.Failed(UpdateFailure.NETWORK)
            }
            _state.update { it.copy(phase = phase) }
        }
    }

    /** Back from the "install unknown apps" settings screen: continue if it was allowed. */
    fun onReturnedToApp() {
        if (_state.value.phase == UpdatePhase.NeedsPermission && canInstallPackages()) startUpdate()
    }

    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's per-app "Instalar apps desconhecidos" switch for TI Masters. */
    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /** Opens Android's installer; it keeps the app data because the signing key is the same. */
    fun installIntent(apkPath: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(apkPath))
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private suspend fun fetchLatest(): AppRelease? = supabase.postgrest.from(RELEASES_TABLE)
        .select {
            order("version_code", Order.DESCENDING)
            limit(1)
        }
        .decodeList<AppRelease>()
        .firstOrNull()
        ?.takeIf { it.isNewerThan(installedVersionCode) }

    private fun publish(release: AppRelease?) {
        _state.update { current ->
            if (current.available?.versionCode == release?.versionCode) current
            else current.copy(available = release, phase = UpdatePhase.Idle)
        }
    }

    private suspend fun download(release: AppRelease): File {
        val target = File(updatesDir, "TI-Masters-${release.versionName}.apk")
        // Already downloaded earlier (the user canceled the installer): reuse it.
        if (withContext(Dispatchers.IO) { target.exists() && sha256Of(target) == release.sha256 }) return target

        val url = supabase.storage.from(BUCKET).createSignedUrl(release.apkPath, 15.minutes)
        return withContext(Dispatchers.IO) {
            updatesDir.mkdirs()
            val partial = File(updatesDir, target.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                try {
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastShown = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                done += read
                                val percent = (done * 100 / total).toInt().coerceAtMost(100)
                                if (percent != lastShown) {
                                    lastShown = percent
                                    _state.update { it.copy(phase = UpdatePhase.Downloading(percent / 100f)) }
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    partial.delete()
                    if (updatesDir.usableSpace < release.sizeBytes) throw StorageException()
                    throw e
                }
            }
            if (digest.digest().toHex() != release.sha256) {
                partial.delete()
                throw CorruptedDownloadException()
            }
            target.delete()
            if (!partial.renameTo(target)) throw StorageException()
            target
        }
    }

    /** Files of versions already installed (or older) are no longer needed. */
    private suspend fun deleteOldDownloads() = withContext(Dispatchers.IO) {
        val keep = _state.value.available?.let { "TI-Masters-${it.versionName}.apk" }
        updatesDir.listFiles()?.filter { it.name != keep }?.forEach { it.delete() }
    }

    private fun installerPackage(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    private class CorruptedDownloadException : IOException()
    private class StorageException : IOException()

    companion object {
        private const val RELEASES_TABLE = "app_releases"
        private const val BUCKET = "app-releases"
        private const val UPDATES_DIR = "updates"
        private const val PLAY_STORE = "com.android.vending"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val AUTO_CHECK_INTERVAL_MS = 60 * 60 * 1000L

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
