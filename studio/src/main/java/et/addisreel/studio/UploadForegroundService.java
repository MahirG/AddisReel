package et.addisreel.studio;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pre-Android 14 fallback: notification-backed dataSync foreground service. */
public final class UploadForegroundService extends Service {
    private final AtomicBoolean stopped=new AtomicBoolean(false);
    private volatile Thread worker;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        stopped.set(false);
        UploadNotice.channel(this);
        startForeground(UploadController.NOTIFICATION_ID,
                UploadNotice.make(this,"Preparing movie upload…",0,true));
        if(worker!=null&&worker.isAlive())return START_STICKY;
        worker=new Thread(()->{
            boolean retry=new UploadEngine(this,new UploadEngine.Progress(){
                @Override public boolean interrupted(){return stopped.get();}
                @Override public void report(String text,int percent){
                    if(!stopped.get())UploadNotice.update(UploadForegroundService.this,text,
                        percent,percent<100);
                }
            }).run();
            if(retry&&!stopped.get())
                UploadNotice.update(this,"Upload interrupted — open Studio to resume",0,false);
            stopSelf(startId);
        },"AddisReel-ForegroundUpload");
        worker.start();
        return START_REDELIVER_INTENT;
    }
    @Override public void onDestroy(){
        stopped.set(true);
        Thread current=worker;if(current!=null)current.interrupt();
        super.onDestroy();
    }
}
