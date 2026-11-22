package et.addisreel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
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
        super.onCreate(savedInstanceState)
        setContent { AddisReelApp() }
    }
}
@Composable
private fun AddisReelApp() {
    var tab by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(catalog.first()) }
    var episode by remember { mutableIntStateOf(1) }
    var saved by remember { mutableStateOf(setOf<String>()) }
    MaterialTheme(colorScheme = darkColorScheme(primary=Gold, background=Dark, surface=Color(0xFF18181E))) {
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
                                Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Movie, null, Modifier.size(74.dp), tint=Gold)
                                Spacer(Modifier.height(12.dp))
                                Text("Ethiopian stories. Coming soon.", color=Color.LightGray)
                                Text("Licensed episodes will play here",fontSize=12.sp,color=Color.Gray)
                                }
                            }
                            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                                Text("ADDISREEL  •  EPISODE $episode", color=Gold, fontWeight=FontWeight.Bold,fontSize=12.sp)
                                Text(selected.title,fontSize=28.sp,fontWeight=FontWeight.Bold)
                                Text(selected.description,maxLines=2,overflow=TextOverflow.Ellipsis,color=Color.LightGray)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Button(onClick={episode=if(episode<selected.episodes) episode+1 else 1}) { Icon(Icons.Default.SkipNext,null); Text("Next episode") }
                                    OutlinedButton(onClick={saved=if(selected.title in saved) saved-selected.title else saved+selected.title}) { Icon(if(selected.title in saved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,null) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            catalog.forEach { drama -> FilterChip(selected=selected==drama,onClick={selected=drama;episode=1},label={Text(drama.title)}) }
                        }
                    }
                    1 -> {
                        Header("Discover")
                        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            item { Text("Ethiopian short dramas",fontSize=20.sp,fontWeight=FontWeight.Bold) }
                            items(catalog) { drama -> DramaRow(drama) { selected=drama;episode=1;tab=0 } }
                        }
                    }
                    2 -> {
                        Header("My Library")
                        if(saved.isEmpty()) Text("Save a series to watch it later.",Modifier.padding(20.dp),color=Color.LightGray)
                        else LazyColumn { items(catalog.filter{it.title in saved}) { drama -> DramaRow(drama){selected=drama;tab=0} } }
                    }
                    else -> {
                        Header("AddisReel Premium")
                        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Default.Diamond,null,Modifier.size(60.dp),tint=Gold)
                            Text("Unlock more Ethiopian stories",fontSize=26.sp,fontWeight=FontWeight.Bold)
                            Text("Premium checkout with Ethiopian payment methods is planned. Payments are not enabled in this preview.",color=Color.LightGray)
                            Card { Column(Modifier.padding(20.dp)) { Text("Coming soon",color=Gold,fontWeight=FontWeight.Bold); Text("telebirr • Ethiopian banks • local payment gateway") } }
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
    val context=androidx.compose.ui.platform.LocalContext.current
    val player=remember(url) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(url));prepare();playWhenReady=true;repeatMode=ExoPlayer.REPEAT_MODE_ONE } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory={PlayerView(it).apply { this.player=player;useController=true }},modifier=Modifier.fillMaxSize())
}
