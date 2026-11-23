package et.addisreel.studio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Premium native shell for the existing authenticated AddisReel Studio.
 * Only first-party HTTPS URLs can load inside the WebView. The owner's
 * deliberate Upload button launches a separate native background transfer.
 */
public final class StudioActivity extends Activity {
    private static final int PICK_NATIVE = 430;
    private static final int PICK_WEB = 431;
    private WebView web;
    private TextView transferTitle,transferText;
    private ProgressBar transferProgress;
    private Button uploadButton, pauseButton, resumeButton, activityBell; 
    private ActivityCenter activityCenter;
    private final ExecutorService processingExecutor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing=new AtomicBoolean(false);
    private long lastProcessingSync=0L;
    private ValueCallback<Uri[]> webFileCallback;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String chosenSeriesId="",chosenSeriesName="";
    private int chosenEpisodeNumber=0;
    private boolean refreshedFinished=false;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            refreshUploadState();
            if(System.currentTimeMillis()-lastProcessingSync>30000L)syncProcessing(false);
            handler.postDelayed(this,1700);
        }
    };
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
    private TextView label(String text,int size,int color){
        TextView view=new TextView(this);view.setText(text);view.setTextSize(size);
        view.setTextColor(color);return view;
    }
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(Color.rgb(18,17,22));
        getWindow().setNavigationBarColor(Color.rgb(18,17,22));
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(12,11,15));
        // Android 15+ draws apps edge to edge. Respect system bars and cutouts.
        if(Build.VERSION.SDK_INT>=35){
            root.setOnApplyWindowInsetsListener((view,insets)->{
                android.graphics.Insets safe=insets.getInsets(
                    WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                view.setPadding(safe.left,safe.top,safe.right,safe.bottom);
                return insets;
            });
        }
        LinearLayout top=new LinearLayout(this);
        top.setPadding(dp(16),dp(12),dp(14),dp(11));top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.rgb(29,26,33));
        LinearLayout textGroup=new LinearLayout(this);textGroup.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label("ADDISREEL",17,Color.rgb(246,204,140));
        heading.setTypeface(null,1);
        TextView subtitle=label("CONTENT STUDIO  •  PRIVATE",10,Color.rgb(183,177,175));
        textGroup.addView(heading);textGroup.addView(subtitle);
        top.addView(textGroup,new LinearLayout.LayoutParams(0,dp(52),1));
        activityBell=new Button(this);activityBell.setText("🔔");activityBell.setTextSize(15);
        activityBell.setContentDescription("Open Studio notifications and activity center");
        activityBell.setAllCaps(false);
        activityBell.setTextColor(Color.rgb(245,207,153));
        activityBell.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(53,45,48)));
        top.addView(activityBell,new LinearLayout.LayoutParams(dp(50),dp(45)));
        uploadButton=new Button(this);uploadButton.setText("↑ Upload MP4");uploadButton.setAllCaps(false);
        uploadButton.setTextSize(12);uploadButton.setTextColor(Color.rgb(36,25,17));
        uploadButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242,189,111)));
        top.addView(uploadButton,new LinearLayout.LayoutParams(dp(118),dp(45)));
        root.addView(top);

        LinearLayout status=new LinearLayout(this);status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);status.setPadding(dp(15),dp(7),dp(12),dp(7));
        status.setBackgroundColor(Color.rgb(34,29,33));
        LinearLayout transferLabels=new LinearLayout(this);
        transferLabels.setOrientation(LinearLayout.VERTICAL);
        transferLabels.setGravity(Gravity.CENTER_VERTICAL);
        transferTitle=label("STUDIO READY",13,Color.rgb(248,216,163));
        transferTitle.setTypeface(null,1);
        transferText=label("Tap here to view upload activity",12,Color.rgb(235,221,206));
        transferLabels.addView(transferTitle);
        transferLabels.addView(transferText);
        status.addView(transferLabels,new LinearLayout.LayoutParams(0,dp(64),1));
        status.setOnClickListener(v->openActivityCenter());
        status.setContentDescription("Live video upload, confirmed progress, and processing. Tap to open Activity Center.");
        pauseButton=new Button(this);pauseButton.setText("Pause");pauseButton.setTextSize(11);
        pauseButton.setAllCaps(false);pauseButton.setVisibility(View.GONE);
        status.addView(pauseButton,new LinearLayout.LayoutParams(dp(79),dp(42)));
        resumeButton=new Button(this);resumeButton.setText("Resume");resumeButton.setTextSize(11);
        resumeButton.setAllCaps(false);resumeButton.setVisibility(View.GONE);
        status.addView(resumeButton,new LinearLayout.LayoutParams(dp(85),dp(42)));
        root.addView(status);
        transferProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        transferProgress.setMax(100);transferProgress.setVisibility(View.GONE);
        transferProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242,189,111)));
        root.addView(transferProgress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(8)));

        web=new WebView(this);
        web.setBackgroundColor(Color.rgb(11,11,14));
        WebSettings settings=web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if(Build.VERSION.SDK_INT>=26)settings.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if("https".equalsIgnoreCase(uri.getScheme()) &&
                    "ar.eddieaman475.workers.dev".equalsIgnoreCase(uri.getHost()))return false;
                if("https".equalsIgnoreCase(uri.getScheme())){
                    try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(ActivityNotFoundException ignored){}
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,
                    FileChooserParams params){
                if(webFileCallback!=null)webFileCallback.onReceiveValue(null);
                webFileCallback=callback;
                try{startActivityForResult(params.createIntent(),PICK_WEB);return true;}
                catch(Exception error){webFileCallback=null;callback.onReceiveValue(null);return false;}
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        setContentView(root);
        uploadButton.setOnClickListener(v->requestNativeUpload());
        activityBell.setOnClickListener(v->openActivityCenter());
        pauseButton.setOnClickListener(v->{UploadController.pause(this);refreshUploadState();});
        resumeButton.setOnClickListener(v->{
            JSONObject saved=UploadStore.read(this);
            if(saved!=null&&"signin".equals(saved.optString("state"))){
                message("Sign in to Studio first, then tap Resume.");
                return;
            }
            try{UploadController.start(this);refreshUploadState();}
            catch(Exception error){message(error.getMessage());}
        });
        web.loadUrl(UploadController.URL+"/admin");
    }
    private void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void requestNativeUpload(){
        JSONObject pending=UploadStore.read(this);
        if(pending!=null && !"done".equals(pending.optString("state"))){
            // Never enqueue another transfer for the same episode from repeated taps.
            // Pause, Resume and Cancel are explicit in the live activity panel.
            openActivityCenter();
            return;
        }
        String current=web==null?null:web.getUrl();
        if(current==null||!current.startsWith(UploadController.URL+"/admin")){
            message("Open AddisReel Studio and sign in first");return;
        }
        web.evaluateJavascript("(function(){return JSON.stringify(window.addisreelNativeUploadContext ? window.addisreelNativeUploadContext() : {});})()",
            result->{
                try{
                    String json=(String)new JSONTokener(result).nextValue();
                    JSONObject context=new JSONObject(json);
                    chosenSeriesId=context.optString("seriesId","");
                    chosenSeriesName=context.optString("seriesTitle","");
                    chosenEpisodeNumber=context.optInt("nextEpisodeNumber",0);
                    if(!chosenSeriesId.matches("[a-z0-9][a-z0-9-]{0,63}")||
                       chosenEpisodeNumber<1||chosenEpisodeNumber>10000){
                        message("In Create & edit, select a series and tap Save & continue. Then tap Upload MP4.");return;
                    }
                    Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    // Accept Android providers that label MP4 files as generic videos.
                    // The selected document is still checked as MP4 before upload.
                    pick.setType("video/*");
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(pick,PICK_NATIVE);
                }catch(Exception error){message("Studio is loading. Sign in and save your series first.");}
            });
    }
    @Override @SuppressWarnings("deprecation") protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==PICK_WEB){
            if(webFileCallback!=null){
                webFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data));
                webFileCallback=null;
            }
            return;
        }
        if(request!=PICK_NATIVE||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException error){message("Choose a video from Android Documents to allow resumable background access");return;}
        String name="episode.mp4";long size=-1;
        try(Cursor cursor=getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
            if(cursor!=null && cursor.moveToFirst()){
                name=cursor.getString(0);
                if(!cursor.isNull(1))size=cursor.getLong(1);
            }
        }catch(Exception ignored){ /* Some Android document providers omit metadata. */ }
        // Resolve size without reading the entire video on the UI thread.
        if(size<1){
            try(android.content.res.AssetFileDescriptor fd=
                    getContentResolver().openAssetFileDescriptor(uri,"r")){
                if(fd!=null && fd.getLength()>0)size=fd.getLength();
            }catch(Exception ignored){}
        }
        if(name==null||name.trim().isEmpty())name="episode.mp4";
        if(!name.toLowerCase(Locale.ROOT).endsWith(".mp4")){
            String mime=getContentResolver().getType(uri);
            if("video/mp4".equalsIgnoreCase(mime))name=name+".mp4";
            else{message("This video is not MP4. Convert it to MP4 first.");return;}
        }
        if(size<1){message("Android could not read this video's size. Save the MP4 to local Files and select it again.");return;}
        if(size>1024L*1024*1024){message("This MP4 exceeds the 1 GB upload limit.");return;}
        final String finalName=name;
        final long finalSize=size;
        EditText editor=new EditText(this);
        editor.setSingleLine(true);
        String suggested=name.replaceFirst("(?i)\\.mp4$","").replace('_',' ').trim();
        if(suggested.length()>150)suggested=suggested.substring(0,150);
        editor.setText(suggested.isEmpty()?"Episode "+chosenEpisodeNumber:suggested);
        LinearLayout wrapper=new LinearLayout(this);
        wrapper.setPadding(dp(22),dp(8),dp(22),dp(2));wrapper.addView(editor);
        new AlertDialog.Builder(this).setTitle("Upload episode "+chosenEpisodeNumber)
                .setMessage(chosenSeriesName+" · "+Math.max(1,size/(1024*1024))+" MB\nUpload continues when minimized.")
                .setView(wrapper)
                .setPositiveButton("Start upload",(dialog,which)->{
                    String title=editor.getText().toString().trim();
                    if(title.isEmpty()||title.length()>150){message("Episode title must be 1–150 characters");return;}
                    try{
                        JSONObject record=new JSONObject();
                        record.put("seriesId",chosenSeriesId);
                        record.put("episodeNumber",chosenEpisodeNumber);
                        record.put("title",title);
                        record.put("fileName",finalName);
                        record.put("fileUri",uri.toString());
                        record.put("size",finalSize);
                        record.put("parts",new JSONArray());
                        record.put("state","queued");
                        record.put("progress",0);
                        UploadStore.save(this,record);
                        refreshedFinished=false;
                        UploadController.start(this);
                        refreshUploadState();
                    }catch(Exception error){message("Cannot start upload: "+error.getMessage());}
                }).setNegativeButton("Cancel",null).show();
    }
    void showActivityError(String message){
        Toast.makeText(this,message==null?"Operation failed":message,Toast.LENGTH_LONG).show();
    }
    void openStudioReview(){
        if(web!=null){
            web.evaluateJavascript("document.querySelector('[data-tab=create]')?.click();",null);
        }
    }
    void syncProcessing(boolean manual){
        if(!manual && System.currentTimeMillis()-lastProcessingSync<30000L)return;
        if(!syncing.compareAndSet(false,true))return;
        lastProcessingSync=System.currentTimeMillis();
        processingExecutor.execute(()->{
            String problem=null;
            try{OptimizationClient.sync(getApplicationContext());}
            catch(Exception e){problem=e.getMessage();}
            final String reason=problem;
            runOnUiThread(()->{
                syncing.set(false);
                if(manual && reason!=null)showActivityError(reason);
                refreshUploadState();
            });
        });
    }
    private void openActivityCenter(){
        if(activityCenter!=null&&activityCenter.isOpen())return;
        activityCenter=ActivityCenter.show(this);
    }
    private static String mb(long bytes){
        return String.format(Locale.US,"%.1f",Math.max(0,bytes)/1048576d);
    }
    private void refreshUploadState(){
        JSONObject info=UploadStore.read(this);
        int notifications=ActivityJournal.unread(this);
        activityBell.setText(notifications>0?"🔔 "+Math.min(notifications,99):"🔔");
        if(info==null){
            transferTitle.setText("STUDIO READY");
            transferText.setText("Choose a saved series, then upload an MP4");
            transferProgress.setVisibility(View.GONE);
            pauseButton.setVisibility(View.GONE);
            resumeButton.setVisibility(View.GONE);
            uploadButton.setText("↑ Upload MP4");
            if(activityCenter!=null)activityCenter.update();
            return;
        }
        String state=info.optString("state");
        String stage=info.optString("stage");
        boolean done="done".equals(state);
        boolean active="queued".equals(state)||"running".equals(state);
        long total=info.optLong("size",0);
        long confirmed=Math.max(0,Math.min(total,info.optLong("confirmedBytes",0)));
        long sending=Math.max(confirmed,Math.min(total,info.optLong("sendingBytes",confirmed)));
        int percent=total>0?(int)Math.min(100,100L*confirmed/total):0;
        int sendingPercent=total>0?(int)Math.min(100,100L*sending/total):0;
        int episode=info.optInt("episodeNumber");
        JSONObject remote=OptimizationClient.matching(this,info.optString("seriesId"),episode);
        String jobState=remote==null?"":remote.optString("status");
        String message=info.optString("message","Preparing your video");
        if(done){
            transferTitle.setText("✓ EPISODE "+episode+" UPLOADED");
            transferText.setText(remote==null?"R2 verified · Checking optimization":
                OptimizationClient.stateLabel(jobState));
        }else if(active){
            transferTitle.setText("● EPISODE "+episode+" · "+
                (sending>confirmed?sendingPercent+"% SENDING":percent+"% VERIFIED"));
            transferText.setText(mb(sending)+" MB sending · "+mb(confirmed)+" / "+
                mb(total)+" MB R2-verified\n"+message);
        }else if("paused".equals(state)){
            transferTitle.setText("Ⅱ EPISODE "+episode+" · PAUSED");
            transferText.setText(mb(confirmed)+" / "+mb(total)+" MB saved · Tap Resume");
        }else if("error".equals(state)||"interrupted".equals(state)||"signin".equals(state)){
            transferTitle.setText("! EPISODE "+episode+" · NEEDS ATTENTION");
            transferText.setText(message+" · "+percent+"% verified");
        }else {
            transferTitle.setText("EPISODE "+episode+" · "+state.toUpperCase(Locale.ROOT));
            transferText.setText(message);
        }
        uploadButton.setText(done?"↑ Upload MP4":"View upload");
        boolean remoteBusy=done&&("queued".equals(jobState)||
            "processing".equals(jobState)||jobState.isEmpty());
        transferProgress.setVisibility(View.VISIBLE);
        // A pulsing bar means the first part is in flight; verified bytes
        // are still 0 and no unconfirmed progress is presented as saved.
        boolean initialTransfer=active&&"uploading".equals(stage)&&sending==0;
        transferProgress.setIndeterminate(remoteBusy||initialTransfer);
        if(!remoteBusy&&!initialTransfer)
            transferProgress.setProgress(done?100:active?sendingPercent:percent);
        pauseButton.setVisibility(active?View.VISIBLE:View.GONE);
        boolean canResume="paused".equals(state)||"error".equals(state)||
            "signin".equals(state)||"interrupted".equals(state);
        resumeButton.setVisibility(canResume?View.VISIBLE:View.GONE);
        resumeButton.setText("signin".equals(state)?"Sign in":"Resume");
        if(activityCenter!=null)activityCenter.update();
        if(done&&!refreshedFinished&&web!=null&&
            (UploadController.URL+"/admin").equals(web.getUrl())){
            refreshedFinished=true;
            syncProcessing(true);
            web.evaluateJavascript("window.addisreelNativeUploadRefresh && window.addisreelNativeUploadRefresh();",null);
        }
    }
    @Override protected void onResume(){
        super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);syncProcessing(false);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        setIntent(intent);
        if(intent!=null&&"SHOW_ACTIVITY".equals(intent.getAction()))
            handler.post(this::openActivityCenter);
    }
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){
        handler.removeCallbacks(refresh);
        processingExecutor.shutdownNow();
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        super.onDestroy();
    }
}
