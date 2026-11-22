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
        try{value.put("state",state);value.put("message",message);}catch(Exception ignored){}
        save(context,value);
    }
    static synchronized void clear(Context context){
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(KEY).commit();
    }
}
