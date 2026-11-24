package et.addisreel.app

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.ui.PlayerView

// One bounded on-device cache shared by players; never create a SimpleCache per episode.
private object StreamCache {
    @Volatile private var instance: SimpleCache? = null
    fun get(context: Context): SimpleCache =
        instance ?: synchronized(this) {
            instance ?: SimpleCache(
                java.io.File(context.applicationContext.cacheDir, "stream_segments"),
                LeastRecentlyUsedCacheEvictor(80L * 1024L * 1024L),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { instance = it }
        }
}
// Decode posters close to their display resolution rather than allocating full-resolution bitmaps.
// Coil reuses its memory/disk caches across the Home, History and drama detail surfaces.
private fun posterVariant(url: String?, widthPx: Int): String? {
    if (url == null) return null
    val variantWidth = when {
        widthPx <= 320 -> 320
        widthPx <= 640 -> 640
        else -> 960
    }
    return if (url.startsWith(CATALOG_API + "/v1/posters/")) "$url?w=$variantWidth" else url
}

@Composable
private fun posterModel(url: String?, widthPx: Int = NetworkBudgetPolicy.posterWidthForTile(142), heightPx: Int = 480): ImageRequest {
    val context = LocalContext.current
    return remember(context, url, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(posterVariant(url, widthPx))
            .size(widthPx, heightPx)
            .precision(Precision.INEXACT)
            .crossfade(180)
            .build()
    }
}

private val Gold: Color
    @Composable get() = AddisColors.accent
private data class Drama(val title:String, val genre:String, val episodes:Int, val description:String, val url:String? = null, val id:String = "", val posterUrl:String? = null)
private const val CATALOG_API = "https://addisreel-catalog.eddieaman475.workers.dev"
private val previewCatalog = listOf(
    Drama("Addis Love", "Romance", 12, "A story of love, family and difficult choices in Addis Ababa."),
    Drama("The Last Promise", "Drama", 8, "An Ethiopian family faces a life-changing secret."),
    Drama("Jimma Nights", "Mystery", 10, "A mystery unfolds over one unforgettable night."),
    Drama("Our Tomorrow", "Family", 6, "Two generations discover what truly matters.")
)
private fun catalogResponse(path: String): JSONObject {
    val connection = (URL(CATALOG_API + path).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6000
        readTimeout = 6000
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("Catalog HTTP " + connection.responseCode)
        }
        return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    } finally {
        connection.disconnect()
    }
}
private const val CATALOG_CACHE_KEY = "published_catalog_v1"
private const val CATALOG_CACHE_AT_KEY = "published_catalog_at_v1"
private const val CATALOG_CACHE_ETAG_KEY = "published_catalog_etag_v1"
private data class CatalogResult(val titles: List<Drama>?, val etag: String?, val unchanged: Boolean)

private fun parsePublicTitles(array: JSONArray): List<Drama> {
    val seen = HashSet<String>()
    return buildList {
        for (i in 0 until minOf(array.length(), CatalogCachePolicy.MAX_SERIES)) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id", "")
            val title = item.optString("title", "")
            val genre = item.optString("genre", "")
            val description = item.optString("description", "")
            val count = item.optInt("episodes", 0)
            if (!CatalogCachePolicy.validPublicSeries(id, title, genre, description, count) ||
                !seen.add(id)) continue
            add(Drama(
                title = title, genre = genre, episodes = count, description = description,
                id = id, posterUrl = CATALOG_API + "/v1/posters/" + id
            ))
        }
    }
}

private fun cachedPublicTitles(prefs: SharedPreferences): List<Drama> {
    val encoded = prefs.getString(CATALOG_CACHE_KEY, null)
    if (!CatalogCachePolicy.isFresh(
            System.currentTimeMillis(), prefs.getLong(CATALOG_CACHE_AT_KEY, 0L), encoded
        )) return emptyList()
    return runCatching { parsePublicTitles(JSONArray(encoded!!)) }.getOrDefault(emptyList())
}

private fun rememberPublicTitles(prefs: SharedPreferences, titles: List<Drama>, etag: String?) {
    val array = JSONArray()
    for (item in titles.take(CatalogCachePolicy.MAX_SERIES)) {
        array.put(JSONObject()
            .put("id", item.id).put("title", item.title)
            .put("genre", item.genre).put("description", item.description)
            .put("episodes", item.episodes))
    }
    val raw = array.toString()
    if (raw.length > CatalogCachePolicy.MAX_PAYLOAD_CHARS) return
    prefs.edit()
        .putString(CATALOG_CACHE_KEY, raw)
        .putLong(CATALOG_CACHE_AT_KEY, System.currentTimeMillis())
        .putString(CATALOG_CACHE_ETAG_KEY, etag.takeIf { CatalogCachePolicy.validEtag(it) })
        .apply()
}

private fun clearCachedPublicTitles(prefs: SharedPreferences) {
    prefs.edit()
        .remove(CATALOG_CACHE_KEY).remove(CATALOG_CACHE_AT_KEY)
        .remove(CATALOG_CACHE_ETAG_KEY).apply()
}

