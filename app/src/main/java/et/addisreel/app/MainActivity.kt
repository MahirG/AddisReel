package et.addisreel.app

import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

private val Gold = Color(0xFFFFC857)
private val Dark = Color(0xFF0C0C10)
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
private fun fetchPublishedCatalog(): List<Drama> {
    val result = catalogResponse("/v1/catalog").getJSONArray("series")
    return buildList {
        for (i in 0 until result.length()) {
            val item = result.getJSONObject(i)
            val id = item.getString("id")
            val episodeCount = item.optInt("episodes", 0)
            if (episodeCount > 0 && id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) {
                add(Drama(
                    title = item.getString("title"),
                    genre = item.getString("genre"),
                    episodes = episodeCount,
                    description = item.optString("description", ""),
                    id = id,
                    posterUrl = CATALOG_API + "/v1/posters/" + id
                ))
            }
        }
    }
}
private fun fetchEpisodeStream(id: String, episode: Int): String? {
    val url = catalogResponse("/v1/play/" + id + "/" + episode).optString("playbackUrl")
    return url.takeIf { it.startsWith(CATALOG_API + "/v1/media/") && it.endsWith(".m3u8") }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { AddisReelApp() }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddisReelApp() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("addisreel_library", android.content.Context.MODE_PRIVATE) }
    var catalog by remember { mutableStateOf(previewCatalog) }
    var remoteCatalogAvailable by remember { mutableStateOf(false) }
    var episodeUrl by remember { mutableStateOf<String?>(null) }
    // Prefetch only the next playback URL (not the video bytes) to avoid extra mobile data.
    var prefetchedEpisode by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showFullDescription by remember { mutableStateOf(false) }
    // Viewers can move between short episodes with a deliberate vertical swipe.
    // Keep the gesture on the player container so it works with real video too.
    var cinemaMode by remember { mutableStateOf(false) }
    // Default to the original frame so subtitles and credits are never cropped.
    var fillVideo by remember {
        mutableStateOf(preferences.getBoolean("cinema_fill_video", false))
    }
    var discoverQuery by remember { mutableStateOf("") }
    var discoverGenre by remember { mutableStateOf("All") }
    var discoverSort by remember { mutableStateOf("Featured") }
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
    var selected by remember { mutableStateOf(catalog.find { it.title == recentlyWatched } ?: catalog.first()) }
    var episode by remember { mutableIntStateOf(preferences.getInt("episode_" + selected.title, 1).coerceIn(1, selected.episodes)) }
    fun selectDrama(drama: Drama) {
        recentlyWatched = drama.title
        recordViewing(drama.title)
        preferences.edit().putString("recently_watched", drama.title).apply()
        selected = drama
        showEpisodes = false
        showFullDescription = false
        cinemaMode = false
        episode = preferences.getInt("episode_" + drama.title, 1).coerceIn(1, drama.episodes)
    }
    fun selectEpisode(next: Int) {
        recentlyWatched = selected.title
        recordViewing(selected.title)
        preferences.edit().putString("recently_watched", selected.title).apply()
        episode = next.coerceIn(1, selected.episodes)
        preferences.edit().putInt("episode_" + selected.title, episode).apply()
    }
    LaunchedEffect(Unit) {
        val remote = runCatching { withContext(Dispatchers.IO) { fetchPublishedCatalog() } }.getOrNull()
        if (!remote.isNullOrEmpty()) {
            catalog = remote
            remoteCatalogAvailable = true
            val restored = remote.find { it.title == recentlyWatched } ?: remote.first()
            selected = restored
            episode = preferences.getInt("episode_" + restored.title, 1).coerceIn(1, restored.episodes)
        }
    }
    var streamRefresh by remember { mutableIntStateOf(0) }
    var streamLoading by remember { mutableStateOf(false) }
    LaunchedEffect(selected.id, episode, streamRefresh) {
        episodeUrl = null
        streamLoading = selected.id.isNotBlank()
        val seriesId = selected.id
        val currentEpisode = episode
        if (seriesId.isNotBlank()) {
            val warm = prefetchedEpisode
            episodeUrl = if (streamRefresh == 0 && warm?.first == seriesId && warm.second == currentEpisode) {
                prefetchedEpisode = null
                warm.third
            } else {
                runCatching {
                    withContext(Dispatchers.IO) { fetchEpisodeStream(seriesId, currentEpisode) }
                }.getOrNull()
            }
            // Fetch the next episode's short-lived playback URL in advance.
            // Never pre-download media or retain URLs across app launches.
            streamLoading = false
            if (episodeUrl != null && currentEpisode < selected.episodes) {
                val nextUrl = runCatching {
                    withContext(Dispatchers.IO) { fetchEpisodeStream(seriesId, currentEpisode + 1) }
                }.getOrNull()
                prefetchedEpisode = nextUrl?.let { Triple(seriesId, currentEpisode + 1, it) }
            }
        }
    }
    var saved by remember { mutableStateOf(preferences.getStringSet("saved_titles", emptySet())?.toSet() ?: emptySet()) }
    fun toggleSaved(title: String) {
        val updated = if (title in saved) saved - title else saved + title
        saved = updated
        preferences.edit().putStringSet("saved_titles", updated).apply()
    }
    BackHandler(enabled = cinemaMode && tab == 0) { cinemaMode = false }
    MaterialTheme(colorScheme = darkColorScheme(primary=Gold, background=Dark, surface=Color(0xFF18181E))) {
        detailDrama?.let { drama ->
            AlertDialog(
                onDismissRequest = { detailDrama = null },
                icon = { Icon(Icons.Default.Movie, contentDescription = null, tint = Gold) },
                title = { Text(drama.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(drama.genre + "  •  " + drama.episodes + " episodes", color = Gold)
                        Text(drama.description)
                        Text(
                            if (drama.id.isBlank()) "Preview title · Streaming begins when licensed content is published." else "Licensed Ethiopian short-drama catalog.",
                            fontSize = 12.sp,
                            color = Color.LightGray
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
                containerColor = Color(0xFF1D1D24),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Gold) }
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Text(selected.title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("${selected.episodes} short episodes · Select an episode", color = Gold, fontSize = 12.sp)
                    val lastReached = preferences.getInt("episode_" + selected.title, 1).coerceIn(1, selected.episodes)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your progress · Episode $lastReached", color = Color.LightGray, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${((lastReached - 1) * 100 / selected.episodes.coerceAtLeast(1))}%", color = Gold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (lastReached - 1).toFloat() / selected.episodes.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Gold,
                        trackColor = Color(0xFF34343B)
                    )
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items((1..selected.episodes).toList()) { number ->
                            val active = number == episode
                            val watched = number < lastReached
                            Surface(
                                onClick = { selectEpisode(number); showEpisodes = false },
                                shape = RoundedCornerShape(14.dp),
                                color = if (active) Color(0xFF403526) else Color(0xFF292930),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (active) Icons.Default.PlayCircle else if (watched) Icons.Default.CheckCircle else Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = if (active || watched) Gold else Color.LightGray
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Episode $number", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            when {
                                                active -> "Now playing"
                                                watched -> "Previously reached"
                                                number == lastReached -> "Continue here"
                                                else -> "Tap to watch"
                                            },
                                            fontSize = 11.sp,
                                            color = if (active || watched || number == lastReached) Gold else Color.LightGray
                                        )
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }
        Scaffold(containerColor=Dark, bottomBar={
            if (!cinemaMode || tab != 0) NavigationBar(containerColor=Color(0xFF141419)) {
                listOf(Triple("Watch", Icons.Default.PlayCircle,0), Triple("Discover",Icons.Default.Search,1), Triple("Library",Icons.Default.Bookmark,2), Triple("Premium",Icons.Default.Diamond,3)).forEach { (name,icon,index) ->
                    NavigationBarItem(selected=tab==index,onClick={tab=index},icon={Icon(icon,contentDescription=name)},label={Text(name)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Gold,selectedTextColor=Gold,indicatorColor=Color(0xFF333027)))
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when(tab) {
                    0 -> {
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .background(Color(0xFF101016))
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
                            val videoUrl = if (selected.id.isNotBlank()) episodeUrl else selected.url
                            if (videoUrl != null) {
                                Video(videoUrl, (selected.id.ifBlank { selected.title }) + "_episode_" + episode, fillVideo = fillVideo, hasNextEpisode = episode < selected.episodes, onEpisodeCompleted = {
                                    if (episode < selected.episodes) selectEpisode(episode + 1)
                                })
                            } else {
                                Column(
                                    Modifier.align(Alignment.Center).fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Movie, null, Modifier.size(74.dp), tint=Gold)
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
                                        CircularProgressIndicator(color = Gold, modifier = Modifier.size(26.dp))
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
                                Icon(if (cinemaMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, contentDescription = if (cinemaMode) "Exit cinema mode" else "Cinema mode", tint = Gold)
                            }
                            // Cinema mode keeps episode navigation reachable without opening a sheet.
                            if (cinemaMode) {
                                Row(
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                        .padding(horizontal = 18.dp, vertical = 18.dp)
                                        .background(Color(0xCC101016), RoundedCornerShape(28.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { selectEpisode(episode - 1) },
                                        enabled = episode > 1
                                    ) {
                                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous episode", tint = if (episode > 1) Gold else Color.Gray)
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
                                        Icon(Icons.Default.SkipNext, contentDescription = "Next episode", tint = if (episode < selected.episodes) Gold else Color.Gray)
                                    }
                                    IconButton(onClick = { showEpisodes = true }) {
                                        Icon(Icons.Default.VideoLibrary, contentDescription = "All episodes", tint = Gold)
                                    }
                                    IconButton(onClick = {
                                        fillVideo = !fillVideo
                                        preferences.edit().putBoolean("cinema_fill_video", fillVideo).apply()
                                    }) {
                                        Icon(
                                            if (fillVideo) Icons.Default.FitScreen else Icons.Default.CropFree,
                                            contentDescription = if (fillVideo) "Fit entire video" else "Fill screen",
                                            tint = Gold
                                        )
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
                                Text("ADDISREEL  •  EPISODE $episode OF ${selected.episodes}", color=Gold, fontWeight=FontWeight.Bold,fontSize=12.sp)
                                Text(selected.title,fontSize=28.sp,fontWeight=FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(progress = { episode.toFloat() / selected.episodes.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Gold, trackColor = Color(0xFF34343B))
                                Text(selected.description, modifier = Modifier.clickable { showFullDescription = !showFullDescription }, maxLines = if (showFullDescription) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis, color=Color.LightGray)
                                Text(if (showFullDescription) "Show less" else "More about this story", modifier = Modifier.clickable { showFullDescription = !showFullDescription }, fontSize = 11.sp, color = Gold)
                                TextButton(onClick={showEpisodes=true}) { Icon(Icons.Default.List,contentDescription=null); Spacer(Modifier.width(6.dp)); Text("Episodes  •  $episode / ${selected.episodes}") }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick={selectEpisode(episode-1)},enabled=episode>1) { Icon(Icons.Default.SkipPrevious,contentDescription="Previous episode") }
                                    Button(onClick={selectEpisode(episode+1)},enabled=episode<selected.episodes) { Icon(Icons.Default.SkipNext,null); Text("Next episode") }

                                }
                            }
                        }
                        if (!cinemaMode) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            catalog.forEach { drama -> FilterChip(selected=selected==drama,onClick={selectDrama(drama)},label={Text(drama.title)}) }
                        }
                    }
                    1 -> {
                        Header("Discover")
                        if (!remoteCatalogAvailable) Text("Preview titles · Licensed streaming catalog is not yet available", modifier=Modifier.padding(horizontal=16.dp, vertical=4.dp), fontSize=11.sp, color=Gold)
                        val genres = remember { listOf("All") + catalog.map { it.genre }.distinct() }
                        val matches = catalog.filter { drama ->
                            (discoverGenre == "All" || drama.genre == discoverGenre) &&
                                (discoverQuery.isBlank() ||
                                    drama.title.contains(discoverQuery.trim(), ignoreCase = true) ||
                                    drama.genre.contains(discoverQuery.trim(), ignoreCase = true) ||
                                    drama.description.contains(discoverQuery.trim(), ignoreCase = true))
                        }
                        val filtered = when (discoverSort) {
                            "A–Z" -> matches.sortedBy { it.title }
                            "Most episodes" -> matches.sortedByDescending { it.episodes }
                            else -> matches
                        }
                        Column(Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = discoverQuery,
                                onValueChange = { discoverQuery = it },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                label = { Text("Search Ethiopian dramas") },
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
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                genres.forEach { genre ->
                                    FilterChip(
                                        selected = discoverGenre == genre,
                                        onClick = { discoverGenre = genre },
                                        label = { Text(genre) }
                                    )
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Sort, contentDescription = null, tint = Color.LightGray)
                                listOf("Featured", "A–Z", "Most episodes").forEach { sort ->
                                    FilterChip(
                                        selected = discoverSort == sort,
                                        onClick = { discoverSort = sort },
                                        label = { Text(sort) }
                                    )
                                }
                            }
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (discoverQuery.isBlank() && discoverGenre == "All" && viewingHistory.isNotEmpty()) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Continue Watching", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                            Row(
                                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                viewingHistory.mapNotNull { title -> catalog.find { it.title == title } }
                                                    .forEach { drama ->
                                                        val resume = preferences.getInt("episode_" + drama.title, 1)
                                                            .coerceIn(1, drama.episodes)
                                                        ElevatedCard(
                                                            onClick = { selectDrama(drama); tab = 0 },
                                                            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF302536)),
                                                            modifier = Modifier.width(180.dp)
                                                        ) {
                                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                                Text(drama.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                Text("Episode $resume of ${drama.episodes}", fontSize = 12.sp, color = Gold)
                                                                LinearProgressIndicator(
                                                                    progress = { (resume - 1).toFloat() / drama.episodes.coerceAtLeast(1) },
                                                                    modifier = Modifier.fillMaxWidth().height(3.dp),
                                                                    color = Gold,
                                                                    trackColor = Color(0xFF34343B)
                                                                )
                                                                Text("Resume watching", fontSize = 11.sp, color = Color.LightGray)
                                                            }
                                                        }
                                                    }
                                            }
                                        }
                                    }
                                }
                                if (discoverQuery.isBlank() && discoverGenre == "All") {
                                    item {
                                        Card(
                                            onClick = { detailDrama = catalog.first() },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(20.dp),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF302536))
                                        ) {
                                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("ADDISREEL ORIGINAL STORIES", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Text("Discover your next story", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                                Text("Explore short Ethiopian drama previews. New episodes are coming soon.", color = Color.LightGray, fontSize = 13.sp)
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Gold)
                                                    Text("Explore featured series", color = Gold, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                                item { Text("${filtered.size} dramas", color = Color.LightGray) }
                                if (filtered.isEmpty()) {
                                    item {
                                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.SearchOff, contentDescription = null, tint = Gold, modifier = Modifier.size(44.dp))
                                            Spacer(Modifier.height(12.dp))
                                            Text("No dramas found", fontWeight = FontWeight.Bold)
                                            Text("Try another title or genre", color = Color.LightGray)
                                            TextButton(onClick = { discoverQuery = ""; discoverGenre = "All" }) { Text("Clear filters") }
                                        }
                                    }
                                }
                                items(filtered) { drama ->
                                    DramaRow(
                                        drama,
                                        resumeEpisode = if (drama.title in viewingHistory) preferences.getInt("episode_" + drama.title, 1).coerceIn(1, drama.episodes) else null
                                    ) { detailDrama = drama }
                                }
                            }
                        }
                    }
                    2 -> {
                        Header("My Library")
                        catalog.find { it.title == recentlyWatched }?.let { recent ->
                            val resumeEpisode = preferences.getInt("episode_" + recent.title, 1).coerceIn(1, recent.episodes)
                            Card(
                                onClick = { selectDrama(recent); tab = 0 },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF302536)),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PlayCircle, contentDescription = null, tint = Gold, modifier = Modifier.size(36.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("CONTINUE WATCHING", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(recent.title, fontWeight = FontWeight.Bold)
                                        Text("Resume episode $resumeEpisode of ${recent.episodes}", fontSize = 12.sp, color = Color.LightGray)
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = "Resume series", tint = Gold)
                                }
                            }
                        }
                        if (saved.isEmpty()) {
                            Column(
                                Modifier.fillMaxWidth().padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(52.dp), tint = Gold)
                                Text("Your collection starts here", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("Save Ethiopian dramas to find them here later.", color = Color.LightGray)
                                Button(onClick = { tab = 1 }) { Text("Explore dramas") }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item { Text("${saved.size} saved series", color = Color.LightGray) }
                                items(catalog.filter { it.title in saved }) { drama ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        DramaRow(drama, resumeEpisode = preferences.getInt("episode_" + drama.title, 1).coerceIn(1, drama.episodes)) { selectDrama(drama); tab = 0 }
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val lastEpisode = preferences.getInt("episode_" + drama.title, 1)
                                                .coerceIn(1, drama.episodes)
                                            Text("Continue · Episode $lastEpisode of ${drama.episodes}", color = Gold, fontSize = 12.sp)
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
                    else -> {
                        Header("AddisReel Premium")
                        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.Diamond,null,Modifier.size(60.dp),tint=Gold)
                            Text("Unlock more Ethiopian stories",fontSize=26.sp,fontWeight=FontWeight.Bold)
                            Text("Premium checkout with Ethiopian payment methods is planned. Payments are not enabled in this preview.",color=Color.LightGray)
                            Card { Column(Modifier.padding(20.dp)) { Text("Coming soon",color=Gold,fontWeight=FontWeight.Bold); Text("telebirr • Ethiopian banks • local payment gateway") } }
                            HorizontalDivider()
                            InAppUpdatePanel()
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Header(title:String) { Text(title,Modifier.padding(20.dp),fontSize=28.sp,fontWeight=FontWeight.Bold,color=Color.White) }
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1D24))
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
                        model = drama.posterUrl,
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
                        Text("Poster coming soon", fontSize = 8.sp, color = Color.LightGray)
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
                Text("${drama.episodes} short episodes", fontSize = 12.sp, color = Color.LightGray)
                if (resumeEpisode != null) {
                    Text("Continue · Episode $resumeEpisode of ${drama.episodes}", fontSize = 11.sp, color = Gold)
                    LinearProgressIndicator(
                        progress = { (resumeEpisode - 1).toFloat() / drama.episodes.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = Gold,
                        trackColor = Color(0xFF34343B)
                    )
                }
                Text(drama.description, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Color.LightGray)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = Gold)
                    Spacer(Modifier.width(5.dp))
                    Text("View series details", fontSize = 11.sp, color = Gold)
                }
            }
        }
    }
}
@Composable private fun Video(url:String, episodeKey:String, fillVideo: Boolean, hasNextEpisode: Boolean, onEpisodeCompleted: () -> Unit) {
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
    DisposableEffect(connectivity, url, episodeKey) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                isOnline = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            override fun onLost(network: Network) {
                isOnline = hasInternet()
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
            if (metered) {
                setParameters(buildUponParameters().setMaxVideoBitrate(1_500_000))
            }
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
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build().apply {
            setMediaItem(MediaItem.fromUri(url))
            val savedPosition = preferences.getLong(positionKey, 0L)
            if (savedPosition > 0L) seekTo(savedPosition)
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
        }
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
    // Persist a short-drama resume point even when Android terminates the process
    // without delivering an activity stop or a Compose disposal callback.
    LaunchedEffect(player, positionKey) {
        while (true) {
            delay(5_000L)
            if (player.isPlaying && player.playbackState != Player.STATE_ENDED) {
                val position = player.currentPosition
                val duration = player.duration
                if (position > 0L && (duration <= 0L || position < duration - 1_000L)) {
                    preferences.edit().putLong(positionKey, position).apply()
                }
            }
        }
    }
    // ExoPlayer can remain in BUFFERING without emitting an error on weak
    // connections. Detect a prolonged stall and use the same bounded retry path.
    LaunchedEffect(buffering, isOnline, inForeground, playbackError, player) {
        if (buffering && isOnline && inForeground && player.playWhenReady && !playbackError) {
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
    var playbackEnded by remember(url, episodeKey) { mutableStateOf(false) }
    var nextCountdown by remember(url, episodeKey) { mutableIntStateOf(3) }
    var nextRequested by remember(url, episodeKey) { mutableStateOf(false) }
    val currentOnEpisodeCompleted by rememberUpdatedState(onEpisodeCompleted)
    LaunchedEffect(playbackEnded, hasNextEpisode, episodeKey) {
        if (playbackEnded && hasNextEpisode && !nextRequested) {
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
        if (playbackError && isOnline && inForeground && retryCount < 3) {
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
                    // A replayed episode must save its position again if interrupted.
                    if (completed && player.currentPosition < 1000L) completed = false
                }
                if (state == Player.STATE_ENDED && !completed) {
                    completed = true
                    preferences.edit().remove(positionKey).apply()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
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
        if (showBuffering && buffering && !playbackError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Gold)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (isOnline) "Preparing your episode…" else "Internet disconnected — waiting to reconnect",
                    color = Color.White,
                    fontSize = 13.sp
                )
            }
        }
        if (playbackEnded && !playbackError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (hasNextEpisode) {
                    Text("Next episode in $nextCountdown", color = Color.White, fontSize = 15.sp)
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
                        !isOnline -> "You're offline. Playback will retry when internet returns."
                        !inForeground -> "Playback paused in the background"
                        retryPending -> "Connection interrupted — reconnecting…"
                        retryCount >= 3 -> "Can't play right now. Check your connection and try again."
                        else -> "Video interrupted. Preparing to reconnect…"
                    },
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                if (retryPending && isOnline) {
                    CircularProgressIndicator(color = Gold, modifier = Modifier.size(24.dp))
                } else if (isOnline && inForeground) {
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
