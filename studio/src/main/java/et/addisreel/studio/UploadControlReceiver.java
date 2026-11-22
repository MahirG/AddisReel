package et.addisreel.studio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class UploadControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent==null?null:intent.getAction();
        if("et.addisreel.studio.PAUSE".equals(action)){
            UploadController.pause(context);
            UploadNotice.update(context,"Paused — open Studio to resume",0,false);
        } else if("et.addisreel.studio.CANCEL".equals(action)){
            UploadController.cancel(context);
        }
    }
}
