package me.shiwen.wxbackup;

import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.util.Log;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

public class WechatDecryptionTask extends AsyncTask<Object, Integer, Object> {

    private static final String TAG = "WxBackup";

    private static final String WECHAT_BASE_DIR_RELATIVE = "../../com.tencent.mm/MicroMsg";
    private static final String DATABASE_FILE = "EnMicroMsg.db";
    private static final String WECHAT_USER_ID = "46f43394370ae8866016497c22894ace";

    private Context context;

    public WechatDecryptionTask(Context context) {
        this.context = context;
    }

    private boolean copyDatabaseFile(String wechatBaseDir, String wechatUserId, String targetDir) {
        String sourceDatabaseFile = wechatBaseDir + "/" + wechatUserId + "/" + DATABASE_FILE;
        File targetFile = new File(targetDir, DATABASE_FILE);
        String command = "cp " + sourceDatabaseFile + " " + targetDir;
        Log.d(TAG, "Copy WeChat database file: " + command);

        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream commandStream = new DataOutputStream(process.getOutputStream());
            commandStream.writeBytes(command + "\n");
            commandStream.writeBytes("chmod 666 " + targetFile.getPath() + "\n");
            commandStream.writeBytes("exit\n");
            commandStream.flush();

            String line;
            BufferedReader errReader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream()));
            while ((line = errReader.readLine()) != null) {
                Log.d(TAG, "su stderr: " + line);
            }
            BufferedReader outReader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream()));
            while ((line = outReader.readLine()) != null) {
                Log.d(TAG, "su stdout: " + line);
            }

            process.waitFor();
            Log.d(TAG, "su process finished");
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }

        return targetFile.exists() && targetFile.canRead();
    }

    @Override
    protected Object doInBackground(Object... params) {
        String workingDir = context.getFilesDir().getPath();
        String wechatBaseDir;
        try {
            wechatBaseDir = new File(workingDir, WECHAT_BASE_DIR_RELATIVE).getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Can not get WeChat base directory");
            return null;
        }

        if (!copyDatabaseFile(wechatBaseDir, WECHAT_USER_ID, workingDir)) {
            Log.e(TAG, "Database file copy failed");
            return null;
        }

        SQLiteDatabase.loadLibs(context);
        SQLiteDatabaseHook hook = new SQLiteDatabaseHook() {
            public void preKey(SQLiteDatabase database) {
            }

            public void postKey(SQLiteDatabase database) {
                Cursor cursor = database.rawQuery("PRAGMA cipher_migrate", new String[]{});
                cursor.moveToFirst();
                Log.d(TAG, "cipher_migrate return code: " + cursor.getString(0));
                cursor.close();
            }
        };

        File databaseFile = new File(workingDir, DATABASE_FILE);
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, "d9d70da", null,
                hook);
        Log.d(TAG, "database open");
        Cursor cursor = database.query("message", new String[]{"createTime", "talker", "content"},
                null, null, null, null, null);
        while (cursor.moveToNext()) {
            String message = cursor.getString(2);
            message = message == null ? "null" : message;
            Log.d(TAG, message);
        }
        cursor.close();
        database.close();
        Log.d(TAG, "database closed");

        return null;
    }

}
