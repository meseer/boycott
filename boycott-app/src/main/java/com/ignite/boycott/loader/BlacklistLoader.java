package com.ignite.boycott.loader;

import android.content.Context;
import android.os.FileObserver;
import android.support.v4.content.Loader;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.ignite.boycott.io.model.BoycottList;
import com.ignite.boycott.util.AppUtils;
import com.ignite.boycott.util.StreamUtils;
import com.squareup.okhttp.OkHttpClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.crashlytics.android.Crashlytics.logException;
import static com.ignite.boycott.util.StreamUtils.safeClose;
import static java.io.File.separator;

/**
 * Created by mdelegan on 10.02.14.
 */
public class BlacklistLoader extends android.support.v4.content.AsyncTaskLoader<BoycottList> {
    public static final URL BOYCOTT_LIST_URL;
    private static final String TAG = BlacklistLoader.class.getSimpleName();
    private static final String ASSET_JSON_PATH = "json";
    public static final String JSON_EXT = ".json";
    private static BlacklistLoader mInstance;
    private final String mAssetPath;
    private final String mJsonDirPath;
    private final String mName;
    private final Context mContext;
    private final FileObserver mJsonObserver;
    private OkHttpClient client;
    private BoycottList mBoycottList;
    private AtomicBoolean needUpdate = new AtomicBoolean(true);
    private String mJsonFilePath;
    //TODO: Use FileObserver to monitor list updates

    static {
        try {
            BOYCOTT_LIST_URL = new URL("https://script.google.com/macros/s/AKfycbxWbLBL6_7FJkb5fPj6PdyE45EoOCwgFVaAH6H0QmMcgiP8EVo6/exec?json=type3&zip=1");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    public BlacklistLoader(Context context) {
        super(context);
        AppUtils.startMonitoring(context);

        mContext = context;
        client = new OkHttpClient();
        client.setReadTimeout(100, TimeUnit.SECONDS);

        mName = "boycottList";
        mAssetPath = ASSET_JSON_PATH + separator + mName + JSON_EXT;
        mJsonDirPath = context.getApplicationInfo().dataDir + "/json";
        mJsonFilePath = mJsonDirPath + separator + mName + JSON_EXT;

        mJsonObserver = new FileObserver(mJsonFilePath) {
            @Override
            public void onEvent(int event, String path) {
                switch (event) {
                    case MODIFY:
                    case DELETE:
                    case CREATE:
                        needUpdate.set(true);
                }
            }
        };
        mJsonObserver.startWatching();
    }

    /**
     * Called when there is new data to deliver to the client.  The
     * super class will take care of delivering it; the implementation
     * here just adds a little more logic.
     */
    @Override public void deliverResult(BoycottList boycottList) {
        mBoycottList = boycottList;

        if (isStarted()) {
            // If the Loader is currently started, we can immediately
            // deliver its results.
            super.deliverResult(boycottList);
        }
    }

    /**
     * Handles a request to start the Loader.
     */
    @Override protected void onStartLoading() {
        if (mBoycottList != null) {
            // If we currently have a result available, deliver it
            // immediately.
            deliverResult(mBoycottList);
        }

        if (needUpdate.get() || takeContentChanged() || mBoycottList == null) {
            // If the data has changed since the last time it was loaded
            // or is not currently available, start a load.
            needUpdate.set(false);
            forceLoad();
        }
    }

    public BoycottList loadInBackground() {
        InputStream jsonStream = null;
        try {
//            jsonStream = openJsonStreamFromUrl(BOYCOTT_LIST_URL);
            File jsonFile = openOrCopyLocalBoycottList();
            try {
                jsonStream = openJsonStreamFromFile(jsonFile);
                return parseBlacklistJson(jsonStream);
            } catch (JsonSyntaxException e) {
                Log.e(TAG, "Malformed JSON file, deleting...", e);
                String absolutePath = jsonFile.getAbsolutePath();
                boolean isDeleted = jsonFile.delete();
                if (isDeleted) {
                    Log.i(TAG, "Succesfully deleted file " + absolutePath);
                }
                else {
                    Log.e(TAG, "Unable to delete file " + absolutePath);
                }
                // return empty list for the time being
                return null;
            }
        } catch (Exception e) {
            logException(e);
            throw new RuntimeException(e);
        } finally {
            safeClose(jsonStream);
        }
    }

    private File openOrCopyLocalBoycottList() {
        String path = this.mAssetPath;

        File f = new File(mJsonFilePath);
        if (f.exists()) return f;

        return copyFromAssets(path, mJsonFilePath);
    }

    private File copyFromAssets(String path, String dest) {
        Log.w(TAG, "copying boycott list from assets...");
        InputStream is;
        try {
            is = mContext.getAssets().open(path);
        } catch (IOException e) {
            logException(e);
            throw new RuntimeException("Unable to open " + path + " from assets");
        }

        try {
            File f = new File(mJsonDirPath + "/");
            if (!f.exists()) f.mkdir();

            FileOutputStream os = new FileOutputStream(dest);
            try {
                StreamUtils.copy(is, os);
            } finally {
                safeClose(os);
                safeClose(is);
            }

            Log.w(TAG, "boycott list copyFromAssets complete");
            return new File(dest);
        } catch (IOException e) {
            throw new RuntimeException("Unable to write " + dest + " to data directory", e);
        }
    }

    private InputStream openJsonStreamFromFile(File file) {
        try {
            return new FileInputStream(file);
        } catch (FileNotFoundException e) {
            logException(e);
            throw new RuntimeException(e);
        }
    }

    private InputStream openJsonStreamFromUrl(URL boycottListUrl) throws IOException {
        HttpURLConnection con = client.open(boycottListUrl);

        InputStream cis = null;
        Base64InputStream bis = null;
        ZipInputStream zis = null;
        try {
            cis = con.getInputStream();
            bis = new Base64InputStream(cis, Base64.DEFAULT);
            zis = new ZipInputStream(bis);
            return getFirstZipEntryAsStream(zis);
        } finally {
            safeClose(bis);
            safeClose(cis);
            safeClose(zis);
            con.disconnect();
        }
    }

    @Override
    protected void onReset() {
        super.onReset();
        mBoycottList = null;
        needUpdate.set(true);
    }

    private InputStream getFirstZipEntryAsStream(ZipInputStream zis) throws IOException {
        ZipEntry ze = zis.getNextEntry();
        if (ze != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                StreamUtils.copy(zis, baos);
                return new ByteArrayInputStream(baos.toByteArray());
            } finally {
                safeClose(baos);
            }
        }
        throw new RuntimeException("Downloaded ZIP is empty");
    }

    public BoycottList parseBlacklistJson(InputStream stream) throws JsonSyntaxException {
        InputStreamReader is = new InputStreamReader(stream, Charset.forName("UTF-8"));
        try {
            return new Gson().fromJson(is, BoycottList.class);
        } finally {
            safeClose(is);
        }
    }

    public static Loader<BoycottList> instance(Context context) {
        if (mInstance == null) {
            synchronized (BlacklistLoader.class) {
                if (mInstance == null)
                    mInstance = new BlacklistLoader(context);
            }
        }
        return mInstance;
    }
}
