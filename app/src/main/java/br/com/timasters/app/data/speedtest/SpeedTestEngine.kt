package br.com.timasters.app.data.speedtest

import br.com.timasters.app.core.network.LatencyStats
import br.com.timasters.app.data.network.CloudflareSpeedApi
import br.com.timasters.app.data.network.CloudflareSpeedEndpoints
import br.com.timasters.app.data.network.PublicNetworkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

enum class SpeedTestPhase { CONNECTING, LATENCY, DOWNLOAD, UPLOAD, DONE }

data class SpeedTestProgress(
    val phase: SpeedTestPhase = SpeedTestPhase.CONNECTING,
    /** 0..1 within the current phase. */
    val phaseProgress: Float = 0f,
    /** Throughput of the last second while downloading/uploading. */
    val liveMbps: Double? = null,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val server: PublicNetworkInfo? = null
)

/**
 * Measures latency, download and upload against Cloudflare's nearest data center using several
 * parallel HTTP streams, like most browser speed tests. Each transfer phase lasts up to
 * [TRANSFER_DURATION_NANOS] or stops early after [MAX_DOWNLOAD_BYTES]/[MAX_UPLOAD_BYTES] to limit
 * mobile data use. The first [WARMUP_NANOS] of each phase are ignored (TCP slow start).
 */
