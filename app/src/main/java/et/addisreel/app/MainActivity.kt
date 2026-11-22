package et.addisreel.app

import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private val Gold = Color(0xFFFFC857)
private val Dark = Color(0xFF0C0C10)
private data class Drama(val title:String, val genre:String, val episodes:Int, val description:String, val url:String? = null)
private val catalog = listOf(
    Drama("Addis Love", "Romance", 12, "A story of love, family and difficult choices in Addis Ababa."),
    Drama("The Last Promise", "Drama", 8, "An Ethiopian family faces a life-changing secret."),
    Drama("Jimma Nights", "Mystery", 10, "A mystery unfolds over one unforgettable night."),
    Drama("Our Tomorrow", "Family", 6, "Two generations discover what truly matters.")
)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { AddisReelApp() }
    }
}
@Composable
private fun AddisReelApp() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("addisreel_library", android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableIntStateOf(0) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showFullDescription by remember { mutableStateOf(false) }
    var cinemaMode by remember { mutableStateOf(false) }
    var discoverQuery by remember { mutableStateOf("") }
    var discoverGenre by remember { mutableStateOf("All") }
    var discoverSort by remember { mutableStateOf("Featured") }
    var detailDrama by remember { mutableStateOf<Drama?>(null) }
    var selected by remember { mutableStateOf(catalog.first()) }
    var episode by remember { mutableIntStateOf(preferences.getInt("episode_" + selected.title, 1)) }
    var recentlyWatched by remember { mutableStateOf(preferences.getString("recently_watched", "") ?: "") }
    fun selectDrama(drama: Drama) {
        recentlyWatched = drama.title
        preferences.edit().putString("recently_watched", drama.title).apply()
        selected = drama
        showEpisodes = false
        showFullDescription = false
        cinemaMode = false
        episode = preferences.getInt("episode_" + drama.title, 1).coerceIn(1, drama.episodes)
    }
    fun selectEpisode(next: Int) {
        recentlyWatched = selected.title
        preferences.edit().putString("recently_watched", selected.title).apply()
        episode = next.coerceIn(1, selected.episodes)
        preferences.edit().putInt("episode_" + selected.title, episode).apply()
    }
    var saved by remember { mutableStateOf(preferences.getStringSet("saved_titles", emptySet())?.toSet() ?: emptySet()) }
    fun toggleSaved(title: String) {
        val updated = if (title in saved) saved - title else saved + title
        saved = updated
        preferences.edit().putStringSet("saved_titles", updated).apply()
    }
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
                            "Preview catalog · Video episodes will be available when licensed content is added.",
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
                    Spacer(Modifier.height(14.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items((1..selected.episodes).toList()) { number ->
                            val active = number == episode
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
                                        if (active) Icons.Default.PlayCircle else Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = if (active) Gold else Color.LightGray
                                    )
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("Episode $number", fontWeight = FontWeight.SemiBold)
                                        if (active) Text("Current episode", fontSize = 11.sp, color = Gold)
                                    }
                                    if (number < episode) Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Previously selected episode",
                                        tint = Color.Gray
                                    )
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
                        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101016))) {
                            val videoUrl = selected.url
                            if (videoUrl != null) {
                                Video(videoUrl, selected.title + "_episode_" + episode)
                            } else {
                                Column(
                                    Modifier.align(Alignment.Center).fillMaxWidth()
                                        .pointerInput(selected.title, episode) {
                                            var dragDistance = 0f
                                            detectVerticalDragGestures(
                                                onDragStart = { dragDistance = 0f },
                                                onVerticalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragDistance += dragAmount
                                                },
                                                onDragEnd = {
                                                    if (dragDistance < -90f && episode < selected.episodes) selectEpisode(episode + 1)
                                                    if (dragDistance > 90f && episode > 1) selectEpisode(episode - 1)
                                                    dragDistance = 0f
                                                },
                                                onDragCancel = { dragDistance = 0f }
                                            )
                                        }.padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Default.Movie, null, Modifier.size(74.dp), tint=Gold)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Ethiopian stories. Coming soon.", color=Color.LightGray)
                                    Text("Licensed episodes will play here",fontSize=12.sp,color=Color.Gray)
                                    Spacer(Modifier.height(16.dp))
                                    Text("Swipe up for next episode · down for previous", fontSize=11.sp, color=Color.Gray)
                                }
                            }
                            IconButton(onClick = { cinemaMode = !cinemaMode }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0x990C0C10), RoundedCornerShape(50))) {
                                Icon(if (cinemaMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, contentDescription = if (cinemaMode) "Exit cinema mode" else "Cinema mode", tint = Gold)
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
                                items(filtered) { drama -> DramaRow(drama) { detailDrama = drama } }
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
                                        DramaRow(drama) { selectDrama(drama); tab = 0 }
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
@Composable private fun DramaRow(drama: Drama, onClick: () -> Unit) {
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
                Modifier.size(width = 104.dp, height = 144.dp)
                    .background(Brush.verticalGradient(posterColors), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(38.dp), tint = Gold)
                    Spacer(Modifier.height(8.dp))
                    Text("ADDISREEL", fontSize = 9.sp, color = Gold, fontWeight = FontWeight.Bold)
                    Text("Poster coming soon", fontSize = 8.sp, color = Color.LightGray)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(drama.title, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(drama.genre.uppercase(), color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${drama.episodes} short episodes", fontSize = 12.sp, color = Color.LightGray)
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
@Composable private fun Video(url:String, episodeKey:String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("addisreel_playback", android.content.Context.MODE_PRIVATE) }
    val positionKey = remember(url, episodeKey) {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        "position_" + bytes.joinToString("") { "%02x".format(it) } + "_" + episodeKey
    }
    val player = remember(url, episodeKey) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            val savedPosition = preferences.getLong(positionKey, 0L)
            if (savedPosition > 0L) seekTo(savedPosition)
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    var buffering by remember(url, episodeKey) { mutableStateOf(true) }
    var playbackError by remember(url, episodeKey) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
            }
            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                playbackError = true
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (player.currentPosition > 0L) {
                    preferences.edit().putLong(positionKey, player.currentPosition).apply()
                }
                player.pause()
            }
        }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            if (player.currentPosition > 0L) {
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (buffering && !playbackError) {
            CircularProgressIndicator(color = Gold)
        }
        if (playbackError) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Video couldn't load", color = Color.White)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    playbackError = false
                    buffering = true
                    player.prepare()
                    player.playWhenReady = true
                }) { Text("Try again") }
            }
        }
    }
}
