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

/**
 * Premium native shell for the existing authenticated AddisReel Studio.
 * Only first-party HTTPS URLs can load inside the WebView. The owner's
 * deliberate Upload button launches a separate native background transfer.
 */
public final class StudioActivity extends Activity {
    private static final int PICK_NATIVE = 430;
    private static final int PICK_WEB = 431;
    private WebView web;
    private TextView transferText;
    private ProgressBar transferProgress;
    private Button uploadButton, resumeButton;
    private ValueCallback<Uri[]> webFileCallback;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String chosenSeriesId="",chosenSeriesName="";
    private int chosenEpisodeNumber=0;
    private boolean refreshedFinished=false;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            refreshUploadState();
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
        LinearLayout top=new LinearLayout(this);
        top.setPadding(dp(16),dp(12),dp(14),dp(11));top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(Color.rgb(29,26,33));
        LinearLayout textGroup=new LinearLayout(this);textGroup.setOrientation(LinearLayout.VERTICAL);
        TextView heading=label("ADDISREEL",17,Color.rgb(246,204,140));
        heading.setTypeface(null,1);
        TextView subtitle=label("CONTENT STUDIO  •  PRIVATE",10,Color.rgb(183,177,175));
        textGroup.addView(heading);textGroup.addView(subtitle);
        top.addView(textGroup,new LinearLayout.LayoutParams(0,dp(52),1));
        uploadButton=new Button(this);uploadButton.setText("↑ Upload MP4");uploadButton.setAllCaps(false);
        uploadButton.setTextSize(12);uploadButton.setTextColor(Color.rgb(36,25,17));
        uploadButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242,189,111)));
        top.addView(uploadButton,new LinearLayout.LayoutParams(dp(118),dp(45)));
        root.addView(top);

        LinearLayout status=new LinearLayout(this);status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);status.setPadding(dp(15),dp(7),dp(12),dp(7));
        status.setBackgroundColor(Color.rgb(34,29,33));
        transferText=label("Sign in to manage short dramas",11,Color.rgb(231,204,168));
        status.addView(transferText,new LinearLayout.LayoutParams(0,dp(31),1));
        resumeButton=new Button(this);resumeButton.setText("Resume");resumeButton.setTextSize(11);
        resumeButton.setAllCaps(false);resumeButton.setVisibility(View.GONE);
        status.addView(resumeButton,new LinearLayout.LayoutParams(dp(85),dp(40)));
        root.addView(status);
        transferProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        transferProgress.setMax(100);transferProgress.setVisibility(View.GONE);
        transferProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242,189,111)));
        root.addView(transferProgress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(4)));

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
        resumeButton.setOnClickListener(v->{
            try{UploadController.start(this);refreshUploadState();}
            catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
        });
        web.loadUrl(UploadController.URL+"/admin");
    }
    private void message(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void requestNativeUpload(){
        JSONObject pending=UploadStore.read(this);
        if(pending!=null && !"done".equals(pending.optString("state"))){
            new AlertDialog.Builder(this).setTitle("An upload is already saved")
                .setMessage("Resume the saved episode, or discard that progress before selecting a new movie?")
                .setPositiveButton("Resume",(d,w)->{
                    try{UploadController.start(this);}catch(Exception e){message(e.getMessage());}
                })
                .setNegativeButton("Discard & choose new",(d,w)->{
                    UploadController.cancel(this);requestNativeUpload();
                }).setNeutralButton("Keep current",null).show();
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
                        message("Save or select a series in Studio first, then tap Upload MP4");return;
                    }
                    Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.setType("video/mp4");
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
        }catch(Exception error){message("Cannot read selected video");return;}
        if(name==null||!name.toLowerCase(Locale.ROOT).endsWith(".mp4")||
            size<1||size>1024L*1024*1024){message("Choose an MP4 file from 1 byte to 1 GB");return;}
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
    private void refreshUploadState(){
        JSONObject info=UploadStore.read(this);
        if(info==null){
            transferText.setText("Secure native MP4 uploads • Ready");
            transferProgress.setVisibility(View.GONE);resumeButton.setVisibility(View.GONE);return;
        }
        String state=info.optString("state");
        transferText.setText(info.optString("message","Movie upload")+
            ("done".equals(state)?" ✓":""));
        transferProgress.setVisibility(View.VISIBLE);
        transferProgress.setProgress(info.optInt("progress",0));
        boolean canResume="paused".equals(state)||"error".equals(state)||
                "signin".equals(state)||"interrupted".equals(state);
        resumeButton.setVisibility(canResume?View.VISIBLE:View.GONE);
        resumeButton.setText("signin".equals(state)?"Retry":"Resume");
        if("done".equals(state) && !refreshedFinished && web!=null &&
           "https://ar.eddieaman475.workers.dev/admin".equals(web.getUrl())){
            refreshedFinished=true;
            // Don't force a navigation or reset the current editing context.
            web.evaluateJavascript("window.addisreelNativeUploadRefresh && window.addisreelNativeUploadRefresh();",null);
        }
    }
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){
        handler.removeCallbacks(refresh);
        if(web!=null){web.stopLoading();web.destroy();web=null;}
        super.onDestroy();
    }
}
