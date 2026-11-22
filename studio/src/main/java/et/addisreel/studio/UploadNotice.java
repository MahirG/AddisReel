package et.addisreel.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class UploadNotice {
    private UploadNotice(){}
    static void channel(Context ctx){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(UploadController.CHANNEL,
                "AddisReel Studio uploads",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Background movie upload progress");
            NotificationManager manager=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if(manager!=null)manager.createNotificationChannel(channel);
        }
    }
    static Notification make(Context ctx,String text,int percent,boolean ongoing) {
        channel(ctx);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=23)flags|=PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open=PendingIntent.getActivity(ctx,0,
                new Intent(ctx,StudioActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),flags);
        PendingIntent pause=PendingIntent.getBroadcast(ctx,1,
                new Intent(ctx,UploadControlReceiver.class).setAction("et.addisreel.studio.PAUSE"),flags);
        PendingIntent cancel=PendingIntent.getBroadcast(ctx,2,
                new Intent(ctx,UploadControlReceiver.class).setAction("et.addisreel.studio.CANCEL"),flags);
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?
                new Notification.Builder(ctx,UploadController.CHANNEL):new Notification.Builder(ctx);
        builder.setContentTitle("AddisReel Studio")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open).setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setShowWhen(false);
        if(ongoing){
            builder.setProgress(100,Math.max(0,Math.min(100,percent)),false)
                    .addAction(android.R.drawable.ic_media_pause,"Pause",pause)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",cancel);
        }else builder.setAutoCancel(true);
        return builder.build();
    }
    static void update(Context context,String text,int percent,boolean ongoing){
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager!=null)manager.notify(UploadController.NOTIFICATION_ID,
                make(context,text,percent,ongoing));
    }
}
