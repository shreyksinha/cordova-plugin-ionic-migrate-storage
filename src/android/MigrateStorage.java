package com.migrate.android;

import android.content.Context;
import android.util.Log;

import com.appunite.leveldb.LevelDB;
import com.appunite.leveldb.LevelIterator;
import com.appunite.leveldb.Utils;
import com.appunite.leveldb.WriteBatch;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;

import org.apache.cordova.CordovaWebView;
import java.io.File;

/**
 * Main class that is instantiated by cordova
 * Acts as a "bridge" between the SDK and the cordova layer
 * 
 * This plugin migrates WebSQL and localStorage from the old webview to the new webview
 * 
 * TODO
 * - Test if we can we remove old file:// keys?
 * - Properly handle exceptions? We have a catch-all at the moment that is dealt with in the `initialize` function
 * - migrating IndexedDB (may not be possible because of leveldb complexities)
 */
public class MigrateStorage extends CordovaPlugin {
    // Switch this value to enable debug mode
    private static final boolean DEBUG_MODE = true;

    private static final String TAG = "com.migrate.android";
    private static final String DEFAULT_NEW_HOSTNAME = "localhost";
    private static final String DEFAULT_NEW_SCHEME = "http";
    private static final String DEFAULT_NEW_PORT_NUMBER = "";

    private static final String DEFAULT_PREV_HOSTNAME = "localhost";
    private static final String DEFAULT_PREV_SCHEME = "http";
    private static final String DEFAULT_PREV_PORT_NUMBER = "8080";

    private static final String SETTING_NEW_PORT_NUMBER = "WKPort";
    private static final String SETTING_NEW_HOSTNAME = "Hostname";
    private static final String SETTING_NEW_SCHEME = "Scheme";

    private static final String SETTING_PREV_PORT_NUMBER = "MIGRATE_STORAGE_PREV_PORT_NUMBER";
    private static final String SETTING_PREV_HOSTNAME = "MIGRATE_STORAGE_PREV_HOSTNAME";
    private static final String SETTING_PREV_SCHEME = "MIGRATE_STORAGE_PREV_SCHEME";

    private static final String KEY_SEPARATOR_BYTES = "\u0000\u0001";

    private String prevPortNumber;
    private String prevHostname;
    private String prevScheme;

    private String newPortNumber;
    private String newHostname;
    private String newScheme;

    private void logDebug(String message) {
        if(DEBUG_MODE) Log.d(TAG, message);
    }

    private String getPrevLocalStorageBaseURL() {
        String result = this.prevScheme + "://" + this.prevHostname;
        if(this.prevPortNumber != null && !this.prevPortNumber.isEmpty()) {
            result += ":" + this.prevPortNumber;
        }
        return result;
    }

    private String getNewLocalStorageBaseURL() {
        String result = this.newScheme + "://" + this.newHostname;
        if(this.newPortNumber != null && !this.newPortNumber.isEmpty()) {
            result += ":" + this.newPortNumber;
        }
        return result;
    }

    private String getRootPath() {
        Context context = cordova.getActivity().getApplicationContext();
        return context.getFilesDir().getAbsolutePath().replaceAll("/files", "");
    }

    private String getWebViewRootPath() {
        return this.getRootPath() + "/app_webview";
    }

    private String getLocalStorageRootPath() {
        return this.getWebViewRootPath() + "/Local Storage";
    }

