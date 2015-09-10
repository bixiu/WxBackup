package me.shiwen.wxbackup;

import android.app.IntentService;
import android.content.Intent;

public class BackupService extends IntentService {

    private static final String TAG = "WxBackup";

    public BackupService() {
        super("BackupService");
    }

    public BackupService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // Release the wake lock provided by the WakefulBroadcastReceiver
        BootBroadcastReceiver.completeWakefulIntent(intent);
    }

}
