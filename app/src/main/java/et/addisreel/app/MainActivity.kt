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
    var discoverQuery by remember { mutableStateOf("") }
    var discoverGenre by remember { mutableStateOf("All") }
    var detailDrama by remember { mutableStateOf<Drama?>(null) }
    var selected by remember { mutableStateOf(catalog.first()) }
    var episode by remember { mutableIntStateOf(preferences.getInt("episode_" + selected.title, 1)) }
    fun selectDrama(drama: Drama) {
        selected = drama
        showEpisodes = false
        episode = preferences.getInt("episode_" + drama.title, 1).coerceIn(1, drama.episodes)
    }
    fun selectEpisode(next: Int) {
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
            AlertDialog(
                onDismissRequest = { showEpisodes = false },
                title = { Text(selected.title + " · Episodes") },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items((1..selected.episodes).toList()) { number ->
                            TextButton(
                                onClick = { selectEpisode(number); showEpisodes = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(if (number == episode) Icons.Default.PlayArrow else Icons.Default.Movie, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text("Episode $number", modifier = Modifier.weight(1f))
                                if (number == episode) Text("Current", color = Gold)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showEpisodes = false }) { Text("Close") } }
            )
        }
        Scaffold(containerColor=Dark, bottomBar={
            NavigationBar(containerColor=Color(0xFF141419)) {
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
                                Video(videoUrl)
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
                            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                                Text("ADDISREEL  •  EPISODE $episode OF ${selected.episodes}", color=Gold, fontWeight=FontWeight.Bold,fontSize=12.sp)
                                Text(selected.title,fontSize=28.sp,fontWeight=FontWeight.Bold)
                                Text(selected.description,maxLines=2,overflow=TextOverflow.Ellipsis,color=Color.LightGray)
                                TextButton(onClick={showEpisodes=true}) { Icon(Icons.Default.List,contentDescription=null); Spacer(Modifier.width(6.dp)); Text("Episodes  •  $episode / ${selected.episodes}") }
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick={selectEpisode(episode-1)},enabled=episode>1) { Icon(Icons.Default.SkipPrevious,contentDescription="Previous episode") }
                                    Button(onClick={selectEpisode(episode+1)},enabled=episode<selected.episodes) { Icon(Icons.Default.SkipNext,null); Text("Next episode") }
                                    OutlinedButton(onClick={toggleSaved(selected.title)}) { Icon(if(selected.title in saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,null) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            catalog.forEach { drama -> FilterChip(selected=selected==drama,onClick={selectDrama(drama)},label={Text(drama.title)}) }
                        }
                    }
                    1 -> {
                        Header("Discover")
                        val genres = remember { listOf("All") + catalog.map { it.genre }.distinct() }
                        val filtered = catalog.filter { drama ->
                            (discoverGenre == "All" || drama.genre == discoverGenre) &&
                                (discoverQuery.isBlank() ||
                                    drama.title.contains(discoverQuery.trim(), ignoreCase = true) ||
                                    drama.genre.contains(discoverQuery.trim(), ignoreCase = true) ||
                                    drama.description.contains(discoverQuery.trim(), ignoreCase = true))
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
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
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
                        if(saved.isEmpty()) Text("Save a series to watch it later.",Modifier.padding(20.dp),color=Color.LightGray)
                        else LazyColumn { items(catalog.filter{it.title in saved}) { drama -> DramaRow(drama){selectDrama(drama);tab=0} } }
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
@Composable private fun DramaRow(drama:Drama,onClick:()->Unit) {
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF202027)),shape=RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(width=92.dp,height=116.dp).background(Color(0xFF393039),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) { Icon(Icons.Default.Movie,null,Modifier.size(40.dp),tint=Gold) }
            Column { Text(drama.title,fontWeight=FontWeight.Bold,fontSize=20.sp);Text("${drama.genre} • ${drama.episodes} episodes",color=Gold,fontSize=12.sp); Spacer(Modifier.height(8.dp));Text(drama.description,maxLines=3,overflow=TextOverflow.Ellipsis,color=Color.LightGray,fontSize=13.sp) }
        }
    }
}
@Composable private fun Video(url:String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = remember(context) { context.getSharedPreferences("addisreel_playback", android.content.Context.MODE_PRIVATE) }
    val positionKey = remember(url) {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        "position_" + bytes.joinToString("") { "%02x".format(it) }
    }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            val savedPosition = preferences.getLong(positionKey, 0L)
            if (savedPosition > 0L) seekTo(savedPosition)
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    var buffering by remember(url) { mutableStateOf(true) }
    var playbackError by remember(url) { mutableStateOf(false) }
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
