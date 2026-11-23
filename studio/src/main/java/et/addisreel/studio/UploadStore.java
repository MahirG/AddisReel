package et.addisreel.studio;

import android.content.Context;
import org.json.JSONObject;

/** Durable metadata only. Never store passwords, session cookies or original video bytes. */
final class UploadStore {
    private static final String PREF = "addisreel_native_uploads_v1";
    private static final String KEY = "active";
    private UploadStore() {}
    static synchronized JSONObject read(Context context) {
        String raw=context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null);
        if(raw==null)return null;
        try{return new JSONObject(raw);}catch(Exception error){return null;}
    }
    static synchronized void save(Context context,JSONObject value){
        if(!context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY,value.toString()).commit())
            throw new IllegalStateException("Could not persist video upload progress");
    }
    static synchronized void changeState(Context context,String state,String message){
        JSONObject value=read(context);
        if(value==null)return;
        String old=value.optString("state");
        try{value.put("state",state);value.put("message",message);}catch(Exception ignored){}
        save(context,value);
        if(!state.equals(old)){
            String id=keyFor(value);
            ActivityJournal.record(context,id,value.optString("title","Episode "+value.optInt("episodeNumber")),
                "upload:"+state,message,
                "done".equals(state)||"error".equals(state)||"signin".equals(state));
        }
    }
    static synchronized void stage(Context context,String stage,String message){
        JSONObject current=read(context);
        if(current==null||"paused".equals(current.optString("state")))return;
        String old=current.optString("stage");
        try{current.put("stage",stage);current.put("message",message);}
        catch(Exception ignored){}
        save(context,current);
        if(!stage.equals(old)){
            ActivityJournal.record(context,keyFor(current),current.optString("title","Episode"),
                "stage:"+stage,message,false);
        }
    }
    private static String keyFor(JSONObject value){
        return "upload:"+value.optString("seriesId")+"#"+value.optInt("episodeNumber");
    }
    static synchronized void checkpoint(Context context,JSONObject session) throws java.io.IOException {
        JSONObject current=read(context);
        if(current==null || "paused".equals(current.optString("state")))
            throw new java.io.IOException("Upload paused or cancelled");
        try {
            for(String field:new String[]{"episodeCreated","uploadId","partSize","parts"}){
                if(session.has(field))current.put(field,session.get(field));
            }
        }catch(Exception error){throw new java.io.IOException("Unable to save upload checkpoint",error);}
        save(context,current);
    }
    static synchronized void updateProgress(Context context,String text,int percent) {
        JSONObject current=read(context);
        if(current==null||"paused".equals(current.optString("state")))return;
        try{current.put("message",text);current.put("progress",percent);}
        catch(Exception ignored){}
        save(context,current);
    }
    static synchronized void updateConfirmedBytes(Context context,long bytes) {
        JSONObject current=read(context);
        if(current==null||"paused".equals(current.optString("state")))return;
        try{current.put("confirmedBytes",Math.max(0,bytes));}
        catch(Exception ignored){}
        save(context,current);
    }
    static synchronized void clear(Context context){
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(KEY).commit();
    }
}
