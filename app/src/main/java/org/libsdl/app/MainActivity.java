package org.libsdl.app;

import android.os.Bundle;
import android.system.Os;
import android.system.ErrnoException;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.FileWriter;

public class MainActivity extends SDLActivity {

    private void setupCrashLogger() {
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                File logFile = new File(getFilesDir(), "abuse_crash.txt");
                PrintWriter pw = new PrintWriter(new FileWriter(logFile));
                ex.printStackTrace(pw);
                pw.close();
            } catch (Exception e) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setupCrashLogger();
        copyAssets();
        try {
            Os.setenv("ABUSE_PATH", getFilesDir().getAbsolutePath() + "/", true);
        } catch (ErrnoException e) { e.printStackTrace(); }
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void copyAssets() {
        File marker = new File(getFilesDir(), ".assets_extracted");
        if (marker.exists()) return;
        copyAssetFolder(getAssets(), "", getFilesDir().getAbsolutePath());
        try { marker.createNewFile(); } catch (IOException e) {}
    }

    private void copyAssetFolder(AssetManager am, String fromPath, String toPath) {
        try {
            String[] files = am.list(fromPath);
            if (files != null && files.length > 0) {
                new File(toPath).mkdirs();
                for (String f : files) {
                    String assetPath = fromPath.isEmpty() ? f : fromPath + "/" + f;
                    copyAssetFolder(am, assetPath, toPath + "/" + f);
                }
            } else {
                copyAssetFile(am, fromPath, toPath);
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void copyAssetFile(AssetManager am, String fromPath, String toPath) {
        try {
            InputStream in = am.open(fromPath);
            new File(toPath).getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(toPath);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            out.close();
        } catch (IOException e) { e.printStackTrace(); }
    }
}
