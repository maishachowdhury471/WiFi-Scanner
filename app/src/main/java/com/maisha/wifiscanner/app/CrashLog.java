package com.maisha.wifiscanner.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * অ্যাপ বন্ধ হয়ে গেলে (ক্র্যাশ) কারণটা ফাইলে লিখে রাখে।
 * পরের বার অ্যাপ খুললে কারণটা স্ক্রিনে দেখায়, যাতে স্ক্রিনশট পাঠিয়ে
 * আসল সমস্যাটা ঠিক করা যায়।
 */
public class CrashLog {
    private static final String FILE = "crash-log.txt";
    private static boolean installed = false;

    public static void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try { write(ctx.getApplicationContext(), e); } catch (Exception ignored) {}
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static void write(Context ctx, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("সময়: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            String version = "?";
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0);
                version = pi.versionName + " (" + pi.versionCode + ")";
            } catch (Exception ignored) {}
            pw.println("অ্যাপ: ওয়াইফাই স্ক্যানার " + version);
            pw.println("ফোন: " + Build.MANUFACTURER + " " + Build.MODEL + " — Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println();
            e.printStackTrace(pw);
            pw.close();
            FileWriter fw = new FileWriter(new File(ctx.getFilesDir(), FILE), false);
            fw.write(sw.toString());
            fw.close();
        } catch (Exception ignored) {}
    }

    public static String read(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context ctx) {
        try { new File(ctx.getFilesDir(), FILE).delete(); } catch (Exception ignored) {}
    }

    public static void showIfExists(Activity act) {
        String log = read(act);
        if (log == null || log.isEmpty()) return;
        TextView info = new TextView(act);
        info.setText("অ্যাপটা বন্ধ হয়ে গিয়েছিল। নিচের লেখাটার স্ক্রিনশট নিয়ে পাঠাও, তাহলে আসল কারণটা ঠিক করে দিতে পারব।");
        info.setPadding(24, 16, 24, 8);
        info.setTextSize(14);
        TextView tv = new TextView(act);
        tv.setText(log);
        tv.setTextSize(11);
        tv.setPadding(24, 8, 24, 16);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(act);
        sv.addView(tv);
        LinearLayout layout = new LinearLayout(act);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(info);
        layout.addView(sv);
        new AlertDialog.Builder(act)
                .setTitle("অ্যাপ বন্ধ হওয়ার কারণ")
                .setView(layout)
                .setPositiveButton("📋 কপি করো", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("crash log", log));
                })
                .setNegativeButton("পরে দেখব", null)
                .setNeutralButton("মুছে দাও", (d, w) -> clear(act))
                .show();
    }
}