    /**
     * Migrate localStorage from `{prevScheme}://{prevHostname}:{prevPort}` to `{newScheme}://{newHostname}:{newPort}`
     *
     * @throws Exception - Can throw LevelDBException
     */
    private void migrateLocalStorage() throws Exception {
        this.logDebug("migrateLocalStorage: Migrating localStorage..");

        String levelDbPath = this.getLocalStorageRootPath() + "/leveldb";
        this.logDebug("migrateLocalStorage: levelDbPath: " + levelDbPath);

        File levelDbDir = new File(levelDbPath);
        if(!levelDbDir.isDirectory() || !levelDbDir.exists()) {
            this.logDebug("migrateLocalStorage: '" + levelDbPath + "' is not a directory or was not found; Exiting");
            return;
        }

        LevelDB db = new LevelDB(levelDbPath);

        String prevLocalStorageBaseURL = this.getPrevLocalStorageBaseURL();
        String prevLocalStorageMetaKey = "META:" + prevLocalStorageBaseURL;
        String prevLocalStorageKeyPrefix = "_" + prevLocalStorageBaseURL + KEY_SEPARATOR_BYTES;

        String newLocalStorageBaseURL = this.getNewLocalStorageBaseURL();
        String newLocalStorageMetaKey = "META:" + newLocalStorageBaseURL;

        if(db.exists(Utils.stringToBytes(newLocalStorageMetaKey))) {
            this.logDebug("migrateLocalStorage: Found '" + newLocalStorageMetaKey + "' key; Skipping migration");
            db.close();
            return;
        }

        // Yes, there is a typo here; `newInterator` 😔
        LevelIterator iterator = db.newInterator();

        // To update in bulk!
        WriteBatch batch = new WriteBatch();

        // 🔃 Loop through the keys and replace `{prevScheme}://{prevHostname}:{prevPort}` with `{newScheme}://{newHostname}:{newPort}`
        logDebug("migrateLocalStorage: Starting replacements;");
        for(iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
            String key = Utils.bytesToString(iterator.key());
            byte[] value = iterator.value();
            if (key.startsWith(prevLocalStorageKeyPrefix) || key.equals(prevLocalStorageMetaKey)) {
                String newKey = key.replace(prevLocalStorageBaseURL, newLocalStorageBaseURL);
                logDebug("migrateLocalStorage: Changing key: " + key + " to '" + newKey + "'");
                // Add new key to db
                batch.putBytes(Utils.stringToBytes(newKey), value);
            } else {
                logDebug("migrateLocalStorage: Skipping key:" + key);
            }
        }

        // Commit batch to DB
        db.write(batch);

        iterator.close();
        db.close();

        this.logDebug("migrateLocalStorage: Successfully migrated localStorage..");
    }


    /**
     * Sets up the plugin interface
     *
     * @param cordova - cdvInterface that contains cordova goodies
     * @param webView - the webview that we're running
     */
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        try {
            super.initialize(cordova, webView);

            this.prevPortNumber = this.preferences.getString(SETTING_PREV_PORT_NUMBER, "");
            if(this.prevPortNumber == null || this.prevPortNumber.isEmpty()) {
                this.prevPortNumber = DEFAULT_PREV_PORT_NUMBER;
            }
            this.prevHostname = this.preferences.getString(SETTING_PREV_HOSTNAME, "");
            if(this.prevHostname == null || this.prevHostname.isEmpty()) {
                this.prevHostname = DEFAULT_PREV_HOSTNAME;
            }
            this.prevScheme = this.preferences.getString(SETTING_PREV_SCHEME, "");
            if(this.prevScheme == null || this.prevScheme.isEmpty()) {
                this.prevScheme = DEFAULT_PREV_SCHEME;
            }

            this.newPortNumber = this.preferences.getString(SETTING_NEW_PORT_NUMBER, "");
            if(this.newPortNumber == null || this.newPortNumber.isEmpty()) {
                this.newPortNumber = DEFAULT_NEW_PORT_NUMBER;
            }

            this.newHostname = this.preferences.getString(SETTING_NEW_HOSTNAME, "");
            if(this.newHostname == null || this.newHostname.isEmpty()) {
                this.newHostname = DEFAULT_NEW_HOSTNAME;
            }

            this.newScheme = this.preferences.getString(SETTING_NEW_SCHEME, "");
            if(this.newScheme == null || this.newScheme.isEmpty()) {
                this.newScheme = DEFAULT_NEW_SCHEME;
            }

            logDebug("Starting migration;");

            this.migrateLocalStorage();

            logDebug("Migration completed;");
        } catch (Exception ex) {
            logDebug("Migration filed due to error: " + ex.getMessage());
        }
    }
}
