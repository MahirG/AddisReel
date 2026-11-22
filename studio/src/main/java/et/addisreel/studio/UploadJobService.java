package et.addisreel.studio;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Build;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android 14+ user-initiated data transfer survives Studio minimization. */
public final class UploadJobService extends JobService {
    private final AtomicBoolean stopped=new AtomicBoolean(false);
    private volatile Thread worker;
    @Override public boolean onStartJob(JobParameters params){
        if(Build.VERSION.SDK_INT<34)return false;
        stopped.set(false);
        UploadNotice.channel(this);
        setNotification(params, UploadController.NOTIFICATION_ID,
            UploadNotice.make(this,"Preparing movie upload…",0,true),
            JobService.JOB_END_NOTIFICATION_POLICY_DETACH);
        worker=new Thread(()->{
            boolean retry=new UploadEngine(this,new UploadEngine.Progress(){
                @Override public boolean interrupted(){return stopped.get();}
                @Override public void report(String text,int percent){
                    if(!stopped.get())UploadNotice.update(UploadJobService.this,text,percent,
                        percent<100);
                }
            }).run();
            if(!stopped.get())jobFinished(params,retry);
        },"AddisReel-UIDT");
        worker.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        stopped.set(true);
        Thread current=worker;
        if(current!=null)current.interrupt();
        // Persisted part receipts permit a system reschedule when restrictions lift.
        return true;
    }
}
