package et.addisreel.studio;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Native, state-driven Live Activity Center. Data derives from confirmed R2 parts and D1 jobs. */
final class ActivityCenter {
    private static final int GOLD=Color.rgb(238,192,121);
    private static final int WHITE=Color.rgb(242,236,229);
    private static final int MUTED=Color.rgb(184,174,166);
    private static final int BG=Color.rgb(23,21,27);
    private final StudioActivity host;
    private AlertDialog dialog;
    private TextView currentTitle,currentStatus,currentBytes,lastSync,historyTitle;
    private ProgressBar uploadProgress,busyIndicator;
    private final TextView[] steps=new TextView[7];
    private LinearLayout history;
    private Button pause,resume,cancel,retry,review;
    private String lastHistoryFingerprint="";
    private static final String[] STAGES={
        "Preparing video","Uploading securely","Verifying storage",
        "Waiting for optimizer","Optimizing on Windows PC","Checking output quality",
        "Ready to review & publish"
    };
    private ActivityCenter(StudioActivity host){this.host=host;}
    static ActivityCenter show(StudioActivity host){
        ActivityCenter view=new ActivityCenter(host);view.open();return view;
    }
    private int dp(int n){return (int)(n*host.getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable surface(int color,int radius){
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;
    }
    private TextView text(String label,int size,int color){
        TextView v=new TextView(host);v.setText(label);v.setTextColor(color);v.setTextSize(size);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setLineSpacing(dp(2),1f);return v;
    }
    private Button button(String label, boolean gold,Runnable onClick){
        Button b=new Button(host);b.setText(label);b.setAllCaps(false);b.setTextSize(12);
        b.setTextColor(gold?Color.rgb(36,25,20):WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            gold?GOLD:Color.rgb(62,53,58)));
        b.setOnClickListener(v->onClick.run());return b;
    }
    private LinearLayout vertical(int gap){
        LinearLayout c=new LinearLayout(host);c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(gap),dp(gap),dp(gap),dp(gap));return c;
    }
    private void rowButtons(LinearLayout holder,View... views){
        LinearLayout r=new LinearLayout(host);r.setOrientation(LinearLayout.HORIZONTAL);
        for(View v:views)r.addView(v,new LinearLayout.LayoutParams(0,dp(43),1));
        holder.addView(r);
    }
    private TextView heading(String s){
        TextView t=text(s,13,GOLD);t.setTypeface(null,1);t.setPadding(0,dp(9),0,dp(5));return t;
    }
    private void open(){
        ActivityJournal.markRead(host);
        // Keep the live transfer meter pinned when the history is scrolled.
        LinearLayout dialogRoot=new LinearLayout(host);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackground(surface(BG,19));
        ScrollView scroll=new ScrollView(host);
        scroll.setFillViewport(false);
        LinearLayout content=vertical(17);
        content.addView(text("ACTIVITY HISTORY • 7-STAGE PROCESSING",11,GOLD));
        LinearLayout active=vertical(12);
        active.addView(text("ADDISREEL  •  LIVE UPLOAD",11,GOLD));
        active.setBackground(surface(Color.rgb(35,31,36),14));
        currentTitle=text("Checking active operation…",17,WHITE);currentTitle.setTypeface(null,1);
        currentStatus=text("",12,MUTED);
        currentBytes=text("",11,GOLD);
        active.addView(currentTitle);
        active.addView(currentStatus);
        active.addView(currentBytes);
        uploadProgress=new ProgressBar(host,null,android.R.attr.progressBarStyleHorizontal);
        uploadProgress.setMax(100);
        uploadProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(GOLD));
        active.addView(uploadProgress,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(9)));
        busyIndicator=new ProgressBar(host);
        active.addView(busyIndicator,new LinearLayout.LayoutParams(dp(27),dp(27)));
        pause=button("Pause",false,()->{UploadController.pause(host);update();});
        resume=button("Resume",true,()->{
            JSONObject activeUpload=UploadStore.read(host);
            if(activeUpload!=null&&"signin".equals(activeUpload.optString("state"))){
                host.showActivityError("Sign in to AddisReel Studio, then tap Resume.");
                return;
            }
            try{UploadController.start(host);}catch(Exception e){host.showActivityError(e.getMessage());}
            update();
        });
        cancel=button("Cancel",false,()->new AlertDialog.Builder(host)
            .setTitle("Cancel this upload?")
            .setMessage("Completed chunks will no longer be used. The original video on your phone will not be deleted.")
            .setPositiveButton("Cancel upload",(d,w)->{UploadController.cancel(host);update();})
            .setNegativeButton("Keep upload",null).show());
        retry=button("Retry optimization",true,()->{
            JSONObject current=UploadStore.read(host);
            if(current==null)return;
            String series=current.optString("seriesId"),episodeStr=current.optString("episodeNumber");
            int episode=current.optInt("episodeNumber");
            retry.setEnabled(false);
            new Thread(()->{
                try{OptimizationClient.retry(series,episode);host.runOnUiThread(()->host.syncProcessing(true));}
                catch(Exception e){host.runOnUiThread(()->host.showActivityError(e.getMessage()));}
                finally{host.runOnUiThread(()->{retry.setEnabled(true);update();});}
            },"AddisReel-RetryOptimization").start();
        });
        review=button("Open Studio",false,()->{dialog.dismiss();host.openStudioReview();});
        rowButtons(active,pause,resume);
        rowButtons(active,cancel,retry);
        active.addView(review,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(43)));
        dialogRoot.addView(active,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(heading("7-STAGE PROCESSING TIMELINE"));
        LinearLayout timeline=vertical(10);
        timeline.setBackground(surface(Color.rgb(32,29,34),13));
        for(int i=0;i<steps.length;i++){
            steps[i]=text("○  "+STAGES[i],13,MUTED);
            steps[i].setPadding(dp(3),dp(5),0,dp(5));timeline.addView(steps[i]);
        }
        content.addView(timeline);
        lastSync=text("",11,MUTED);
        content.addView(lastSync);
        Button refresh=button("↻ Refresh processing status",false,()->host.syncProcessing(true));
        content.addView(refresh,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(45)));
        content.addView(heading("NOTIFICATION CENTER"));
        historyTitle=text("No activities yet",12,MUTED);content.addView(historyTitle);
        history=vertical(5);
        content.addView(history);
        content.addView(text("Upload jobs continue through Android's supported background mechanisms. A user stop, device restriction or expired login may still interrupt them.",11,MUTED));
        Button close=button("Close",false,()->dialog.dismiss());
        content.addView(close,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(43)));
        scroll.addView(content);
        dialogRoot.addView(scroll,new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        dialog=new AlertDialog.Builder(host).setView(dialogRoot).create();
        dialog.setOnDismissListener(d->dialog=null);
        dialog.show();
        if(dialog.getWindow()!=null){
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int height=(int)(host.getResources().getDisplayMetrics().heightPixels*0.88f);
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,height);
        }
        update();
        host.syncProcessing(false);
    }
    private static String size(long value){
        return String.format(Locale.US,"%.1f MB",Math.max(0,value)/1048576d);
    }
    boolean isOpen(){return dialog!=null&&dialog.isShowing();}
    void update(){
        if(!isOpen())return;
        JSONObject local=UploadStore.read(host);
        JSONObject remote=local==null?null:OptimizationClient.matching(host,
                local.optString("seriesId"),local.optInt("episodeNumber"));
        String uploadState=local==null?"":local.optString("state");
        String stage=local==null?"":local.optString("stage");
        String remoteState=remote==null?"":remote.optString("status");
        boolean hasLocal=local!=null;
        boolean uploadComplete="done".equals(uploadState);
        boolean uploading=hasLocal&&!uploadComplete&&
            !("error".equals(uploadState)||"paused".equals(uploadState)||
              "interrupted".equals(uploadState)||"signin".equals(uploadState));
        int activeIndex=0;
        if("uploading".equals(stage))activeIndex=1;
        if("verifying".equals(stage))activeIndex=2;
        if(uploadComplete)activeIndex=3;
        if("processing".equals(remoteState))activeIndex=4;
        if("ready".equals(remoteState)||"original".equals(remoteState))activeIndex=6;
        if("failed".equals(remoteState))activeIndex=4;
        currentTitle.setText(hasLocal?
            "Episode "+local.optInt("episodeNumber")+" · "+local.optString("title","Video"):
            "No active video upload");
        String message=hasLocal?local.optString("message","Preparing video"):
            "Choose an MP4 in Studio to start your first transfer.";
        if(uploadComplete){
            message=remote==null?"Uploaded securely · Checking optimization queue":
                OptimizationClient.stateLabel(remoteState);
        } else if("paused".equals(uploadState))message="Paused by you · Saved chunks retained";
        else if("signin".equals(uploadState))message="Sign-in required · Open Studio to reconnect";
        else if("error".equals(uploadState))message="Upload stopped: "+message+" · Tap Resume to retry";
        currentStatus.setText(message);
        int percent=hasLocal?Math.min(100,Math.max(0,local.optInt("progress"))):0;
        long original=hasLocal?local.optLong("size"):0;
        long confirmed=hasLocal?local.optLong("confirmedBytes"):0;
        if(uploadComplete)confirmed=original;
        long sending=hasLocal?Math.max(confirmed,local.optLong("sendingBytes",confirmed)):0;
        sending=Math.min(original,sending);
        if(original>0 && !uploadComplete)percent=(int)Math.min(100,100*confirmed/original);
        int sendingPercent=original>0?(int)Math.min(100,100*sending/original):0;
        boolean moving=uploading&&sending>confirmed;
        uploadProgress.setIndeterminate(uploading&&sending==0&&"uploading".equals(stage));
        uploadProgress.setProgress(moving?sendingPercent:percent);
        uploadProgress.setVisibility(hasLocal?View.VISIBLE:View.GONE);
        currentBytes.setText(hasLocal?(uploadComplete?
            "Uploaded "+size(original)+" • R2 verified":
            (moving?"Sending "+size(sending)+" ("+sendingPercent+"%) · awaiting confirmation\n":"")+
            size(confirmed)+" / "+size(original)+" R2 verified · "+percent+"% saved"):"");
        boolean showSpin=uploadComplete&&(!("ready".equals(remoteState)||"original".equals(remoteState)||"failed".equals(remoteState)));
        busyIndicator.setVisibility(showSpin?View.VISIBLE:View.GONE);
        pause.setVisibility(uploading?View.VISIBLE:View.GONE);
        resume.setVisibility(hasLocal&&("paused".equals(uploadState)||"interrupted".equals(uploadState)||
            "error".equals(uploadState)||"signin".equals(uploadState))?View.VISIBLE:View.GONE);
        cancel.setVisibility(hasLocal&&!uploadComplete?View.VISIBLE:View.GONE);
        retry.setVisibility(uploadComplete&&"failed".equals(remoteState)?View.VISIBLE:View.GONE);
        review.setVisibility(uploadComplete?View.VISIBLE:View.GONE);
        for(int i=0;i<steps.length;i++){
            String marker=i<activeIndex?"✓":i==activeIndex?"◉":"○";
            if(i==6&&"failed".equals(remoteState))marker="!";
            if(i==6&&"original".equals(remoteState))marker="✓";
            steps[i].setText(marker+"  "+STAGES[i]+(i==4&&"failed".equals(remoteState)?" — failed":""));
            steps[i].setTextColor(i<=activeIndex?GOLD:MUTED);
        }
        if("original".equals(remoteState))steps[5].setText("✓  No smaller quality-safe file; original retained");
        if("queued".equals(remoteState))steps[3].setText("◉  Waiting for your Windows FFmpeg processor");
        OptimizationClient.Result snap=OptimizationClient.last(host);
        if(snap.time==0)lastSync.setText("Processing status has not yet been checked.");
        else lastSync.setText("Processing checked: "+
            DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
              .format(new Date(snap.time))+" · No invented progress percentages");
        renderHistoryIfNeeded();
    }
    private void renderHistoryIfNeeded(){
        JSONArray list=ActivityJournal.read(host);
        String signature=list.toString();
        if(signature.equals(lastHistoryFingerprint))return;
        lastHistoryFingerprint=signature;
        history.removeAllViews();
        if(list.length()==0){historyTitle.setText("No activities yet");return;}
        historyTitle.setText(Math.min(30,list.length())+" recent verified updates");
        int count=0;
        for(String section:new String[]{"Attention","Active","Completed"}){
            boolean any=false;
            for(int i=0;i<list.length()&&count<30;i++){
                JSONObject item=list.optJSONObject(i);if(item==null)continue;
                String state=item.optString("state");
                boolean attention=state.endsWith("failed")||state.endsWith("error")||
                    state.endsWith("signin")||state.endsWith("interrupted");
                boolean complete=state.endsWith("done")||state.endsWith("ready")||
                    state.endsWith("original")||state.endsWith("cancelled");
                String category=attention?"Attention":complete?"Completed":"Active";
                if(!section.equals(category))continue;
                if(!any){history.addView(heading(section.toUpperCase(Locale.ROOT)));any=true;}
                LinearLayout card=vertical(10);
                card.setBackground(surface(Color.rgb(39,34,41),11));
                TextView name=text(item.optString("title","Studio operation"),13,WHITE);
                name.setTypeface(null,1);
                card.addView(name);
                card.addView(text(item.optString("detail",""),12,MUTED));
                long at=item.optLong("at",0);
                if(at>0)card.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT,
                    DateFormat.SHORT).format(new Date(at)),10,GOLD));
                history.addView(card);
                count++;
            }
        }
    }
}
