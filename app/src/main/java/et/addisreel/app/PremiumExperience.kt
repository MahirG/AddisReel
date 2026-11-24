package et.addisreel.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

internal data class PremiumPremiere(
    val id: String,
    val title: String,
    val genre: String,
    val utc: Long,
    val readyEpisodes: Int
)

internal object PremiereClock {
    fun parseUtc(value: String): Long? {
        if(!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$").matches(value)) return null
        return runCatching {
            val format=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply {
                timeZone=TimeZone.getTimeZone("UTC"); isLenient=false
            }
            val valueDate=format.parse(value)?:return null
            if(format.format(valueDate)==value) valueDate.time else null
        }.getOrNull()
    }
    fun remaining(target: Long, now: Long): String {
        val secs=max(0L,(target-now+999L)/1000L)
        val days=secs/86400L
        val hours=secs%86400L/3600L
        val minutes=secs%3600L/60L
        val seconds=secs%60L
        return (if(days>0L) days.toString()+"d  " else "")+
            String.format(Locale.US,"%02d:%02d:%02d",hours,minutes,seconds)
    }
}

private const val PREMIERE_API="https://addisreel-catalog.eddieaman475.workers.dev/v1/premieres"
private const val SAVED_PREFS="addisreel_premiere_bookmarks"

private fun fetchPremieres():List<PremiumPremiere>{
    val connection=URL(PREMIERE_API).openConnection() as HttpURLConnection
    connection.connectTimeout=7000
    connection.readTimeout=7000
    connection.requestMethod="GET"
    connection.setRequestProperty("Accept","application/json")
    connection.setRequestProperty("Cache-Control","no-cache")
    try {
        check(connection.responseCode==200)
        val data=JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            .getJSONArray("premieres")
        return buildList {
            for(i in 0 until minOf(data.length(),24)){
                val item=data.optJSONObject(i)?:continue
                val id=item.optString("id")
                val title=item.optString("title")
                val date=PremiereClock.parseUtc(item.optString("premiereAtUtc"))?:continue
                if(!Regex("^[a-z0-9][a-z0-9-]{0,63}$").matches(id) || title.isBlank())continue
                add(PremiumPremiere(id,title.take(150),item.optString("genre").take(80),
                    date,item.optInt("readyEpisodes",0).coerceAtLeast(0)))
            }
        }
    }finally{connection.disconnect()}
}

private fun displayTime(utc:Long,addis:Boolean=false):String{
    return SimpleDateFormat("EEE, d MMM · h:mm a",Locale.getDefault()).apply {
        if(addis) timeZone=TimeZone.getTimeZone("Africa/Addis_Ababa")
    }.format(Date(utc))
}

private fun openStaff(context:Context,route:String){
    context.startActivity(Intent(context,CreatorStudioActivity::class.java)
        .putExtra(CreatorStudioActivity.EXTRA_INITIAL_ROUTE,route))
}

@Composable
internal fun PremiumExperience(onExplore:()->Unit){
    var section by remember { mutableIntStateOf(0) }
    Column(
        modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal=AddisSpacing.standard,vertical=AddisSpacing.standard),
        verticalArrangement=Arrangement.spacedBy(AddisSpacing.comfortable)
    ){
        PremiumHero(onExplore=onExplore,onUpdates={section=2})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){
            listOf(
                R.string.premium_section_premieres,
                R.string.premium_section_studio,
                R.string.premium_section_settings
            ).forEachIndexed{ index,textId->
                FilterChip(
                    selected=section==index,
                    onClick={section=index},
                    label={Text(stringResource(textId),maxLines=1,overflow=TextOverflow.Ellipsis,
                        style=MaterialTheme.typography.labelSmall)},
                    modifier=Modifier.weight(1f),
                    leadingIcon=if(section==index){{Icon(Icons.Default.CheckCircle,null,Modifier.size(16.dp))}}else null
                )
            }
        }
        when(section){
            0->{PremiumPremieres();PremiumMembership()}
            1->PremiumStudio()
            else->{
                PremiumHeading(
                    stringResource(R.string.premium_section_settings),
                    stringResource(R.string.premium_settings_description)
                )
                InAppUpdatePanel()
                HorizontalDivider(color=AddisColors.border)
                AddisAppearancePicker()
            }
        }
        Text(stringResource(R.string.premium_footer_note),style=MaterialTheme.typography.labelSmall,
            color=AddisColors.textMuted,modifier=Modifier.padding(bottom=18.dp))
    }
}

