package et.addisreel.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Small, Wi-Fi-only MP4 head / HLS master prefetch for the upcoming episode.
 *
 * A full second ExoPlayer instance wastes data and memory, especially on low-end
 * Android phones. Instead prime at most 256 KiB in the *same* LRU media cache
 * used by the active player, and only near the end of the current episode.
 *
 * HLS playback remains adaptive: prefetch only its tiny master playlist, not
 * video segments or entire episodes. Never prefetch on metered networks.
 */
internal object NextEpisodePreloader {
    private const val MP4_MAX_BYTES = 256 * 1024
    private const val HLS_MANIFEST_MAX_BYTES = 64 * 1024
    private const val READ_CHUNK_BYTES = 8 * 1024

    suspend fun warmNextEpisodeOnUnmeteredNetwork(context: Context, url: String, cache: SimpleCache): Boolean {
        val mp4 = PlaybackPolicy.canPrimeMp4(url)
        val manifest = PlaybackPolicy.canWarmHlsManifest(url)
        if (!mp4 && !manifest) return false
        val maxBytes = if (mp4) MP4_MAX_BYTES else HLS_MANIFEST_MAX_BYTES
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (connectivity.isActiveNetworkMetered) return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false

        return withContext(Dispatchers.IO) {
            val dataSource = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(5_000)
                        .setReadTimeoutMs(5_000)
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()

            try {
                val request = DataSpec.Builder()
                    .setUri(url)
                    .setLength(if (mp4) maxBytes.toLong() else C.LENGTH_UNSET.toLong())
                    .build()
                dataSource.open(request)
                var remaining = maxBytes
                val chunk = ByteArray(READ_CHUNK_BYTES)
                var loaded = 0
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = dataSource.read(chunk, 0, minOf(chunk.size, remaining))
                    if (count == C.RESULT_END_OF_INPUT) break
                    remaining -= count
                    loaded += count
                }
                loaded > 0
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Prefetch is best-effort; never interrupt the currently playing episode.
                false
            } finally {
                runCatching { dataSource.close() }
            }
        }
    }
}
