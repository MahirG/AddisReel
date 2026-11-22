package et.addisreel.studio;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Native streaming R2 multipart uploader. Only the owner-picked persisted
 * content:// URI is read. Never serializes files or login cookies to preferences.
 * Every confirmed 8 MiB part has its R2 ETag durably checkpointed.
 */
final class UploadEngine {
    interface Progress {
        boolean interrupted();
        void report(String text,int percent);
    }
    private static final int EIGHT_MB = 8*1024*1024;
    private static final long MAX_BYTES=1024L*1024*1024;
    private final Context app;
    private final Progress listener;

    UploadEngine(Context context,Progress progress){this.app=context.getApplicationContext();this.listener=progress;}
    private static String url(String value) {
        return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");
    }
    private void ensureActive() throws IOException {
        JSONObject state=UploadStore.read(app);
        if(Thread.currentThread().isInterrupted()||listener.interrupted()||state==null||
           "paused".equals(state.optString("state")))
            throw new InterruptedIOException("Upload paused or stopped");
    }
    private void status(String text,int percent){
        UploadStore.updateProgress(app,text,percent);
        listener.report(text,percent);
    }
    private JSONObject request(String path,String method,JSONObject payload,byte[] raw)
            throws IOException {
        ensureActive();
        HttpURLConnection http=(HttpURLConnection)new URL(UploadController.URL+path).openConnection();
        http.setConnectTimeout(30000);
        http.setReadTimeout(600000);
        http.setInstanceFollowRedirects(false);
        http.setRequestMethod(method);
        http.setRequestProperty("Accept","application/json");
        http.setRequestProperty("Cache-Control","no-store");
        String cookie=CookieManager.getInstance().getCookie(UploadController.URL);
        if(cookie!=null&&!cookie.isEmpty())http.setRequestProperty("Cookie",cookie);
        if(!"GET".equals(method))http.setRequestProperty("Origin",UploadController.URL);
        try {
            byte[] bytes=payload!=null?payload.toString().getBytes(StandardCharsets.UTF_8):raw;
            if(bytes!=null){
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type",payload!=null?"application/json":"application/octet-stream");
                http.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=http.getOutputStream()){out.write(bytes);}
            }
            int status=http.getResponseCode();
            if(status>=300&&status<400)throw new HttpError(status,"Unexpected HTTP redirect");
            InputStream body=(status>=400)?http.getErrorStream():http.getInputStream();
            byte[] buffer=new byte[8192];
            ByteArrayOutputStream response=new ByteArrayOutputStream();
            if(body!=null)try(InputStream in=body){
                int n;
                while((n=in.read(buffer))!=-1){
                    if(response.size()>32*1024)throw new IOException("Unexpected oversized API response");
                    response.write(buffer,0,n);
                }
            }
            JSONObject result;
            try{result=new JSONObject(new String(response.toByteArray(),StandardCharsets.UTF_8));}
            catch(Exception error){throw new IOException("Invalid server reply",error);}
            if(status<200||status>=300)
                throw new HttpError(status,result.optString("error","Server refused upload"));
            return result;
        } finally {http.disconnect();}
    }
    private static JSONObject data(Object... pairs){
        JSONObject json=new JSONObject();
        try{for(int i=0;i<pairs.length;i+=2)json.put((String)pairs[i],pairs[i+1]);}
        catch(Exception error){throw new IllegalStateException(error);}
        return json;
    }
    private static JSONArray parts(JSONObject current){
        JSONArray value=current.optJSONArray("parts");
        return value==null?new JSONArray():value;
    }
    private static boolean hasPart(JSONArray list,int number){
        for(int i=0;i<list.length();i++)if(list.optJSONObject(i)!=null &&
                list.optJSONObject(i).optInt("partNumber")==number)return true;
        return false;
    }
    private static void skipFully(InputStream input,int count) throws IOException{
        long remaining=count;
        while(remaining>0){
            long skipped=input.skip(remaining);
            if(skipped<=0){
                if(input.read()==-1)throw new IOException("Video ended before saved chunk");
                skipped=1;
            }
            remaining-=skipped;
        }
    }
    private static byte[] readFully(InputStream input,int size) throws IOException {
        byte[] bytes=new byte[size];
        int pos=0;
        while(pos<size){
            int n=input.read(bytes,pos,size-pos);
            if(n<0)throw new IOException("Selected video is incomplete or changed");
            if(n==0)continue;
            pos+=n;
        }
        return bytes;
    }
    /** Returns true if the OS should retry later, false for success/permanent failure. */
    boolean run(){
        JSONObject state=UploadStore.read(app);
        if(state==null)return false;
        if("paused".equals(state.optString("state"))||"done".equals(state.optString("state")))return false;
        try{
            UploadStore.changeState(app,"running","Starting secure upload");
            String series=state.getString("seriesId");
            int episode=state.getInt("episodeNumber");
            String title=state.getString("title");
            String filename=state.getString("fileName");
            long size=state.getLong("size");
            if(!series.matches("[a-z0-9][a-z0-9-]{0,63}")||
                episode<1||episode>10000||title.length()>150||title.isEmpty()||
                size<1||size>MAX_BYTES||!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))
                throw new IOException("Invalid saved episode details");
            JSONObject auth=request("/v1/auth/session","GET",null,null);
            if(!auth.optBoolean("authenticated"))throw new HttpError(401,"Sign in to Studio first");

            if(!state.optBoolean("episodeCreated")){
                status("Saving episode details…",0);
                request("/v1/admin/episodes","POST",data("seriesId",series,"number",episode,
                        "title",title,"durationSeconds",0,"status","draft","licensed",false),null);
                state.put("episodeCreated",true);
                UploadStore.checkpoint(app,state);
            }
            String base="/v1/admin/uploads/"+url(series)+"/"+episode;
            String session=state.optString("uploadId","");
            if(session.isEmpty()){
                JSONObject begun=request(base+"?action=start","POST",
                        data("size",size,"name",filename),null);
                session=begun.getString("uploadId");
                state.put("uploadId",session);
                state.put("partSize",begun.getInt("partSize"));
                state.put("parts",new JSONArray());
                UploadStore.checkpoint(app,state);
            }
            int partBytes=state.optInt("partSize",EIGHT_MB);
            if(partBytes!=EIGHT_MB)throw new IOException("Server upload chunk size mismatch");
            JSONArray existing=parts(state);
            int total=(int)((size+partBytes-1)/partBytes);
            Uri uri=Uri.parse(state.getString("fileUri"));
            try(InputStream input=app.getContentResolver().openInputStream(uri)){
                if(input==null)throw new IOException("Cannot open the selected video file");
                for(int n=1;n<=total;n++){
                    ensureActive();
                    long offset=(long)(n-1)*partBytes;
                    int length=(int)Math.min(partBytes,size-offset);
                    if(hasPart(existing,n)){
                        skipFully(input,length);
                        status("Resuming saved video chunks…",(int)((offset+length)*100/size));
                        continue;
                    }
                    byte[] chunk=readFully(input,length);
                    String partUrl=base+"?action=part&partNumber="+n+"&uploadId="+url(session);
                    JSONObject done=null;
                    for(int attempt=1;attempt<=4;attempt++){
                        ensureActive();
                        try{done=request(partUrl,"PUT",null,chunk);break;}
                        catch(HttpError e){
                            if(e.status==409)throw e;
                            if(e.status<500 && e.status!=408 && e.status!=429)throw e;
                            if(attempt==4)throw e;
                        }catch(IOException e){
                            if(attempt==4)throw e;
                        }
                        try{Thread.sleep(attempt*1500L);}catch(InterruptedException e){
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("Upload interrupted");
                        }
                    }
                    if(done==null||done.optInt("partNumber")!=n||done.optString("etag").isEmpty())
                        throw new IOException("Invalid confirmed upload chunk");
                    existing.put(data("partNumber",n,"etag",done.getString("etag")));
                    state.put("parts",existing);
                    UploadStore.checkpoint(app,state);
                    status("Episode "+episode+" · Part "+n+"/"+total,(int)((offset+length)*100/size));
                }
            }
            ensureActive();
            status("Finalizing video upload…",99);
            JSONObject finalParts=new JSONObject();
            finalParts.put("size",size);finalParts.put("parts",existing);
            try{
                JSONObject finished=request(base+"?action=finish&uploadId="+url(session),
                    "POST",finalParts,null);
                if(!finished.optBoolean("uploaded")||finished.optLong("sizeBytes")!=size)
                    throw new IOException("Final video size verification failed");
            }catch(IOException error){
                // Cloudflare may have finished but the acknowledgment was lost.
                JSONObject media=request("/v1/admin/media-status/"+url(series)+"/"+episode,
                    "GET",null,null);
                if(!media.optBoolean("ready")||media.optLong("sizeBytes")!=size)throw error;
            }
            UploadStore.changeState(app,"done","Episode uploaded — ready for FFmpeg optimization");
            status("Video uploaded successfully",100);
            return false;
        }catch(InterruptedIOException error){
            // JobScheduler can resume from completed R2 part receipts.
            return true;
        }catch(HttpError error){
            if(error.status==409 && error.getMessage()!=null &&
               error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("expired")){
                JSONObject reset=UploadStore.read(app);
                if(reset!=null)try{
                    reset.remove("uploadId");reset.put("parts",new JSONArray());UploadStore.save(app,reset);
                }catch(Exception ignored){}
                return true;
            }
            UploadStore.changeState(app,error.status==401?"signin":"error",error.getMessage());
            UploadNotice.update(app,"Upload stopped: "+error.getMessage(),0,false);
            return error.status>=500||error.status==408||error.status==429;
        }catch(Exception error){
            UploadStore.changeState(app,"interrupted",error.getMessage()==null?"Connection interrupted":error.getMessage());
            UploadNotice.update(app,"Upload interrupted — tap Resume in Studio",0,false);
            return true;
        }
    }
    static final class HttpError extends IOException {
        final int status;
        HttpError(int status,String message){super(message);this.status=status;}
    }
    static final class InterruptedIOException extends IOException{
        InterruptedIOException(String message){super(message);}
    }
}
