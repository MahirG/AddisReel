package et.addisreel.studio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compact local activity history; no cookies, file contents or processor tokens. */
final class ActivityJournal {
    private static final String PREF="addisreel_activity_journal";
    private static final String KEY="events_v1";
    private static final int LIMIT=60;

    private ActivityJournal(){}
    static synchronized JSONArray read(Context ctx){
        String raw=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,"[]");
        try{return new JSONArray(raw);}catch(Exception e){return new JSONArray();}
    }
    static synchronized void record(Context ctx,String id,String title,String state,String detail,boolean unread){
        if(id==null||id.isEmpty()||state==null)return;
        String safeTitle=limit(title,90),safeDetail=limit(detail,170);
        JSONArray old=read(ctx),updated=new JSONArray();
        for(int i=0;i<old.length()&&updated.length()<LIMIT-1;i++){
            JSONObject e=old.optJSONObject(i);
            if(e==null)continue;
            // Keep a history of meaningful state transitions, not progress spam.
            if(id.equals(e.optString("id")) && state.equals(e.optString("state")))return;
            updated.put(e);
        }
        JSONObject entry=new JSONObject();
        try{
            entry.put("id",id);entry.put("title",safeTitle);entry.put("state",state);
            entry.put("detail",safeDetail);entry.put("at",System.currentTimeMillis());
            entry.put("unread",unread);
        }catch(Exception e){return;}
        JSONArray next=new JSONArray();
        next.put(entry);
        for(int i=0;i<updated.length()&&next.length()<LIMIT;i++)next.put(updated.optJSONObject(i));
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,next.toString()).apply();
    }
    private static String limit(String text,int max){
        if(text==null)return "";
        return text.length()>max?text.substring(0,max):text;
    }
    static synchronized int unread(Context ctx){
        JSONArray a=read(ctx);int count=0;
        for(int i=0;i<a.length();i++)if(a.optJSONObject(i)!=null&&a.optJSONObject(i).optBoolean("unread"))count++;
        return count;
    }
    static synchronized void markRead(Context ctx){
        JSONArray list=read(ctx);
        for(int i=0;i<list.length();i++)try{list.getJSONObject(i).put("unread",false);}catch(Exception ignored){}
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,list.toString()).apply();
    }
}
