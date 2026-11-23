package et.addisreel.studio;

import android.content.Context;
import android.webkit.CookieManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reads authoritative Cloudflare D1 optimization jobs using the owner's WebView session.
 * Poll only while Studio is open or the owner explicitly requests refresh.
 */
final class OptimizationClient {
    private static final String PREF="addisreel_optimizer_activity_v1";
    private static final String SNAPSHOT="jobs";
    private static final String SEEN="previous_state";
    private static final String SYNCED="synced_at";
    private OptimizationClient(){}
    static final class Result {
        final JSONArray jobs;
        final long time;
        Result(JSONArray j,long t){jobs=j;time=t;}
    }
    static synchronized Result last(Context ctx){
        String data=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(SNAPSHOT,"[]");
        long time=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getLong(SYNCED,0L);
        try{return new Result(new JSONArray(data),time);}catch(Exception e){return new Result(new JSONArray(),time);}
    }
    static JSONObject matching(Context ctx,String series,int episode){
        JSONArray jobs=last(ctx).jobs;
        for(int i=0;i<jobs.length();i++){
            JSONObject row=jobs.optJSONObject(i);
            if(row!=null&&series.equals(row.optString("series_id"))&&
                episode==row.optInt("episode_number"))return row;
        }
        return null;
    }
    static void sync(Context ctx) throws IOException{
        JSONObject data=request("/v1/admin/optimization/jobs","GET",null);
        JSONArray rows=data.optJSONArray("jobs");
        if(rows==null)throw new IOException("Invalid processing queue response");
        JSONArray safe=new JSONArray();
        JSONObject previous;
        String raw=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(SEEN,"{}");
        try{previous=new JSONObject(raw);}catch(Exception e){previous=new JSONObject();}
        JSONObject next=new JSONObject();
        boolean hadPrevious=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).contains(SEEN);
        for(int i=0;i<rows.length()&&i<100;i++){
            JSONObject r=rows.optJSONObject(i);if(r==null)continue;
            String series=r.optString("series_id"),state=r.optString("status");
            int episode=r.optInt("episode_number",-1);
            if(!series.matches("[a-z0-9][a-z0-9-]{0,63}")||episode<1||episode>10000||
                !("queued".equals(state)||"processing".equals(state)||"ready".equals(state)||
                  "original".equals(state)||"failed".equals(state)))continue;
            JSONObject compact=new JSONObject();
            try{
                compact.put("series_id",series);compact.put("episode_number",episode);
                compact.put("status",state);
                compact.put("original_bytes",Math.max(0,r.optLong("original_bytes")));
                compact.put("optimized_bytes",Math.max(0,r.optLong("optimized_bytes")));
                compact.put("updated_at",r.optString("updated_at",""));
            }catch(Exception ignored){}
            safe.put(compact);
            String id=series+"#"+episode;
            try{next.put(id,state);}catch(Exception ignored){}
            String old=previous.optString(id,"");
            if(!old.equals(state)){
                String message=stateLabel(state);
                boolean notify=hadPrevious&&!old.isEmpty()&&
                    ("ready".equals(state)||"original".equals(state)||"failed".equals(state));
                ActivityJournal.record(ctx,"video:"+id,"Episode "+episode+" · "+series,
                    "optimization:"+state,message,notify);
                if(notify){
                    UploadNotice.event(ctx,"Episode "+episode, message,id.hashCode());
                }
            }
        }
        // Persist only validated status data; not the processor's tokens or raw responses.
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putString(SNAPSHOT,safe.toString()).putString(SEEN,next.toString())
            .putLong(SYNCED,System.currentTimeMillis()).apply();
    }
    static String stateLabel(String state){
        switch(state){
            case "queued":return "Waiting for your Windows video optimizer";
            case "processing":return "Optimizing video on your Windows PC";
            case "ready":return "Optimized video verified and ready to review";
            case "original":return "Original retained: compression was not beneficial";
            case "failed":return "Video optimization failed; original file is safe";
            default:return "Awaiting processing status";
        }
    }
    static void retry(String series,int episode) throws IOException{
        if(series==null||!series.matches("[a-z0-9][a-z0-9-]{0,63}")||episode<1||episode>10000)
            throw new IOException("Invalid episode selection");
        JSONObject payload=new JSONObject();
        try{payload.put("seriesId",series);payload.put("episodeNumber",episode);}
        catch(Exception error){throw new IOException(error);}
        JSONObject result=request("/v1/admin/optimization/retry","POST",payload);
        if(!result.optBoolean("queued"))throw new IOException("Retry could not be queued");
    }
    private static JSONObject request(String path,String method,JSONObject payload) throws IOException{
        HttpURLConnection conn=(HttpURLConnection)new URL(UploadController.URL+path).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(18000);
        conn.setRequestProperty("Accept","application/json");
        conn.setRequestProperty("Cache-Control","no-store");
        String cookie=CookieManager.getInstance().getCookie(UploadController.URL);
        if(cookie!=null&&!cookie.isEmpty())conn.setRequestProperty("Cookie",cookie);
        if(!"GET".equals(method)){
            conn.setRequestProperty("Origin",UploadController.URL);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/json");
            byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=conn.getOutputStream()){out.write(bytes);}
        }
        try{
            int code=conn.getResponseCode();
            if(code<200||code>=300)throw new IOException(code==401?
                "Sign in to Studio to see processing updates":"Processor API HTTP "+code);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream in=conn.getInputStream()){
                byte[] buffer=new byte[2048];int count;
                while((count=in.read(buffer))!=-1){
                    if(output.size()+count>200000)throw new IOException("Processing response too large");
                    output.write(buffer,0,count);
                }
            }
            try{return new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8));}
            catch(Exception e){throw new IOException("Invalid optimization data",e);}
        }finally{conn.disconnect();}
    }
}