@Singleton
class SpeedTestEngine @Inject constructor(
    private val client: OkHttpClient,
    private val cloudflare: CloudflareSpeedApi
) {
    fun run(): Flow<SpeedTestProgress> = channelFlow {
        var state = SpeedTestProgress()
        send(state)

        // Server details are informative only; the measurement itself does not depend on them.
        val server = try {
            cloudflare.fetchPublicInfo()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        state = state.copy(server = server, phase = SpeedTestPhase.LATENCY)
        send(state)

        val samples = mutableListOf<Double>()
        repeat(LATENCY_SAMPLES) { index ->
            samples += measureLatencyMs()
            // The first request also opens the connection, so it is left out of the result.
            val valid = samples.drop(1)
            send(state.copy(pingMs = LatencyStats.median(valid), phaseProgress = (index + 1f) / LATENCY_SAMPLES))
        }
        val valid = samples.drop(1)
        state = state.copy(
            pingMs = LatencyStats.median(valid),
            jitterMs = LatencyStats.jitter(valid),
            phase = SpeedTestPhase.DOWNLOAD,
            phaseProgress = 0f
        )
        send(state)

        val download = measureThroughput(upload = false) { live, fraction ->
            send(state.copy(liveMbps = live, phaseProgress = fraction))
        }
        state = state.copy(downloadMbps = download, liveMbps = null, phase = SpeedTestPhase.UPLOAD, phaseProgress = 0f)
        send(state)

        val upload = measureThroughput(upload = true) { live, fraction ->
            send(state.copy(liveMbps = live, phaseProgress = fraction))
        }
        send(state.copy(uploadMbps = upload, liveMbps = null, phase = SpeedTestPhase.DONE, phaseProgress = 1f))
    }.flowOn(Dispatchers.IO)

    private fun measureLatencyMs(): Double {
        val request = Request.Builder().url(CloudflareSpeedEndpoints.download(0)).build()
        val start = System.nanoTime()
        client.newCall(request).execute().use { response ->
            // execute() returns once the response headers arrive: one HTTP round trip.
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return elapsed
        }
    }

    private suspend fun measureThroughput(
        upload: Boolean,
        onProgress: suspend (liveMbps: Double, fraction: Float) -> Unit
    ): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val maxBytes = if (upload) MAX_UPLOAD_BYTES else MAX_DOWNLOAD_BYTES
        val start = System.nanoTime()
        val deadline = start + TRANSFER_DURATION_NANOS
        val calls = CopyOnWriteArrayList<Call>()
        fun finished() = System.nanoTime() >= deadline || bytes.get() >= maxBytes

        val workers = List(PARALLEL_STREAMS) {
            launch(Dispatchers.IO) {
                while (isActive && !finished()) {
                    val call = client.newCall(if (upload) uploadRequest(bytes, ::finished) else downloadRequest())
                    calls += call
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            if (!upload) {
                                val stream = response.body.byteStream()
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (!finished()) {
                                    val read = stream.read(buffer)
                                    if (read == -1) break
                                    bytes.addAndGet(read.toLong())
                                }
                            }
                        }
                    } catch (e: IOException) {
                        // Calls are cancelled on purpose when the phase ends; other errors abort the test.
                        if (!finished() && !call.isCanceled()) throw e
                    } finally {
                        calls -= call
                    }
                }
            }
        }

        // Live speed over a sliding one-second window, sampled every 250 ms.
        val window = ArrayDeque<Pair<Long, Long>>()
        var warmupBytes = 0L
        var warmupTime = start
        var warmupDone = false
        while (!finished()) {
            delay(SAMPLE_INTERVAL_MS)
            val now = System.nanoTime()
            val total = bytes.get()
            window.addLast(now to total)
            while (window.size > 1 && now - window.first().first > 1_000_000_000L) window.removeFirst()
            val (oldTime, oldBytes) = window.first()
            val live = LatencyStats.mbps(total - oldBytes, now - oldTime)
            if (!warmupDone && now - start >= WARMUP_NANOS) {
                warmupDone = true
                warmupBytes = total
                warmupTime = now
            }
            val fraction = maxOf(
                (now - start).toFloat() / TRANSFER_DURATION_NANOS,
                total.toFloat() / maxBytes
            ).coerceIn(0f, 1f)
            onProgress(live, fraction)
        }
        val end = System.nanoTime()
        val total = bytes.get()
        calls.forEach { it.cancel() }
        workers.joinAll()

        if (warmupDone && end - warmupTime > MIN_MEASURE_NANOS) {
            LatencyStats.mbps(total - warmupBytes, end - warmupTime)
        } else {
            // Very fast links can hit the byte cap during warm-up; use the whole phase then.
            LatencyStats.mbps(total, end - start)
        }
    }

    private fun downloadRequest(): Request =
        Request.Builder().url(CloudflareSpeedEndpoints.download(DOWNLOAD_CHUNK_BYTES)).build()

    private fun uploadRequest(counter: AtomicLong, finished: () -> Boolean): Request =
        Request.Builder()
            .url(CloudflareSpeedEndpoints.UPLOAD)
            .post(CountingUploadBody(UPLOAD_CHUNK_BYTES, counter, finished))
            .build()

    /** Random payload (incompressible) that counts every byte handed to the socket. */
    private class CountingUploadBody(
        private val length: Long,
        private val counter: AtomicLong,
        private val finished: () -> Boolean
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = length

        override fun writeTo(sink: BufferedSink) {
            var remaining = length
            while (remaining > 0) {
                if (finished()) throw IOException("upload phase finished")
                val size = minOf(PAYLOAD.size.toLong(), remaining).toInt()
                sink.write(PAYLOAD, 0, size)
                sink.flush()
                counter.addAndGet(size.toLong())
                remaining -= size
            }
        }
    }

    private companion object {
        const val LATENCY_SAMPLES = 11
        const val PARALLEL_STREAMS = 4
        const val BUFFER_SIZE = 64 * 1024
        const val SAMPLE_INTERVAL_MS = 250L
        const val TRANSFER_DURATION_NANOS = 10_000_000_000L
        const val WARMUP_NANOS = 2_000_000_000L
        const val MIN_MEASURE_NANOS = 1_000_000_000L
        const val DOWNLOAD_CHUNK_BYTES = 25_000_000L
        const val UPLOAD_CHUNK_BYTES = 5_000_000L
        const val MAX_DOWNLOAD_BYTES = 300_000_000L
        const val MAX_UPLOAD_BYTES = 120_000_000L
        val PAYLOAD: ByteArray = Random.nextBytes(64 * 1024)
    }
}