/** A 304 never contains a body; use it only when a validated local snapshot exists. */
private fun fetchPublishedCatalog(knownEtag: String?): CatalogResult {
    val connection = (URL(CATALOG_API + "/v1/catalog").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6000
        readTimeout = 6000
        setRequestProperty("Accept", "application/json")
        if (CatalogCachePolicy.validEtag(knownEtag))
            setRequestProperty("If-None-Match", knownEtag)
    }
    try {
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_NOT_MODIFIED)
            return CatalogResult(null, knownEtag, unchanged = true)
        if (code != HttpURLConnection.HTTP_OK)
            throw IllegalStateException("Catalog HTTP " + code)
        val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            val payload = StringBuilder()
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                if (payload.length + n > CatalogCachePolicy.MAX_PAYLOAD_CHARS)
                    throw IllegalStateException("Catalog exceeds safe cache size")
                payload.append(buffer, 0, n)
            }
            payload.toString()
        }
        val titles = parsePublicTitles(JSONObject(text).getJSONArray("series"))
        val newEtag = connection.getHeaderField("ETag")
        return CatalogResult(titles, newEtag.takeIf { CatalogCachePolicy.validEtag(it) }, unchanged = false)
    } finally {
        connection.disconnect()
    }
}
private fun fetchEpisodeStream(id: String, episode: Int): String? {
    val url = catalogResponse("/v1/play/" + id + "/" + episode).optString("playbackUrl")
    return url.takeIf { PlaybackPolicy.acceptsPlaybackUrl(it) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { AddisReelTheme { AddisReelApp() } }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddisReelApp() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("addisreel_library", android.content.Context.MODE_PRIVATE) }
    val playbackPreferences = remember(context) { context.getSharedPreferences("addisreel_playback", android.content.Context.MODE_PRIVATE) }
    // Display only validated, public licensed metadata from a bounded device snapshot.
    // Playback still checks the server for the current publication/license state.
    val cachedTitles = remember(preferences) { cachedPublicTitles(preferences) }
    var catalog by remember { mutableStateOf(cachedTitles.ifEmpty { previewCatalog }) }
    var catalogStatus by remember { mutableStateOf(if (cachedTitles.isNotEmpty()) "cached" else "loading") }
    var catalogRefreshRequest by remember { mutableIntStateOf(0) }
    var remoteCatalogAvailable by remember { mutableStateOf(false) }
    var episodeUrl by remember { mutableStateOf<String?>(null) }
    // Never render a stale stream from an older series or episode while a new
    // playback token is being resolved.
    var resolvedStreamKey by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var nextEpisodeUrl by remember { mutableStateOf<String?>(null) }
    var nextEpisodeFetchedAtMs by remember { mutableLongStateOf(0L) }
    val appScope = rememberCoroutineScope()
    // Prefetch only the next playback URL (not the video bytes) to avoid extra mobile data.
    var prefetchedEpisode by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    // Signed playback URLs may expire. Never reuse a prefetched URL indefinitely.
    var prefetchedAtMs by remember { mutableLongStateOf(0L) }
    // Open on the cinematic drama storefront; playback is a deliberate viewer action.
    var tab by remember { mutableIntStateOf(1) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showFullDescription by remember { mutableStateOf(false) }
    // Viewers can move between short episodes with a deliberate vertical swipe.
    // Keep the gesture on the player container so it works with real video too.
    var cinemaMode by remember { mutableStateOf(false) }
    // Default to the original frame so subtitles and credits are never cropped.
    var fillVideo by remember {
        mutableStateOf(preferences.getBoolean("cinema_fill_video", false))
    }
    var subtitlesEnabled by remember {
        mutableStateOf(preferences.getBoolean("cinema_subtitles", true))
    }
    var videoQuality by remember {
        mutableStateOf(preferences.getString("cinema_quality", "Auto") ?: "Auto")
    }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(preferences.getFloat("cinema_playback_speed", 1f).coerceIn(0.75f, 1.5f)) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var autoNextEnabled by remember { mutableStateOf(preferences.getBoolean("cinema_auto_next", true)) }
    var discoverQuery by remember { mutableStateOf("") }
    var homeSearchExpanded by remember { mutableStateOf(false) }
    var discoverGenre by remember { mutableStateOf("All") }
    var discoverSort by remember { mutableStateOf("Featured") }
    var discoverOnlySaved by remember { mutableStateOf(false) }
    var discoverOnlyUnwatched by remember { mutableStateOf(false) }
    var libraryFilter by remember { mutableStateOf("Saved") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var libraryCompletionRevision by remember { mutableIntStateOf(0) }
    var detailDrama by remember { mutableStateOf<Drama?>(null) }
    var recentlyWatched by remember { mutableStateOf(preferences.getString("recently_watched", "") ?: "") }
    // Keep a small, ordered local viewing history without requiring an account.
    var viewingHistory by remember {
        mutableStateOf(
            (preferences.getString("viewing_history", "") ?: "")
                .split("|").filter { it.isNotBlank() }.distinct().take(12)
        )
    }
    fun recordViewing(title: String) {
        viewingHistory = (listOf(title) + viewingHistory.filterNot { it == title }).take(12)
        preferences.edit().putString("viewing_history", viewingHistory.joinToString("|")).apply()
    }
    fun savedResumeEpisode(drama: Drama): Int = PlaybackPolicy.resumeEpisode(
        preferences.getInt("episode_" + drama.title, 1), drama.episodes,
        preferences.getStringSet("watched_" + drama.title, emptySet())?.toSet().orEmpty()
    )
    var selected by remember { mutableStateOf(catalog.find { it.title == recentlyWatched } ?: catalog.first()) }
    var episode by remember { mutableIntStateOf(savedResumeEpisode(selected)) }
    // A rejected signed URL gets one automatic credential refresh per episode,
    // never an unlimited refresh loop against an unavailable or forbidden asset.
    var tokenRefreshAttempts by remember(selected.id, episode) { mutableIntStateOf(0) }
    // Browsing a title or tapping a chapter is not proof of watching it.
    // Mark history and last-reached episode only when the player actually runs.
    fun markPlaybackStarted(title: String, chapter: Int) {
        recentlyWatched = title
        recordViewing(title)
        preferences.edit()
            .putString("recently_watched", title)
            .putInt("episode_" + title, chapter)
            .apply()
    }
    fun selectDrama(drama: Drama) {
        selected = drama
        showEpisodes = false
        showFullDescription = false
        cinemaMode = false
        episode = savedResumeEpisode(drama)
    }
    fun selectEpisode(next: Int) {
        episode = next.coerceIn(1, selected.episodes)
    }
    LaunchedEffect(catalogRefreshRequest) {
        // Re-read cache on each retry so a 304 never uses an expired snapshot.
        val localTitles = withContext(Dispatchers.IO) { cachedPublicTitles(preferences) }
        val knownEtag = if (localTitles.isNotEmpty())
            preferences.getString(CATALOG_CACHE_ETAG_KEY, null)?.takeIf { CatalogCachePolicy.validEtag(it) }
        else null
        val response = runCatching {
            withContext(Dispatchers.IO) { fetchPublishedCatalog(knownEtag) }
        }.getOrNull()
        if (response == null) {
            catalogStatus = if (localTitles.isNotEmpty()) "offline" else "unavailable"
        } else {
            val published = if (response.unchanged) localTitles else response.titles.orEmpty()
            if (published.isNotEmpty()) {
                catalog = published
                remoteCatalogAvailable = true
                catalogStatus = "live"
                val restored = published.find { it.id == selected.id && it.id.isNotBlank() }
                    ?: published.find { it.title == recentlyWatched }
                    ?: published.first()
                selected = restored
                episode = savedResumeEpisode(restored)
                withContext(Dispatchers.IO) {
                    rememberPublicTitles(preferences, published, response.etag)
                }
            } else {
                // A successful empty response revokes the old catalog snapshot.
                // Never preserve offline entries when the server has withdrawn them.
                catalog = previewCatalog
                remoteCatalogAvailable = false
                catalogStatus = "empty"
                selected = previewCatalog.first()
                episode = 1
                withContext(Dispatchers.IO) { clearCachedPublicTitles(preferences) }
            }
        }
    }
    // Warm only the first few visible posters, and only on unmetered connectivity.
    // The request cache is shared with AsyncImage; cancelling Home cancels pending work.
    LaunchedEffect(catalog, tab, homeSearchExpanded) {
        if (tab == 1 && !homeSearchExpanded && catalog.isNotEmpty()) {
            delay(600L) // Prioritize visible artwork before warming below-the-fold tiles.
            val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = network.activeNetwork
            val capabilities = active?.let { network.getNetworkCapabilities(it) }
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            if (NetworkBudgetPolicy.shouldWarmPosters(
                    homeVisible = tab == 1, searchExpanded = homeSearchExpanded,
                    validatedNetwork = validated, metered = network.isActiveNetworkMetered
                )) {
                val warmups = catalog.drop(1).take(2).mapNotNull { it.posterUrl }.map { poster ->
                    context.imageLoader.enqueue(
                        ImageRequest.Builder(context).data(posterVariant(poster, 320)).size(320, 480)
                            .precision(Precision.INEXACT).build()
                    )
                }
                try { awaitCancellation() } finally { warmups.forEach { it.dispose() } }
            }
        }
    }
    var streamRefresh by remember { mutableIntStateOf(0) }
    var streamLoading by remember { mutableStateOf(false) }
    // Launch playback requests only while the Watch surface is actually shown.
    // Opening the app, searching, viewing details, or entering Library costs
    // zero /v1/play requests. Episode links remain short lived and uncached.
    LaunchedEffect(selected.id, episode, streamRefresh, tab == 0) {
        if (!NetworkBudgetPolicy.shouldResolveEpisode(tab == 0, selected.id)) {
            episodeUrl = null
            resolvedStreamKey = null
            nextEpisodeUrl = null
            streamLoading = false
            return@LaunchedEffect
        }
        episodeUrl = null
        resolvedStreamKey = null
        nextEpisodeUrl = null
        nextEpisodeFetchedAtMs = 0L
        streamLoading = true
        val seriesId = selected.id
        val currentEpisode = episode
        val warm = prefetchedEpisode
        val resolved = if (
            streamRefresh == 0 && warm?.first == seriesId && warm.second == currentEpisode &&
            PlaybackPolicy.freshPlaybackLink(android.os.SystemClock.elapsedRealtime(), prefetchedAtMs, 60_000L)
        ) {
            prefetchedEpisode = null
            warm.third
        } else {
            runCatching {
                withContext(Dispatchers.IO) { fetchEpisodeStream(seriesId, currentEpisode) }
            }.getOrNull()
        }
        // LaunchedEffect cancellation discards responses when changing titles
        // or tabs; a previous video's source must never flash on this surface.
        if (resolved != null) {
            episodeUrl = resolved
            resolvedStreamKey = seriesId to currentEpisode
        }
        streamLoading = false
        // Do not eagerly resolve the next episode here. The actual player asks
        // for it only near the final 35 seconds, conserving requests and
        // avoiding tokens that expire before viewers finish a long episode.
    }
    // Refresh expiring playback credentials near the episode ending; a URL
    // fetched at the start can be stale by the time a longer episode finishes.
    fun prepareNextEpisode() {
        val seriesId = selected.id
        val currentEpisode = episode
        val currentPrefetch = prefetchedEpisode
        if (seriesId.isNotBlank() && currentEpisode < selected.episodes &&
            !(currentPrefetch?.first == seriesId && currentPrefetch.second == currentEpisode + 1 &&
                PlaybackPolicy.freshPlaybackLink(android.os.SystemClock.elapsedRealtime(), prefetchedAtMs, 35_000L))) {
            appScope.launch {
                val refreshed = runCatching {
                    withContext(Dispatchers.IO) { fetchEpisodeStream(seriesId, currentEpisode + 1) }
                }.getOrNull()
                // Ignore slow responses after the viewer changes episodes or series.
                if (refreshed != null && selected.id == seriesId && episode == currentEpisode) {
                    nextEpisodeUrl = refreshed
                    nextEpisodeFetchedAtMs = android.os.SystemClock.elapsedRealtime()
                    prefetchedEpisode = Triple(seriesId, currentEpisode + 1, refreshed)
                    prefetchedAtMs = android.os.SystemClock.elapsedRealtime()
                }
            }
        }
    }
    var saved by remember { mutableStateOf(preferences.getStringSet("saved_titles", emptySet())?.toSet() ?: emptySet()) }
    fun toggleSaved(title: String) {
        val updated = if (title in saved) saved - title else saved + title
        saved = updated
        preferences.edit().putStringSet("saved_titles", updated).apply()
    }
    // Predictable Android Back: exit fullscreen first, then return to Home instead
    // of closing the app and losing the user's place in the current episode.
    BackHandler(enabled = cinemaMode && tab == 0) { cinemaMode = false }
    BackHandler(
        enabled = !cinemaMode && !showEpisodes && detailDrama == null && !confirmClearHistory &&
            (tab != 1 || homeSearchExpanded)
    ) {
        if (tab == 1) {
            homeSearchExpanded = false
            discoverQuery = ""
            discoverGenre = "All"
            discoverSort = "Featured"
            discoverOnlySaved = false
            discoverOnlyUnwatched = false
        } else {
            tab = 1
        }
    }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear viewing history?") },
            text = { Text("This removes the recently watched list on this device. Saved dramas and completed episode records will remain.") },
            confirmButton = {
                TextButton(onClick = {
                    viewingHistory = emptyList()
                    recentlyWatched = ""
                    preferences.edit().remove("viewing_history").remove("recently_watched").apply()
                    confirmClearHistory = false
                }) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Keep history") } }
        )
    }
    Box { // All screens and dialogs now inherit the single root AddisReelTheme.
        detailDrama?.let { drama ->
            AlertDialog(
                onDismissRequest = { detailDrama = null },
                icon = { Icon(Icons.Default.Movie, contentDescription = null, tint = Gold) },
                title = { Text(drama.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(drama.genre + "  •  " + drama.episodes + " episodes", color = Gold)
                        Text(drama.description)
                        TextButton(onClick = {
                            val message = "Discover ${drama.title} on AddisReel — ${drama.genre}, ${drama.episodes} short episodes."
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, message)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share this drama"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share drama")
                        }
                        Text(
                            if (drama.id.isBlank()) "Preview title · Streaming begins when licensed content is published." else "Licensed Ethiopian short-drama catalog.",
                            fontSize = 12.sp,
                            color = AddisColors.textSecondary
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        selectDrama(drama)
                        detailDrama = null
                        tab = 0
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("Open series")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        toggleSaved(drama.title)
                    }) {
                        Icon(
                            if (drama.title in saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = null
                        )
                        Text(if (drama.title in saved) "Saved" else "Save")
                    }
                }
            )
        }
        if (showEpisodes) {
            ModalBottomSheet(
                onDismissRequest = { showEpisodes = false },
                containerColor = AddisColors.elevated,
                dragHandle = { BottomSheetDefaults.DragHandle(color = Gold) }
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(selected.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("${selected.episodes} short episodes · Select an episode", color = Gold, fontSize = 12.sp)
                    val lastReached = savedResumeEpisode(selected)
                    // Only completed episodes count as watched; jumping ahead is not completion.
                    val watchedEpisodes = preferences.getStringSet("watched_" + selected.title, emptySet())?.toSet().orEmpty()
                    val watchedCount = watchedEpisodes.count { number -> number.toIntOrNull()?.let { it in 1..selected.episodes } == true }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Watched $watchedCount · Continue at EP $lastReached", color = AddisColors.textSecondary, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${PlaybackPolicy.completedPercent(watchedCount, selected.episodes)}%", color = Gold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { watchedCount.toFloat() / selected.episodes.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Gold,
                        trackColor = AddisColors.border
                    )
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items((1..selected.episodes).toList()) { number ->
                            val active = number == episode
                            val watched = number.toString() in watchedEpisodes
                            Surface(
                                onClick = { selectEpisode(number); showEpisodes = false },
                                shape = RoundedCornerShape(14.dp),
                                color = if (active) AddisColors.selected else AddisColors.elevated,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (active) Icons.Default.PlayCircle else if (watched) Icons.Default.CheckCircle else Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = if (active || watched) Gold else AddisColors.textSecondary
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Episode $number", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            when {
                                                active -> "Now playing"
                                                watched -> "Watched"
                                                number == lastReached -> "Continue here"
                                                else -> "Tap to watch"
                                            },
                                            fontSize = 11.sp,
                                            color = if (active || watched || number == lastReached) Gold else AddisColors.textSecondary
                                        )
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }
        Scaffold(containerColor=AddisColors.background, bottomBar={
            if (!cinemaMode || tab != 0) NavigationBar(containerColor=AddisColors.navigation, tonalElevation=0.dp) {
                listOf(
                    Triple(stringResource(R.string.nav_watch), Icons.Default.PlayCircle, 0),
                    Triple(stringResource(R.string.nav_home), Icons.Default.Home, 1),
                    Triple(stringResource(R.string.nav_library), Icons.Default.Bookmark, 2),
                    Triple(stringResource(R.string.nav_premium), Icons.Default.Diamond, 3)
                ).forEach { (name,icon,index) ->
                    NavigationBarItem(
                        selected=tab==index,
                        onClick={tab=index},
                        icon={Icon(icon,contentDescription=name)},
                        label={Text(name, style=MaterialTheme.typography.labelMedium, maxLines=1, overflow=TextOverflow.Ellipsis)},
                        alwaysShowLabel=false,
                        colors=NavigationBarItemDefaults.colors(
                            selectedIconColor=AddisColors.current.onAccent,
                            selectedTextColor=Gold,
                            unselectedIconColor=AddisColors.textMuted,
                            unselectedTextColor=AddisColors.textMuted,
                            indicatorColor=Gold
                        )
                    )
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when(tab) {
                    0 -> {
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .background(AddisCinema.playerBackground)
                                .pointerInput(selected.id, selected.title, episode, selected.episodes) {
                                    var dragDistance = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = { dragDistance = 0f },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            dragDistance += dragAmount
                                        },
                                        onDragEnd = {
                                            if (dragDistance < -110f && episode < selected.episodes) {
                                                selectEpisode(episode + 1)
                                            } else if (dragDistance > 110f && episode > 1) {
                                                selectEpisode(episode - 1)
                                            }
                                            dragDistance = 0f
                                        },
                                        onDragCancel = { dragDistance = 0f }
                                    )
                                }
                        ) {
                            val videoUrl = if (selected.id.isNotBlank()) {
                                episodeUrl.takeIf { resolvedStreamKey == (selected.id to episode) }
                            } else selected.url
                            if (videoUrl != null) {
                                Video(videoUrl, (selected.id.ifBlank { selected.title }) + "_episode_" + episode, fillVideo = fillVideo, subtitlesEnabled = subtitlesEnabled, quality = videoQuality, playbackSpeed = playbackSpeed, nextEpisodeUrl = nextEpisodeUrl, nextEpisodeFetchedAtMs = nextEpisodeFetchedAtMs, hasNextEpisode = episode < selected.episodes, autoPlayNext = autoNextEnabled, onAutoNextToggle = {
                                    autoNextEnabled = !autoNextEnabled
                                    preferences.edit().putBoolean("cinema_auto_next", autoNextEnabled).apply()
                                }, onPlaybackUrlExpired = { status ->
                                    if (selected.id.isNotBlank() && PlaybackPolicy.canRefreshRejectedLink(status, tokenRefreshAttempts)) {
                                        tokenRefreshAttempts += 1
                                        streamRefresh += 1
                                    }
                                }, onPrepareNext = { prepareNextEpisode() }, onPlaybackStarted = {
                                    markPlaybackStarted(selected.title, episode)
                                }, onWatchedToEnd = {
                                    // A very short episode may finish before the 3-second
                                    // history threshold; completion still counts as viewing.
                                    markPlaybackStarted(selected.title, episode)
                                    val key = "watched_" + selected.title
                                    val updated = preferences.getStringSet(key, emptySet())?.toSet().orEmpty() + episode.toString()
                                    preferences.edit().putStringSet(key, updated).apply()
                                    libraryCompletionRevision += 1
                                }, onEpisodeCompleted = {
                                    if (episode < selected.episodes) selectEpisode(episode + 1)
                                })
                            } else {
                                Column(
                                    Modifier.align(Alignment.Center).fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Movie, null, Modifier.size(74.dp), tint=AddisCinema.accent)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        when {
                                            selected.id.isBlank() -> "Ethiopian stories. Coming soon."
                                            streamLoading -> "Preparing episode stream…"
                                            else -> "Episode stream unavailable"
                                        },
                                        color = Color.LightGray
                                    )
                                    Text(
                                        when {
                                            selected.id.isBlank() -> "Licensed episodes will play here"
                                            streamLoading -> "Connecting securely to your episode"
                                            else -> "Check your internet connection and try again"
                                        },
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                    if (selected.id.isNotBlank() && streamLoading) {
                                        Spacer(Modifier.height(12.dp))
                                        CircularProgressIndicator(color = AddisCinema.accent, modifier = Modifier.size(26.dp))
                                    }
                                    if (selected.id.isNotBlank() && !streamLoading) {
                                        Spacer(Modifier.height(12.dp))
                                        Button(onClick = {
                                            // Force a fresh playback URL; never reuse a failed prefetched token.
                                            prefetchedEpisode = null
                                            streamRefresh += 1
                                        }) {
                                            Icon(Icons.Default.Refresh, contentDescription = null)
                                            Spacer(Modifier.width(6.dp))
                                            Text("Retry episode")
                                        }
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Text("Swipe up for next episode · down for previous", fontSize=11.sp, color=Color.Gray)
                                }
                            }
                            IconButton(onClick = { cinemaMode = !cinemaMode }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0x990C0C10), RoundedCornerShape(50))) {
                                Icon(if (cinemaMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, contentDescription = if (cinemaMode) "Exit cinema mode" else "Cinema mode", tint = AddisCinema.accent)
                            }
                            // Two compact rows keep cinema controls usable on narrow phones.
                            if (cinemaMode) {
                                Column(
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 12.dp)
                                        .background(Color(0xE0101016), RoundedCornerShape(22.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                    IconButton(
                                        onClick = { selectEpisode(episode - 1) },
                                        enabled = episode > 1
                                    ) {
                                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous episode", tint = if (episode > 1) AddisCinema.accent else Color.Gray)
                                    }
                                    Text(
                                        "EP $episode / ${selected.episodes}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    IconButton(
                                        onClick = { selectEpisode(episode + 1) },
                                        enabled = episode < selected.episodes
                                    ) {
                                        Icon(Icons.Default.SkipNext, contentDescription = "Next episode", tint = if (episode < selected.episodes) AddisCinema.accent else Color.Gray)
                                    }
                                    IconButton(onClick = { showEpisodes = true }) {
                                        Icon(Icons.Default.VideoLibrary, contentDescription = "All episodes", tint = AddisCinema.accent)
                                    }
                                    }
                                    HorizontalDivider(color = AddisColors.border, thickness = 1.dp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                    IconButton(onClick = {
                                        subtitlesEnabled = !subtitlesEnabled
                                        preferences.edit().putBoolean("cinema_subtitles", subtitlesEnabled).apply()
                                    }) {
                                        Icon(
                                            Icons.Default.ClosedCaption,
                                            contentDescription = if (subtitlesEnabled) "Turn subtitles off" else "Turn subtitles on",
                                            tint = if (subtitlesEnabled) AddisCinema.accent else Color.Gray
                                        )
                                    }
                                    Box {
                                        TextButton(onClick = { qualityMenuExpanded = true }) {
                                            Text(videoQuality, color = AddisCinema.accent, fontSize = 12.sp)
                                        }
                                        DropdownMenu(expanded = qualityMenuExpanded, onDismissRequest = { qualityMenuExpanded = false }) {
                                            listOf("Auto", "Data saver", "High").forEach { mode ->
                                                DropdownMenuItem(text = { Text(mode) }, onClick = {
                                                    videoQuality = mode
                                                    preferences.edit().putString("cinema_quality", mode).apply()
                                                    qualityMenuExpanded = false
                                                })
                                            }
                                        }
                                    }
                                    Box {
                                        TextButton(onClick = { speedMenuExpanded = true }) {
                                            Text("${playbackSpeed}×", color = AddisCinema.accent, fontSize = 12.sp)
                                        }
                                        DropdownMenu(expanded = speedMenuExpanded, onDismissRequest = { speedMenuExpanded = false }) {
                                            listOf(0.75f, 1f, 1.25f, 1.5f).forEach { speed ->
                                                DropdownMenuItem(text = { Text("${speed}×") }, onClick = {
                                                    playbackSpeed = speed
                                                    preferences.edit().putFloat("cinema_playback_speed", speed).apply()
                                                    speedMenuExpanded = false
                                                })
                                            }
                                        }
                                    }
                                    IconButton(onClick = {
                                        fillVideo = !fillVideo
                                        preferences.edit().putBoolean("cinema_fill_video", fillVideo).apply()
                                    }) {
                                        Icon(
                                            if (fillVideo) Icons.Default.FitScreen else Icons.Default.CropFree,
                                            contentDescription = if (fillVideo) "Fit entire video" else "Fill screen",
                                            tint = AddisCinema.accent
                                        )
                                    }
                                    IconButton(onClick = {
                                        autoNextEnabled = !autoNextEnabled
                                        preferences.edit().putBoolean("cinema_auto_next", autoNextEnabled).apply()
                                    }) {
                                        Icon(Icons.Default.Autorenew,
                                            contentDescription = if (autoNextEnabled) "Turn autoplay off" else "Turn autoplay on",
                                            tint = if (autoNextEnabled) AddisCinema.accent else Color.Gray
                                        )
                                    }
                                    }
                                }
                            }
                            if (!cinemaMode) Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledTonalIconButton(onClick = { toggleSaved(selected.title) }) {
                                    Icon(if (selected.title in saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = "Save series")
                                }
                                FilledTonalIconButton(onClick = { showEpisodes = true }) {
                                    Icon(Icons.Default.VideoLibrary, contentDescription = "Choose episode")
                                }
                                FilledTonalIconButton(onClick = { detailDrama = selected }) {
                                    Icon(Icons.Default.Info, contentDescription = "Series information")
                                }
                            }
                            if (!cinemaMode) Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                                Text("ADDISREEL  •  EPISODE $episode OF ${selected.episodes}", color=AddisCinema.accent, fontWeight=FontWeight.Bold,fontSize=12.sp)
                                Text(selected.title,fontSize=28.sp,fontWeight=FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { episode.toFloat() / selected.episodes.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = AddisCinema.accent, trackColor = AddisColors.border)
                                Text(selected.description, modifier = Modifier.clickable { showFullDescription = !showFullDescription }, maxLines = if (showFullDescription) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, color=Color.LightGray)
                                Text(if (showFullDescription) "Show less" else "More about this story", modifier = Modifier.clickable { showFullDescription = !showFullDescription }, fontSize = 11.sp, color = AddisCinema.accent)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick={showEpisodes=true}) { Icon(Icons.Default.List,contentDescription=null); Spacer(Modifier.width(6.dp)); Text("Episodes  •  $episode / ${selected.episodes}") }
                                    TextButton(onClick = {
                                        autoNextEnabled = !autoNextEnabled
                                        preferences.edit().putBoolean("cinema_auto_next", autoNextEnabled).apply()
                                    }) {
                                        Icon(Icons.Default.Autorenew, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text(if (autoNextEnabled) "Auto-next on" else "Auto-next off", fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick={selectEpisode(episode-1)},enabled=episode>1) { Icon(Icons.Default.SkipPrevious,contentDescription="Previous episode") }
                                    Button(onClick={selectEpisode(episode+1)},enabled=episode<selected.episodes) { Icon(Icons.Default.SkipNext,null); Text("Next episode") }

                                }
                            }
                        }
                        if (!cinemaMode) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            catalog.forEach { drama -> AddisChoiceChip(selected=selected==drama,onClick={selectDrama(drama)},label={Text(drama.title)}) }
                        }
                    }
                    1 -> {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                AddisSectionTitle("AddisReel")
                                Text(stringResource(R.string.home_tagline), style = AddisScript.style(stringResource(R.string.home_tagline), MaterialTheme.typography.bodySmall), color = AddisColors.textSecondary)
                            }
                            IconButton(onClick = {
                                homeSearchExpanded = !homeSearchExpanded
                                if (!homeSearchExpanded) { discoverQuery = ""; discoverGenre = "All"; discoverSort = "Featured"; discoverOnlySaved = false; discoverOnlyUnwatched = false }
                            }) {
                                Icon(if (homeSearchExpanded) Icons.Default.Close else Icons.Default.Search, contentDescription = if (homeSearchExpanded) "Close search" else stringResource(R.string.search_dramas), tint = Gold)
                            }
                        }
                        if (!remoteCatalogAvailable) Row(
                            Modifier.fillMaxWidth().padding(horizontal=12.dp, vertical=2.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Text(
                                when (catalogStatus) {
                                    "cached" -> "Saved titles · checking for new episodes…"
                                    "offline" -> "Offline · reconnect to stream saved titles"
                                    "empty" -> "No published episodes yet · previews only"
                                    "unavailable" -> "Unable to load stories · check internet"
                                    else -> "Loading published episodes…"
                                }, modifier=Modifier.weight(1f), fontSize=11.sp, color=Gold
                            )
                            if (catalogStatus != "cached" && catalogStatus != "loading") {
                                TextButton(onClick={ catalogStatus = "loading"; catalogRefreshRequest++ }) {
                                    Icon(Icons.Default.Refresh, contentDescription=null, modifier=Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Retry", fontSize=12.sp)
                                }
                            }
                        }
                        val genres = remember(catalog) { listOf("All") + catalog.map { it.genre }.distinct() }
                        val matches = catalog.filter { drama ->
                            (!discoverOnlySaved || drama.title in saved) &&
                                (!discoverOnlyUnwatched || (preferences.getStringSet("watched_" + drama.title, emptySet())?.size ?: 0) < drama.episodes) &&
                                (discoverGenre == "All" || drama.genre == discoverGenre) &&
                                (discoverQuery.isBlank() ||
                                    drama.title.contains(discoverQuery.trim(), ignoreCase = true) ||
                                    drama.genre.contains(discoverQuery.trim(), ignoreCase = true) ||
                                    drama.description.contains(discoverQuery.trim(), ignoreCase = true))
                        }
                        val favoriteGenres = viewingHistory.mapNotNull { title -> catalog.find { it.title == title }?.genre }
                            .groupingBy { it }.eachCount()
                        // Recommendations use only local history, completion and bookmarks.
                        // No personal viewing data is transmitted to the catalog API.
                        val recommendationOrder = remember(catalog, viewingHistory, saved, libraryCompletionRevision) {
                            DiscoveryRanker.recommend(
                                catalog.map { drama ->
                                    DiscoveryRanker.Signal(
                                        key = drama.id.ifBlank { drama.title },
                                        genre = drama.genre,
                                        saved = drama.title in saved,
                                        completed = PlaybackPolicy.isFullyWatched(
                                            preferences.getStringSet("watched_" + drama.title, emptySet())?.toSet().orEmpty(),
                                            drama.episodes
                                        ),
                                        historyIndex = viewingHistory.indexOf(drama.title)
                                    )
                                }, favoriteGenres
                            ).withIndex().associate { it.value to it.index }
                        }
                        val personalized = catalog.sortedBy { drama ->
                            recommendationOrder[drama.id.ifBlank { drama.title }] ?: Int.MAX_VALUE
                        }
                        val filtered = when (discoverSort) {
                            "A–Z" -> matches.sortedBy { it.title }
                            "Most episodes" -> matches.sortedByDescending { it.episodes }
                            "For You" -> matches.sortedBy { drama ->
                                recommendationOrder[drama.id.ifBlank { drama.title }] ?: Int.MAX_VALUE
                            }
                            "Recently watched" -> matches.sortedWith(compareBy { drama ->
                                val index = viewingHistory.indexOf(drama.title)
                                if (index < 0) Int.MAX_VALUE else index
                            })
                            else -> matches
                        }
                        // Never show a blank Continue Watching row when every visited story is finished.
                        // The revision invalidates this list immediately after playback reaches the end.
                        @Suppress("UNUSED_VARIABLE") val watchedRevision = libraryCompletionRevision
                        val unfinishedHistory = viewingHistory
                            .mapNotNull { title -> catalog.find { it.title == title } }
                            .distinctBy { it.id.ifBlank { it.title } }
                            .filter { drama ->
                                !PlaybackPolicy.isFullyWatched(
                                    preferences.getStringSet("watched_" + drama.title, emptySet())?.toSet().orEmpty(),
                                    drama.episodes
                                )
                            }
                            .take(12)
                        Column(Modifier.fillMaxSize()) {
                            if (homeSearchExpanded) OutlinedTextField(
                                value = discoverQuery,
                                onValueChange = { discoverQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                label = { Text("Find your next favorite story") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                trailingIcon = {
                                    if (discoverQuery.isNotEmpty()) {
                                        IconButton(onClick = { discoverQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                        }
                                    }
                                }
                            )
                            if (homeSearchExpanded) Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                genres.forEach { genre ->
                                    AddisChoiceChip(
                                        selected = discoverGenre == genre,
                                        onClick = { discoverGenre = genre },
                                        label = { Text(genre) }
                                    )
                                }
                            }
                            if (homeSearchExpanded) Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Sort, contentDescription = null, tint = AddisColors.textSecondary)
                                listOf("Featured", "For You", "A–Z", "Most episodes", "Recently watched").forEach { sort ->
                                    AddisChoiceChip(
                                        selected = discoverSort == sort,
                                        onClick = { discoverSort = sort },
                                        label = { Text(sort) }
                                    )
                                }
                            }
                            if (homeSearchExpanded) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AddisChoiceChip(selected = discoverOnlySaved, onClick = { discoverOnlySaved = !discoverOnlySaved }, label = { Text("Saved only") })
                                AddisChoiceChip(selected = discoverOnlyUnwatched, onClick = { discoverOnlyUnwatched = !discoverOnlyUnwatched }, label = { Text("Not completed") })
                                if (discoverOnlySaved || discoverOnlyUnwatched) TextButton(onClick = { discoverOnlySaved = false; discoverOnlyUnwatched = false }) { Text("Reset") }
                            }
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (!homeSearchExpanded && discoverQuery.isBlank() && discoverGenre == "All" && catalog.isNotEmpty()) {
                                val featured = personalized.firstOrNull { it.id.isNotBlank() }
                                    ?: personalized.first()
                                    item {
                                ElevatedCard(
                                    onClick = { selectDrama(featured); tab = 0 },
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = AddisColors.elevated)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.fillMaxWidth().height(296.dp).background(AddisColors.posterPlaceholder)) {
                                            if (featured.posterUrl != null) {
                                                AsyncImage(model = posterModel(featured.posterUrl, 960, 1080), contentDescription = "Featured drama poster", contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize(),
                                                placeholder = ColorPainter(AddisColors.posterPlaceholder),
                                                error = ColorPainter(AddisColors.posterPlaceholder))
                                            }
                                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colorStops = arrayOf(0f to Color(0x110C0C10), 0.42f to Color.Transparent, 0.68f to Color(0xDD29202C), 1f to AddisColors.elevated))))
                                            Text("YOUR FEATURED STORY", modifier = Modifier.align(Alignment.BottomStart).padding(18.dp), color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                                        }
                                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(featured.title, fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 33.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(featured.genre + "  •  " + featured.episodes + " episodes", color = AddisColors.textSecondary)
                                        Text(featured.description, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 21.sp, color = AddisColors.textPrimary)
                                        val featuredResume = PlaybackPolicy.resumeEpisode(
                                            preferences.getInt("episode_" + featured.title, 1),
                                            featured.episodes,
                                            preferences.getStringSet("watched_" + featured.title, emptySet())?.toSet().orEmpty()
                                        )
                                        val featuredCompleted = PlaybackPolicy.isFullyWatched(
                                            preferences.getStringSet("watched_" + featured.title, emptySet())?.toSet().orEmpty(),
                                            featured.episodes
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Button(onClick = {
                                                selectDrama(featured)
                                                selectEpisode(if (featuredCompleted) 1 else featuredResume)
                                                tab = 0
                                            }, modifier = Modifier.weight(1f)) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                                Spacer(Modifier.width(6.dp))
                                                Text(if (featuredCompleted) "Watch Again" else if (featuredResume > 1) "Resume Episode $featuredResume" else "Start Watching", maxLines = 1)
                                            }
                                            OutlinedButton(onClick = { toggleSaved(featured.title) }) {
                                                Icon(if (featured.title in saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = if (featured.title in saved) "Remove from My List" else "Add to My List")
                                            }
                                        }
                                        }
                                    }
                                }
                            }
                                    }

                                if (!homeSearchExpanded && discoverQuery.isBlank() && discoverGenre == "All" && unfinishedHistory.isNotEmpty()) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text("Continue Watching", fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                                                TextButton(onClick = { tab = 2; libraryFilter = "History" }) { Text("See all") }
                                            }
                                            Row(
                                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                unfinishedHistory.forEach { drama ->
                                                        val resume = PlaybackPolicy.resumeEpisode(
                                                            preferences.getInt("episode_" + drama.title, 1),
                                                            drama.episodes,
                                                            preferences.getStringSet("watched_" + drama.title, emptySet())?.toSet().orEmpty()
                                                        )
                                                        ElevatedCard(
                                                            onClick = { selectDrama(drama); selectEpisode(resume); tab = 0 },
                                                            colors = CardDefaults.elevatedCardColors(containerColor = AddisColors.elevated),
                                                            modifier = Modifier.width(196.dp),
                                                            shape = RoundedCornerShape(18.dp)
                                                        ) {
                                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                                                Box(Modifier.fillMaxWidth().height(106.dp).clip(RoundedCornerShape(12.dp)).background(AddisColors.posterPlaceholder)) {
                                                                    if (drama.posterUrl != null) AsyncImage(
                                                                        model = posterModel(drama.posterUrl, 320, 480),
                                                                        contentDescription = "Continue watching " + drama.title,
                                                                        contentScale = ContentScale.Crop,
                                                                        modifier = Modifier.fillMaxSize(),
                                                                        placeholder = ColorPainter(AddisColors.posterPlaceholder),
                                                                        error = ColorPainter(AddisColors.posterPlaceholder)
                                                                    ) else Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Gold, modifier = Modifier.align(Alignment.Center).size(36.dp))
                                                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA09090E)))))
                                                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume playback", tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp))
                                                                }
                                                                Text(drama.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                val mediaKey = (drama.id.ifBlank { drama.title }) + "_episode_" + resume
                                                                val savedPositionMs = playbackPreferences.getLong("position_" + mediaKey, 0L)
                                                                val savedDurationMs = playbackPreferences.getLong("duration_" + mediaKey, 0L)
                                                                val resumePositionLabel = if (savedPositionMs >= 5_000L)
                                                                    " · ${savedPositionMs / 60_000}:${((savedPositionMs / 1000) % 60).toString().padStart(2, '0')} saved" else ""
                                                                Text("Episode $resume of ${drama.episodes}$resumePositionLabel", fontSize = 12.sp, color = Gold, maxLines = 1)
                                                                val watchedEpisodes = preferences.getStringSet("watched_" + drama.title, emptySet())?.toSet().orEmpty()
                                                                val completedCount = (1..drama.episodes).count { it.toString() in watchedEpisodes }
                                                                LinearProgressIndicator(
                                                                    progress = { PlaybackPolicy.seriesProgress(watchedEpisodes, drama.episodes, resume, savedPositionMs, savedDurationMs) },
                                                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                                                    color = Gold,
                                                                    trackColor = AddisColors.border
                                                                )
                                                                Text("$completedCount of ${drama.episodes} episodes completed", fontSize = 11.sp, color = AddisColors.textSecondary)
                                                            }
                                                        }
                                                    }
                                            }
                                        }
                                    }
                                }
                                if (!homeSearchExpanded && discoverQuery.isBlank() && discoverGenre == "All" &&
                                    viewingHistory.isNotEmpty() && unfinishedHistory.isEmpty()) {
                                    item {
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .background(AddisColors.elevated, RoundedCornerShape(18.dp))
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Gold)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text("All caught up!", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                                Text("You've finished your recent stories. Find what to watch next.",
                                                    fontSize = 12.sp, color = AddisColors.textSecondary, lineHeight = 17.sp)
                                            }
                                            TextButton(onClick = { tab = 2; libraryFilter = "Completed" }) { Text("View") }
                                        }
                                    }
                                }
                                if (!homeSearchExpanded && discoverQuery.isBlank() && discoverGenre == "All" && catalog.isNotEmpty()) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("Picked for You", fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                                            Text(if (viewingHistory.isEmpty()) "Discover stories made for your next binge" else "Inspired by your viewing history", fontSize = 13.sp, color = AddisColors.textSecondary, lineHeight = 19.sp)
                                            Row(
                                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                personalized.take(10).forEach { drama ->
                                                    Card(
                                                        onClick = { selectDrama(drama); tab = 0 },
                                                        modifier = Modifier.width(142.dp),
                                                        shape = RoundedCornerShape(16.dp),
                                                        colors = CardDefaults.cardColors(containerColor = AddisColors.surface)
                                                    ) {
                                                        Column {
                                                            Box(Modifier.fillMaxWidth().height(198.dp).background(AddisColors.posterPlaceholder)) {
                                                                if (drama.posterUrl != null) {
                                                                    AsyncImage(
                                                                        model = posterModel(drama.posterUrl),
                                                                        contentDescription = "Poster for " + drama.title,
                                                                        contentScale = ContentScale.Crop,
                                                                        modifier = Modifier.fillMaxSize(),
                                                                        placeholder = ColorPainter(AddisColors.posterPlaceholder),
                                                                        error = ColorPainter(AddisColors.posterPlaceholder)
                                                                    )
                                                                } else {
                                                                    Icon(Icons.Default.Movie, contentDescription = null, tint = Gold, modifier = Modifier.align(Alignment.Center).size(48.dp))
                                                                }
                                                                Text(
                                                                    "${drama.episodes} EP",
                                                                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                                                                        .background(Color(0xCC141018), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                                                                    fontSize = 10.sp, color = Color.White
                                                                )
                                                            }
                                                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                                Text(drama.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                Text(drama.genre, fontSize = 11.sp, color = Gold)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (!homeSearchExpanded && discoverQuery.isBlank() && discoverGenre == "All" && catalog.size > 1) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text("More Stories to Love", fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                catalog.asReversed().take(10).forEach { drama ->
                                                    Card(
                                                        onClick = { detailDrama = drama },
                                                        modifier = Modifier.width(130.dp),
                                                        colors = CardDefaults.cardColors(containerColor = AddisColors.surface),
                                                        shape = RoundedCornerShape(14.dp)
                                                    ) {
                                                        Column {
                                                            Box(Modifier.fillMaxWidth().height(178.dp).background(AddisColors.posterPlaceholder)) {
                                                                if (drama.posterUrl != null) {
                                                                    AsyncImage(model = posterModel(drama.posterUrl), contentDescription = "Poster for " + drama.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                                                    placeholder = ColorPainter(AddisColors.posterPlaceholder),
                                                                    error = ColorPainter(AddisColors.posterPlaceholder))
                                                                } else {
                                                                    Icon(Icons.Default.Movie, contentDescription = null, tint = Gold, modifier = Modifier.align(Alignment.Center).size(42.dp))
                                                                }
                                                            }
                                                            Text(drama.title, modifier = Modifier.padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (!homeSearchExpanded && discoverQuery.isBlank() && discoverGenre == "All" && catalog.isNotEmpty()) {
                                    item {
                                        Card(
                                            onClick = { detailDrama = catalog.first() },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp),
                                            colors = CardDefaults.cardColors(containerColor = AddisColors.elevated)
                                        ) {
                                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("ADDISREEL ORIGINAL STORIES", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("Discover your next story", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                                Text("Explore short Ethiopian drama previews. New episodes are coming soon.", color = AddisColors.textSecondary, fontSize = 13.sp)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Gold)
                                                    Text("Explore featured series", color = Gold, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                                item { Text((if (homeSearchExpanded) "Search results · " else "All dramas · ") + "${filtered.size} titles" + if (discoverSort == "For You") " · Personalized on this device" else "", color = AddisColors.textSecondary) }
                                if (filtered.isEmpty()) {
                                    item {
                                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.SearchOff, contentDescription = null, tint = Gold, modifier = Modifier.size(44.dp))
                                            Spacer(Modifier.height(12.dp))
                                            Text("No stories found", fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
                                            Text("Try a different title, genre, or filter.", color = AddisColors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                                            TextButton(onClick = { discoverQuery = ""; discoverGenre = "All"; discoverOnlySaved = false; discoverOnlyUnwatched = false; discoverSort = "Featured" }) { Text("Clear filters") }
                                        }
                                    }
                                }
                                items(filtered) { drama ->
                                    DramaRow(
                                        drama,
                                        resumeEpisode = if (drama.title in viewingHistory) savedResumeEpisode(drama) else null
                                    ) { detailDrama = drama }
                                }
                            }
                        }
                    }
                    2 -> {
                        Header(stringResource(R.string.library_heading))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("Saved", "History", "Completed").forEach { filter ->
                                AddisChoiceChip(
                                    selected = libraryFilter == filter,
                                    onClick = { libraryFilter = filter },
                                    label = { Text(filter) }
                                )
                            }
                        }
                        if (libraryFilter == "History" && viewingHistory.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = {
                                    confirmClearHistory = true
                                }) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Clear viewing history")
                                }
                            }
                        }
                        if (libraryFilter == "Saved") catalog.find { it.title == recentlyWatched }?.let { recent ->
                            val recentWatched = preferences.getStringSet("watched_" + recent.title, emptySet())?.toSet().orEmpty()
                            val recentCompleted = PlaybackPolicy.isFullyWatched(recentWatched, recent.episodes)
                            val resumeEpisode = PlaybackPolicy.resumeEpisode(
                                preferences.getInt("episode_" + recent.title, 1), recent.episodes, recentWatched
                            )
                            Card(
                                onClick = { selectDrama(recent); selectEpisode(if (recentCompleted) 1 else resumeEpisode); tab = 0 },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = AddisColors.elevated),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Gold, modifier = Modifier.size(36.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if (recentCompleted) "WATCH AGAIN" else "CONTINUE WATCHING", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(recent.title, fontWeight = FontWeight.Bold)
                                        Text(if (recentCompleted) "Start again from episode 1" else "Resume episode $resumeEpisode of ${recent.episodes}", fontSize = 12.sp, color = AddisColors.textSecondary)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = if (recentCompleted) "Watch series again" else "Resume series", tint = Gold)
                                }
                            }
                        }
                        // Recompose when a completed episode is persisted.
                        val completionRevision = libraryCompletionRevision
                        val libraryTitles = when (libraryFilter) {
                            "History" -> viewingHistory.mapNotNull { title -> catalog.find { it.title == title } }
                            "Completed" -> catalog.filter { drama ->
                                // Read the revision so completion updates invalidate this view.
                                @Suppress("UNUSED_VARIABLE") val revision = completionRevision
                                val completed = preferences.getStringSet("watched_" + drama.title, emptySet())?.toSet().orEmpty()
                                drama.episodes > 0 && (1..drama.episodes).all { it.toString() in completed }
                            }
                            else -> catalog.filter { it.title in saved }
                        }
                        if (libraryTitles.isEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(if (libraryFilter == "History") Icons.Default.History else if (libraryFilter == "Completed") Icons.Default.CheckCircle else Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(52.dp), tint = Gold)
                                Text(when (libraryFilter) { "History" -> "No recently watched dramas"; "Completed" -> "No completed dramas yet"; else -> "Your collection starts here" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(when (libraryFilter) { "History" -> "Stories you start watching will appear here."; "Completed" -> "Complete a series to add it to your finished stories."; else -> "Bookmark the stories you love and return anytime." }, color = AddisColors.textSecondary)
                                Button(onClick = { tab = 1 }) { Text("Discover Stories") }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item { Text("${libraryTitles.size} ${libraryFilter.lowercase()} dramas", color = AddisColors.textSecondary) }
                                items(libraryTitles) { drama ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val started = drama.title in viewingHistory
                                        val fullyWatched = PlaybackPolicy.isFullyWatched(
                                            preferences.getStringSet("watched_" + drama.title, emptySet())?.toSet().orEmpty(),
                                            drama.episodes
                                        )
                                        val continueEpisode = savedResumeEpisode(drama)
                                        DramaRow(drama, resumeEpisode = if (started) continueEpisode else null) {
                                            selectDrama(drama); tab = 0
                                        }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                when {
                                                    fullyWatched -> "Watch again · Episode 1"
                                                    started -> "Continue · Episode $continueEpisode of ${drama.episodes}"
                                                    else -> "Start watching · Episode 1"
                                                }, color = Gold, fontSize = 12.sp
                                            )
                                            if (libraryFilter == "Saved") {
                                                TextButton(onClick = { toggleSaved(drama.title) }) {
                                                    Icon(Icons.Default.BookmarkRemove, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Text("Remove")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        // Keep the updater reachable on short screens and at large font sizes.
                        // The bottom navigation remains outside this independently scrollable pane.
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AddisScreenTitle(stringResource(R.string.premium_heading), "Stories worth staying for.")
                            // Updates are essential app maintenance, not a premium purchase.
                            // Put them first so the install action is visible without scrolling.
                            AddisAppearancePicker()
                            HorizontalDivider()
                            InAppUpdatePanel()
                            HorizontalDivider()
                            Icon(Icons.Default.Diamond, null, Modifier.size(48.dp), tint = Gold)
                            Text("Unlock more Ethiopian stories", fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
                            Text("Premium checkout with Ethiopian payment methods is planned. Payments are not enabled in this preview.", color = AddisColors.textSecondary, lineHeight = 22.sp)
                            Card {
                                Column(Modifier.padding(20.dp)) {
                                    Text("Coming soon", color = Gold, fontWeight = FontWeight.Bold)
                                    Text("telebirr • Ethiopian banks • local payment gateway")
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Header(title: String) { AddisScreenTitle(title) }
@Composable private fun DramaRow(drama: Drama, resumeEpisode: Int? = null, onClick: () -> Unit) {
    val posterColors = when (drama.genre) {
        "Romance" -> listOf(Color(0xFF70334E), Color(0xFF24121F))
        "Mystery" -> listOf(Color(0xFF244B5B), Color(0xFF101C29))
        "Family" -> listOf(Color(0xFF6A512E), Color(0xFF241B17))
        else -> listOf(Color(0xFF543E66), Color(0xFF1B1729))
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AddisColors.elevated)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(width = 112.dp, height = 160.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.verticalGradient(posterColors))
            ) {
                if (drama.posterUrl != null) {
                    AsyncImage(
                        model = posterModel(drama.posterUrl),
                        contentDescription = "Poster for " + drama.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(38.dp), tint = Gold)
                        Spacer(Modifier.height(8.dp))
                        Text("ADDISREEL", fontSize = 9.sp, color = Gold, fontWeight = FontWeight.Bold)
                        Text("Poster coming soon", fontSize = 8.sp, color = AddisColors.textSecondary)
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(54.dp)
                        .align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD09090E))))
                )
                Text(
                    if (resumeEpisode != null) "CONTINUE · EP $resumeEpisode" else "${drama.episodes} EPISODES",
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 9.dp, bottom = 9.dp),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (drama.id.isNotBlank()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(7.dp),
                        color = Color(0xD9211C12),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "STREAM",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            color = Gold,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(drama.title, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(drama.genre.uppercase(), color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${drama.episodes} short episodes", fontSize = 12.sp, color = AddisColors.textSecondary)
                if (resumeEpisode != null) {
                    Text("Continue · Episode $resumeEpisode of ${drama.episodes}", fontSize = 11.sp, color = Gold)
                    LinearProgressIndicator(
                        progress = { (resumeEpisode - 1).toFloat() / drama.episodes.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Gold,
                        trackColor = AddisColors.border
                    )
                }
                Text(drama.description, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = AddisColors.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = Gold)
                    Spacer(Modifier.width(5.dp))
                    Text("View series details", fontSize = 11.sp, color = Gold)
                }
            }
        }
    }
}
@Composable private fun Video(url:String, episodeKey:String, fillVideo: Boolean, subtitlesEnabled: Boolean, quality: String, playbackSpeed: Float, nextEpisodeUrl: String?, nextEpisodeFetchedAtMs: Long, hasNextEpisode: Boolean, autoPlayNext: Boolean, onAutoNextToggle: () -> Unit, onPlaybackUrlExpired: (Int) -> Unit, onPrepareNext: () -> Unit, onPlaybackStarted: () -> Unit, onWatchedToEnd: () -> Unit, onEpisodeCompleted: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("addisreel_playback", android.content.Context.MODE_PRIVATE) }
    // Stable episode identity survives refreshed or signed playback URLs.
    val positionKey = remember(episodeKey) { "position_" + episodeKey }
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    fun hasInternet(): Boolean {
        val active = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(active)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    var isOnline by remember(url, episodeKey) { mutableStateOf(hasInternet()) }
    var isMetered by remember(url, episodeKey) { mutableStateOf(connectivity.isActiveNetworkMetered) }
    DisposableEffect(connectivity, url, episodeKey) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                // Query the active network rather than a stale network that may
                // still deliver callbacks during Wi-Fi/mobile handover.
                isOnline = hasInternet()
                isMetered = connectivity.isActiveNetworkMetered
            }
            override fun onLost(network: Network) {
                isOnline = hasInternet()
                isMetered = connectivity.isActiveNetworkMetered
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    val player = remember(url, episodeKey) {
        // Prefer adaptive bitrate on weak or metered networks. The HLS master
        // playlist must offer multiple renditions for quality switching to work.
        val metered = connectivity.isActiveNetworkMetered
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoBitrate(
                PlaybackPolicy.maxVideoBitrate("Auto", metered)
            ))
        }
        // Keep enough video buffered for variable mobile networks without excessive data use.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (metered) 8_000 else 15_000,  // lower memory/data buffering on metered links
                if (metered) 25_000 else 50_000,
                1_000, // start playback promptly
                2_500  // resume after rebuffering
            )
            .build()
        // Cache only media bytes actually requested by the viewer.
        // LRU eviction caps storage at 80 MiB; no whole-episode predownloads.
        val cacheFactory = CacheDataSource.Factory()
            .setCache(StreamCache.get(context))
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(8_000)
                    .setReadTimeoutMs(15_000)
                    .setUserAgent("AddisReel-Android/1.0")
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
            setMediaItem(MediaItem.fromUri(url))
            val savedPosition = PlaybackPolicy.safeResumePositionMs(
                preferences.getLong(positionKey, 0L),
                preferences.getLong("duration_" + episodeKey, 0L)
            )
            if (savedPosition > 0L) seekTo(savedPosition)
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
    }
    LaunchedEffect(player, playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
    }
    LaunchedEffect(player, subtitlesEnabled) {
        val selector = player.trackSelector as? DefaultTrackSelector
        selector?.setParameters(
            selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
        )
    }
    // React to Wi-Fi/mobile handover without replacing the player or losing
    // the current position. Unmetered connections regain unrestricted ABR.
    LaunchedEffect(player, isMetered, quality) {
        val selector = player.trackSelector as? DefaultTrackSelector
        val bitrateLimit = PlaybackPolicy.maxVideoBitrate(quality, isMetered)
        selector?.setParameters(selector.buildUponParameters().setMaxVideoBitrate(bitrateLimit))
    }
    var buffering by remember(url, episodeKey) { mutableStateOf(true) }
    // Avoid flashing a loading spinner for brief, normal buffer transitions.
    var showBuffering by remember(url, episodeKey) { mutableStateOf(false) }
    LaunchedEffect(buffering, url, episodeKey) {
        if (buffering) {
            delay(450L)
            showBuffering = true
        } else {
            showBuffering = false
        }
    }
    var playbackError by remember(url, episodeKey) { mutableStateOf(false) }
    var retryCount by remember(url, episodeKey) { mutableIntStateOf(0) }
    var retryPending by remember(url, episodeKey) { mutableStateOf(false) }
    var credentialRejected by remember(url, episodeKey) { mutableStateOf(false) }
    var failurePositionMs by remember(url, episodeKey) {
        mutableLongStateOf(preferences.getLong(positionKey, 0L))
    }
    var inForeground by remember(url, episodeKey) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var observedOutage by remember(url, episodeKey) { mutableStateOf(!isOnline) }
    LaunchedEffect(isOnline) {
        if (!isOnline) {
            observedOutage = true
        } else if (observedOutage) {
            // A restored connection deserves a fresh, bounded retry budget.
            retryCount = 0
            observedOutage = false
        }
    }
    // A real video must have played for at least three seconds before it can
    // enter Viewing History. Tapping cards, previews and unavailable streams
    // never influence history or recommendation preferences.
    val latestPlaybackStarted by rememberUpdatedState(onPlaybackStarted)
    LaunchedEffect(player, episodeKey) {
        var recorded = false
        while (true) {
            delay(1_000L)
            if (!recorded && PlaybackPolicy.shouldRecordWatch(
                    player.isPlaying, player.currentPosition
                )) {
                recorded = true
                latestPlaybackStarted()
            }
        }
    }
    // Persist a short-drama resume point even when Android terminates the process
    // without delivering an activity stop or a Compose disposal callback.
    LaunchedEffect(player, positionKey) {
        while (true) {
            delay(5_000L)
            if (player.isPlaying && player.playbackState != Player.STATE_ENDED) {
                val position = player.currentPosition
                val duration = player.duration
                if (position > 0L && (duration <= 0L || position < duration - 1_000L)) {
                    preferences.edit().apply {
                        putLong(positionKey, position)
                        if (duration > 0L) putLong("duration_" + episodeKey, duration)
                    }.apply()
                }
            }
        }
    }
    // ExoPlayer can remain in BUFFERING without emitting an error on weak
    // connections. Detect a prolonged stall and use the same bounded retry path.
    LaunchedEffect(buffering, isOnline, inForeground, playbackError, player) {
        if (buffering && isOnline && inForeground && player.playWhenReady && !playbackError && !credentialRejected) {
            delay(15_000L)
            if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && !playbackError) {
                failurePositionMs = player.currentPosition.coerceAtLeast(0L)
                if (failurePositionMs > 0L) {
                    preferences.edit().putLong(positionKey, failurePositionMs).apply()
                }
                playbackError = true
            }
        }
    }
    // Bring a short-lived next-episode token up to date at the last 35 seconds.
    // Download at most the first 256 KiB of the next MP4 near its start window,
    // on validated unmetered networks only. HLS is URL-prefetched, not downloaded.
    val latestPrepareNext by rememberUpdatedState(onPrepareNext)
    LaunchedEffect(player, hasNextEpisode, episodeKey) {
        if (hasNextEpisode) {
            while (true) {
                delay(1_000L)
                val duration = player.duration
                val remaining = duration - player.currentPosition
                if (player.isPlaying && duration > 0L && remaining in 1L..35_000L) {
                    latestPrepareNext()
                    break
                }
            }
        }
    }
    LaunchedEffect(player, nextEpisodeUrl, nextEpisodeFetchedAtMs, isOnline, isMetered, inForeground) {
        val upcoming = nextEpisodeUrl
        if (upcoming != null && hasNextEpisode && isOnline && !isMetered && inForeground) {
            while (true) {
                delay(1_000L)
                val duration = player.duration
                val remaining = duration - player.currentPosition
                if (player.isPlaying && duration > 0L && remaining in 1L..25_000L) {
                    // Do not spend bandwidth warming a link that may have expired.
                    if (PlaybackPolicy.freshPlaybackLink(android.os.SystemClock.elapsedRealtime(), nextEpisodeFetchedAtMs, 45_000L)) {
                        NextEpisodePreloader.warmNextEpisodeOnUnmeteredNetwork(context, upcoming, StreamCache.get(context))
                    }
                    break
                }
            }
        }
    }
    var playbackEnded by remember(url, episodeKey) { mutableStateOf(false) }
    var nextCountdown by remember(url, episodeKey) { mutableIntStateOf(3) }
    var nextRequested by remember(url, episodeKey) { mutableStateOf(false) }
    val currentOnEpisodeCompleted by rememberUpdatedState(onEpisodeCompleted)
    val currentOnWatchedToEnd by rememberUpdatedState(onWatchedToEnd)
    val currentOnPlaybackUrlExpired by rememberUpdatedState(onPlaybackUrlExpired)
    LaunchedEffect(playbackEnded, hasNextEpisode, autoPlayNext, episodeKey) {
        if (playbackEnded && hasNextEpisode && autoPlayNext && !nextRequested) {
            nextCountdown = 3
            while (nextCountdown > 0 && !nextRequested) {
                delay(1000L)
                if (!nextRequested) nextCountdown -= 1
            }
            if (!nextRequested) {
                nextRequested = true
                currentOnEpisodeCompleted()
            }
        }
    }
    LaunchedEffect(playbackError, retryCount, isOnline, inForeground, player) {
        if (playbackError && isOnline && inForeground && !credentialRejected && retryCount < 3) {
            retryPending = true
            // Back off repeated failures; never spin endlessly on a bad stream.
            delay(when (retryCount) { 0 -> 800L; 1 -> 2000L; else -> 4000L })
            if (isOnline && inForeground) {
                retryCount += 1
                player.seekTo(failurePositionMs)
                player.prepare()
                player.playWhenReady = true
                buffering = true
                playbackError = false
            }
            retryPending = false
        } else {
            retryPending = false
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        var completed = false
        var wasPlayingBeforeBackground = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                playbackEnded = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) {
                    retryCount = 0
                    playbackError = false
                    credentialRejected = false
                    // A replayed episode must save its position again if interrupted.
                    if (completed && player.currentPosition < 1000L) completed = false
                }
                if (state == Player.STATE_ENDED && !completed) {
                    completed = true
                    preferences.edit().remove(positionKey).remove("duration_" + episodeKey).apply()
                    currentOnWatchedToEnd()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Expired signed MP4/HLS URLs cannot be fixed by repeatedly
                // retrying the same URL: fetch a fresh link once instead.
                val httpStatus = generateSequence(error as Throwable?) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                if (httpStatus == 401 || httpStatus == 403) {
                    credentialRejected = true
                    currentOnPlaybackUrlExpired(httpStatus)
                }
                failurePositionMs = player.currentPosition.coerceAtLeast(0L)
                if (failurePositionMs > 0L) {
                    preferences.edit().putLong(positionKey, failurePositionMs).apply()
                }
                buffering = false
                playbackError = true
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    inForeground = true
                    if (wasPlayingBeforeBackground && !playbackError && !completed) {
                        player.play()
                    }
                    wasPlayingBeforeBackground = false
                }
                Lifecycle.Event.ON_STOP -> {
                    inForeground = false
                    wasPlayingBeforeBackground = player.playWhenReady && !completed
                    if (!completed && player.currentPosition > 0L) {
                        preferences.edit().putLong(positionKey, player.currentPosition).apply()
                    }
                    player.pause()
                }
                else -> Unit
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            if (!completed && player.currentPosition > 0L) {
                preferences.edit().putLong(positionKey, player.currentPosition).apply()
            }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 2500
                    controllerHideOnTouch = true
                    resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                }
            },
            // Compose may reuse the existing Android view when an episode changes.
            // Always bind the currently active player instead of retaining the
            // released player from the previous episode.
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = if (fillVideo) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize()
        )
        if (showBuffering && buffering && !playbackError && inForeground) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AddisCinema.accent)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isOnline) "Buffering your episode…" else "Waiting for an internet connection…",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
        if (playbackEnded && !playbackError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (hasNextEpisode) {
                    Text(if (autoPlayNext) "Next episode in $nextCountdown" else "Episode complete", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        if (!nextRequested) {
                            nextRequested = true
                            currentOnEpisodeCompleted()
                        }
                    }) {
                        Icon(Icons.Default.SkipNext, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play next now")
                    }
                    TextButton(onClick = onAutoNextToggle) {
                        Text(if (autoPlayNext) "Cancel autoplay" else "Enable autoplay", color = AddisCinema.accent)
                    }
                    TextButton(onClick = {
                        nextRequested = false
                        playbackEnded = false
                        nextCountdown = 3
                        player.seekTo(0)
                        player.play()
                    }) { Text("Replay this episode", color = Color.White) }
                } else {
                    Text("You finished this series", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        playbackEnded = false
                        nextRequested = false
                        nextCountdown = 3
                        player.seekTo(0)
                        player.play()
                    }) {
                        Icon(Icons.Default.Replay, contentDescription = null)
                        Text("Replay episode")
                    }
                }
            }
        }
        if (playbackError) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    when {
                        !isOnline -> "You're offline. Playback will resume when your connection returns." 
                        credentialRejected -> "Your viewing session expired. Refreshing the episode link…"
                        !inForeground -> "Playback paused in the background"
                        retryPending -> "Connection interrupted — reconnecting…"
                        retryCount >= 3 -> "Can't play right now. Check your connection and try again."
                        else -> "Video interrupted. Preparing to reconnect…"
                    },
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                if (retryPending && isOnline) {
                    CircularProgressIndicator(color = AddisCinema.accent, modifier = Modifier.size(24.dp))
                } else if (isOnline && inForeground && !credentialRejected) {
                    Button(onClick = {
                        retryCount = 0
                        failurePositionMs = player.currentPosition.coerceAtLeast(failurePositionMs)
                        player.seekTo(failurePositionMs)
                        player.prepare()
                        player.playWhenReady = true
                        buffering = true
                        playbackError = false
                    }) { Text("Try again") }
                }
            }
        }
    }
}
