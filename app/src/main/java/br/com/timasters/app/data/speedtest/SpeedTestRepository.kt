package br.com.timasters.app.data.speedtest

import br.com.timasters.app.data.network.ConnectionInfoRepository
import br.com.timasters.app.data.network.TransportType
import br.com.timasters.app.data.wifi.WifiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Speed test history, stored only on this phone for now. */
@Singleton
class SpeedTestRepository @Inject constructor(
    private val dao: SpeedTestDao,
    private val connectionInfo: ConnectionInfoRepository,
    private val wifiRepository: WifiRepository
) {
    val history: Flow<List<SpeedTestResult>> = dao.observeAll()

    suspend fun save(progress: SpeedTestProgress) = withContext(Dispatchers.IO) {
        val transport = connectionInfo.current().transport
        val server = progress.server
        dao.insert(
            SpeedTestResult(
                measuredAt = System.currentTimeMillis(),
                downloadMbps = progress.downloadMbps ?: 0.0,
                uploadMbps = progress.uploadMbps ?: 0.0,
                pingMs = progress.pingMs,
                jitterMs = progress.jitterMs,
                connectionType = transport.name,
                networkName = if (transport == TransportType.WIFI) wifiRepository.currentConnection()?.ssid else null,
                isp = server?.asOrganization,
                serverLocation = listOfNotNull(server?.city, server?.colo).joinToString(" · ").ifEmpty { null }
            )
        )
    }

    suspend fun clear() = dao.deleteAll()
}