@Composable
private fun PremiumHero(onExplore:()->Unit,onUpdates:()->Unit){
    Card(shape=RoundedCornerShape(26.dp),
        colors=CardDefaults.cardColors(containerColor=Color(0xFF1C1721)),
        modifier=Modifier.fillMaxWidth()){
        Column(Modifier.background(Brush.linearGradient(listOf(
            Color(0xFF17121B),Color(0xFF342338),Color(0xFF533B26))))
            .padding(22.dp),verticalArrangement=Arrangement.spacedBy(13.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
                Icon(Icons.Default.Diamond,null,Modifier.size(18.dp),tint=Color(0xFFFFDB91))
                Text(stringResource(R.string.premium_eyebrow),color=Color(0xFFFFDB91),
                    style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
            }
            Text(stringResource(R.string.premium_hero_title),color=Color.White,
                style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
            Text(stringResource(R.string.premium_hero_subtitle),
                color=Color(0xFFE7DCE8),style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){
                Button(onClick=onExplore,modifier=Modifier.weight(1f).heightIn(min=48.dp),
                    colors=ButtonDefaults.buttonColors(
                        containerColor=Color(0xFFFFDC91),contentColor=Color(0xFF24190C))){
                    Icon(Icons.Default.MovieFilter,null,Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.premium_browse),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick=onUpdates,modifier=Modifier.weight(1f).heightIn(min=48.dp)){
                    Icon(Icons.Default.CloudDownload,null,Modifier.size(17.dp),tint=Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.premium_updates_shortcut),maxLines=1,
                        overflow=TextOverflow.Ellipsis,color=Color.White)
                }
            }
        }
    }
}

@Composable
private fun PremiumHeading(title:String,subtitle:String){
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(title,style=MaterialTheme.typography.titleLarge,color=AddisColors.textPrimary)
        Text(subtitle,style=MaterialTheme.typography.bodySmall,color=AddisColors.textSecondary)
    }
}

