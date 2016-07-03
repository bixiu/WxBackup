package me.shiwen.wxbackup;

import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class WechatDecryptionTask extends AsyncTask<Object, Integer, Object> {

    private static final String LOGCAT_TAG = "WxBackup";

    private static final String WECHAT_USER_HASH = "46f43394370ae8866016497c22894ace";
    private static final String FRIEND_USERNAME = "karen0123";

    private static final String WECHAT_PACKAGE_NAME = "com.tencent.mm";
    private static final String WECHAT_CONFIG_PATH = "shared_prefs/system_config_prefs.xml";
    private static final String WECHAT_MICROMSG_SUBDIR = "MicroMsg";
    private static final String WECHAT_DATABASE_FILE = "EnMicroMsg.db";
    private static final String BACKUP_DATABASE_FILE = "backup.db";
    private static final String UIN_REGEX = "'(?<=<int name=\"default_uin\" value=\")" +
            "[0-9]+(?=\" />)'";

    private static final String CREATE_MESSAGE_TABLE_SQL =
            "CREATE TABLE backup.message ( msgId INTEGER, msgSvrId INTEGER PRIMARY KEY, " +
                    "type INT, status INT, isSend INT, isShowTimer INTEGER, createTime INTEGER, " +
                    "talker TEXT, content TEXT, imgPath TEXT, reserved TEXT, lvbuffer BLOB, " +
                    "transContent TEXT, transBrandWording TEXT, talkerId INTEGER, " +
                    "bizClientMsgId TEXT, bizChatId INTEGER DEFAULT -1, bizChatUserId TEXT )";
    private static final String CREATE_TIME_INDEX_SQL =
            "CREATE INDEX backup.messageCreateTimeIndex ON message ( createTime )";

    private Context context;
    private File wechatDir;
    private File wechatDatabaseFile;
    private File backupDatabaseFile;
    private String uin;

    public WechatDecryptionTask(Context context) {
        this.context = context;
        File filesDir = context.getFilesDir();
        wechatDir = new File(filesDir.getParentFile().getParent(), WECHAT_PACKAGE_NAME);
        wechatDatabaseFile = getWechatDatabaseFile();
        backupDatabaseFile = new File(filesDir, BACKUP_DATABASE_FILE);
    }

    private File getWechatDatabaseFile() {
        return new File(wechatDir, WECHAT_MICROMSG_SUBDIR + "/" + WECHAT_USER_HASH + "/" +
                WECHAT_DATABASE_FILE);  // TODO resolve WECHAT_USER_HASH
    }

    private void issueCommand(DataOutputStream commandStream, String command) throws IOException {
        Log.d(LOGCAT_TAG, "su command: " + command);
        commandStream.writeBytes(command + "\n");
    }

    private String getUin(DataOutputStream commandStream, BufferedReader outReader)
            throws IOException {
        issueCommand(commandStream, "grep -Po " + UIN_REGEX + " " +
                new File(wechatDir, WECHAT_CONFIG_PATH).getPath());
        commandStream.flush();
        String uin = outReader.readLine();
        Log.d(LOGCAT_TAG, "su stdout: " + uin);
        return uin;
    }

    private void setDatabaseFilePermissions(DataOutputStream commandStream)
            throws IOException {
        issueCommand(commandStream, "chmod o+rw " + wechatDatabaseFile.getPath());
        issueCommand(commandStream, "chmod o+rwx " + wechatDatabaseFile.getParent());
        // TODO maybe write permission is not needed
        issueCommand(commandStream, "chmod -R o+rx " + wechatDir.getPath());
        commandStream.flush();
    }

    private void privilegedCommands() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream commandStream = new DataOutputStream(process.getOutputStream());
            BufferedReader errReader = new BufferedReader(new InputStreamReader(
                    process.getErrorStream()));
            BufferedReader outReader = new BufferedReader(new InputStreamReader(
                    process.getInputStream()));

            uin = getUin(commandStream, outReader);
            setDatabaseFilePermissions(commandStream);

            commandStream.writeBytes("exit\n");
            commandStream.flush();
            String line;
            while ((line = outReader.readLine()) != null) {
                Log.d(LOGCAT_TAG, "su stdout: " + line);
            }
            while ((line = errReader.readLine()) != null) {
                Log.d(LOGCAT_TAG, "su stderr: " + line);
            }
            process.waitFor();
            Log.d(LOGCAT_TAG, "su process finished");
        } catch (IOException | InterruptedException e) {
            Log.e(LOGCAT_TAG, "su process failed", e);
            e.printStackTrace();
        }
    }

    private String md5(String s) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e(LOGCAT_TAG, "md5 error", e);
            return null;
        }
        byte[] bytes = md.digest(s.getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private String getPassword() {
        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        String md5 = md5(tm.getDeviceId() + uin);
        return md5 == null ? "" : md5.substring(0, 7);
    }

    private SQLiteDatabase getDatabase() {
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
        return SQLiteDatabase.openOrCreateDatabase(wechatDatabaseFile, getPassword(), null, hook);
    }

    private void backupMessages(SQLiteDatabase database) {
        database.rawExecSQL("ATTACH DATABASE '" + backupDatabaseFile.getPath() +
                "' AS backup KEY ''");
        Cursor cursor = database.rawQuery("SELECT tbl_name FROM backup.sqlite_master WHERE " +
                "type = ? AND tbl_name = ?", new String[]{"table", "message"});
        if (!cursor.moveToFirst()) {
            database.rawExecSQL(CREATE_MESSAGE_TABLE_SQL);
            database.rawExecSQL(CREATE_TIME_INDEX_SQL);
            Log.d(LOGCAT_TAG, "create message table in backup database");
        }
        cursor.close();
        database.rawExecSQL("INSERT OR REPLACE INTO backup.message SELECT * FROM message WHERE " +
                "talker = '" + FRIEND_USERNAME + "'");
    }

    @Override
    protected Object doInBackground(Object... params) {
        privilegedCommands();
        if (uin == null || !wechatDatabaseFile.canWrite()) {
            return null;
        }

        SQLiteDatabase database;
        try {
            database = getDatabase();
        } catch (RuntimeException e) {
            Log.e(LOGCAT_TAG, "can not open database file", e);
            return null;
        }
        Log.d(LOGCAT_TAG, "database open");

        backupMessages(database);
        database.rawExecSQL("DELETE FROM rconversation WHERE username = '" + FRIEND_USERNAME + "'");
        database.close();
        Log.d(LOGCAT_TAG, "database closed");

        return null;
    }

}
