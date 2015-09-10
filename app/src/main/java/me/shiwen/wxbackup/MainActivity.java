package me.shiwen.wxbackup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WxBackup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Context context = getApplicationContext();
        Intent startServiceIntent = new Intent(context, BackupService.class);
        context.startService(startServiceIntent);

        new WechatDecryptionTask(this).execute();
    }

}