@Composable
private fun PremiumPremieres(){
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences(SAVED_PREFS,Context.MODE_PRIVATE)}
    var saved by remember { mutableStateOf(prefs.getStringSet("saved",emptySet())?.toSet().orEmpty()) }
    var list by remember { mutableStateOf<List<PremiumPremiere>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var expanded by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh){
        loading=true;failed=false
        try{list=withContext(Dispatchers.IO){fetchPremieres()}}
        catch(_:Exception){failed=true}
        finally{loading=false}
    }
    LaunchedEffect(list){
        while(list.any{it.utc>tick}){
            delay(1000L)
            tick=System.currentTimeMillis()
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                PremiumHeading(stringResource(R.string.premium_premieres_heading),
                    stringResource(R.string.premium_premieres_description))
            }
            TextButton(onClick={refresh++},enabled=!loading){
                Icon(Icons.Default.Refresh,null,Modifier.size(18.dp))
                Text(stringResource(R.string.premium_refresh))
            }
        }
        when{
            loading->LinearProgressIndicator(Modifier.fillMaxWidth())
            failed->PremiumInfo(
                stringResource(R.string.premium_premieres_failed_title),
                stringResource(R.string.premium_premieres_failed),
                Icons.Default.ErrorOutline)
            list.isEmpty()->PremiumInfo(
                stringResource(R.string.premium_premieres_empty_title),
                stringResource(R.string.premium_premieres_empty_message),
                Icons.Default.Schedule)
            else->list.forEach{event->
                val isSaved=event.id in saved
                Card(shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth(),
                    colors=CardDefaults.cardColors(containerColor=AddisColors.surface)){
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(stringResource(R.string.premium_exclusive_label),
                                color=AddisColors.accent,style=MaterialTheme.typography.labelSmall,
                                fontWeight=FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(stringResource(R.string.premium_ready_episodes,event.readyEpisodes),
                                color=AddisColors.textSecondary,style=MaterialTheme.typography.labelSmall)
                        }
                        Text(event.title,style=MaterialTheme.typography.titleLarge,
                            color=AddisColors.textPrimary)
                        Text(displayTime(event.utc),style=MaterialTheme.typography.bodySmall,
                            color=AddisColors.textSecondary)
                        Text(
                            if(event.utc>tick)PremiereClock.remaining(event.utc,tick)
                            else stringResource(R.string.premium_launch_reached),
                            color=AddisColors.accent,style=MaterialTheme.typography.headlineSmall,
                            fontWeight=FontWeight.Bold
                        )
                        Row(verticalAlignment=Alignment.CenterVertically){
                            TextButton(onClick={
                                val value=if(isSaved)saved-event.id else saved+event.id
                                saved=value;prefs.edit().putStringSet("saved",value).apply()
                            }){
                                Icon(if(isSaved)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    null,Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(if(isSaved)R.string.premium_saved else R.string.premium_save))
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick={expanded=if(expanded==event.id)null else event.id}){
                                Text(stringResource(if(expanded==event.id)
                                    R.string.premium_hide_details else R.string.premium_details))
                                Icon(Icons.Default.ArrowForward,null,Modifier.size(16.dp))
                            }
                        }
                        if(expanded==event.id){
                            HorizontalDivider(color=AddisColors.border)
                            Text(stringResource(R.string.premium_eat_label,displayTime(event.utc,true)),
                                color=AddisColors.textSecondary,style=MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.premium_save_disclaimer),
                                color=AddisColors.textMuted,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumInfo(title:String,description:String,icon:androidx.compose.ui.graphics.vector.ImageVector){
    Card(shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=AddisColors.surface),
        modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Icon(icon,null,tint=AddisColors.accent)
            Text(title,style=MaterialTheme.typography.titleMedium,color=AddisColors.textPrimary)
            Text(description,style=MaterialTheme.typography.bodyMedium,color=AddisColors.textSecondary)
        }
    }
}

@Composable
private fun PremiumMembership(){
    var expanded by remember { mutableStateOf(false) }
    Card(modifier=Modifier.fillMaxWidth().clickable{expanded=!expanded},
        shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=AddisColors.elevated)){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                Icon(Icons.Default.Diamond,null,tint=AddisColors.accent)
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.premium_membership_title),
                    style=MaterialTheme.typography.titleMedium,color=AddisColors.textPrimary,
                    modifier=Modifier.weight(1f))
                Text(stringResource(R.string.premium_soon),color=AddisColors.accent,
                    style=MaterialTheme.typography.labelSmall)
            }
            Text(stringResource(R.string.premium_membership_desc),
                color=AddisColors.textSecondary)
            Text(stringResource(if(expanded)R.string.premium_hide_details else R.string.premium_details),
                color=AddisColors.accent,style=MaterialTheme.typography.labelMedium)
            if(expanded){
                HorizontalDivider(color=AddisColors.border)
                Text(stringResource(R.string.premium_payment_notice),
                    color=AddisColors.textSecondary,style=MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.premium_payment_methods),
                    color=AddisColors.textMuted,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PremiumStudio(){
    val context=LocalContext.current
    PremiumHeading(stringResource(R.string.premium_creator_heading),
        stringResource(R.string.premium_creator_description))
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        val routes=listOf(
            Triple(R.string.studio_section_content,R.string.premium_tool_content,"/admin"),
            Triple(R.string.studio_section_creators,R.string.premium_tool_creators,"/admin/creators"),
            Triple(R.string.studio_section_rights,R.string.premium_tool_rights,"/admin/rights"),
            Triple(R.string.studio_section_premieres,R.string.premium_tool_premieres,"/admin/premieres")
        )
        routes.forEachIndexed{index,(title,subtitle,path)->
            Surface(onClick={openStaff(context,path)},modifier=Modifier.fillMaxWidth().heightIn(min=78.dp),
                shape=RoundedCornerShape(18.dp),color=AddisColors.surface){
                Row(Modifier.padding(17.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Icon(
                        when(index){
                            0->Icons.Default.MovieFilter
                            1->Icons.Default.AdminPanelSettings
                            2->Icons.Default.Shield
                            else->Icons.Default.CalendarMonth
                        },contentDescription=null,tint=AddisColors.accent,modifier=Modifier.size(23.dp)
                    )
                    Column(Modifier.weight(1f)){
                        Text(stringResource(title),style=MaterialTheme.typography.titleMedium,
                            color=AddisColors.textPrimary)
                        Text(stringResource(subtitle),style=MaterialTheme.typography.bodySmall,
                            color=AddisColors.textSecondary)
                    }
                    Icon(Icons.Default.ArrowForward,
                        contentDescription=stringResource(R.string.premium_open),
                        tint=AddisColors.textSecondary)
                }
            }
        }
    }
    Text(stringResource(R.string.premium_owner_note),style=MaterialTheme.typography.bodySmall,
        color=AddisColors.textMuted)
}
