package et.addisreel.studio;

import android.content.Context;
import android.content.Intent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.Build;
import org.json.JSONObject;

/** Only user-initiated upload actions schedule native work. */
final class UploadController {
    static final String URL = "https://ar.eddieaman475.workers.dev";
    static final int JOB_ID = 77331;
    static final int NOTIFICATION_ID = 77331;
    static final String CHANNEL = "studio_uploads";
    private UploadController() {}

    static void start(Context context) {
        JSONObject pending = UploadStore.read(context);
        if (pending == null) throw new IllegalStateException("Choose an MP4 first");
        UploadStore.changeState(context, "queued", "Waiting for network");
        if (Build.VERSION.SDK_INT >= 34) {
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, UploadJobService.class))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            long size = pending.optLong("size", 0);
            if (size > 0) builder.setEstimatedNetworkBytes(0L, size);
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null || scheduler.schedule(builder.build()) != JobScheduler.RESULT_SUCCESS)
                throw new IllegalStateException("Android could not start the background transfer");
        } else {
            Intent intent = new Intent(context, UploadForegroundService.class).setAction("start");
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        }
    }
    static void pause(Context context) {
        UploadStore.changeState(context, "paused", "Paused by owner");
        if (Build.VERSION.SDK_INT >= 34) {
            JobScheduler manager = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (manager != null) manager.cancel(JOB_ID);
        } else context.stopService(new Intent(context, UploadForegroundService.class));
    }
    static void cancel(Context context) {
        pause(context);
        UploadStore.clear(context);
        android.app.NotificationManager manager=(android.app.NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager!=null)manager.cancel(NOTIFICATION_ID);
    }
}
