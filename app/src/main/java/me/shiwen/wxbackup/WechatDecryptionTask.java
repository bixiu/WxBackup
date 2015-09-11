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

    private static final String WECHAT_PACKAGE_NAME = "com.tencent.mm";
    private static final String WECHAT_BASE_DIR_RELATIVE = "../../com.tencent.mm/MicroMsg";
    private static final String DATABASE_FILE_NAME = "EnMicroMsg.db";
    private static final String WECHAT_USER_ID = "46f43394370ae8866016497c22894ace";
    private static final String WECHAT_SQLCIPHER_PASSWORD = "d9d70da";

    private Context context;

    public WechatDecryptionTask(Context context) {
        this.context = context;
    }

    private void logAndExecute(DataOutputStream commandStream, String command) throws
            IOException {
        Log.d(TAG, "su command: " + command);
        commandStream.writeBytes(command + "\n");
    }

    private boolean setDatabaseFilePermissions(File databaseFile) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream commandStream = new DataOutputStream(process.getOutputStream());

            logAndExecute(commandStream, "chmod 666 " + databaseFile.getPath());
            File parent = databaseFile.getParentFile();
            logAndExecute(commandStream, "chmod 777 " + parent.getPath());
            parent = parent.getParentFile();
            while (!parent.getName().equals(WECHAT_PACKAGE_NAME) && !parent.getName().equals("")) {
                logAndExecute(commandStream, "chmod 755 " + parent.getPath());
                parent = parent.getParentFile();
            }
            if (parent.getName().equals(WECHAT_PACKAGE_NAME)) {
                logAndExecute(commandStream, "chmod 755 " + parent.getPath());
            }

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

        return databaseFile.canWrite();
    }

    @Override
    protected Object doInBackground(Object... params) {
        File databaseFile;
        try {
            String wechatBaseDir = new File(context.getFilesDir(), WECHAT_BASE_DIR_RELATIVE)
                    .getCanonicalPath();
            databaseFile = new File(wechatBaseDir, WECHAT_USER_ID + "/" + DATABASE_FILE_NAME);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Can not get WeChat database file path");
            return null;
        }

        if (!setDatabaseFilePermissions(databaseFile)) {
            Log.e(TAG, "Setting database file permissions failed");
            return null;
        }

        SQLiteDatabase.loadLibs(context);
        SQLiteDatabaseHook hook = new SQLiteDatabaseHook() {
            public void preKey(SQLiteDatabase database) {
            }

            public void postKey(SQLiteDatabase database) {
                database.rawExecSQL("PRAGMA cipher_use_hmac = off");
                database.rawExecSQL("PRAGMA cipher_page_size = 1024");
                database.rawExecSQL("PRAGMA kdf_iter = 4000");
            }
        };

        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile,
                WECHAT_SQLCIPHER_PASSWORD, null, hook);
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
