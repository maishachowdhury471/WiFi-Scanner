package com.maisha.wifiscanner.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 101;
    private WifiManager wifiManager;
    private Handler handler = new Handler(Looper.getMainLooper());
    private List<ScanResult> lastResults = new ArrayList<>();
    private String connectedSsid = "";
    private boolean scanReceiverRegistered = false;
    private boolean speedRunning = false;
    private boolean devicesRunning = false;

    private TextView warningText, scanStatus, myWifiSignalLabel, channelAdvice, speedResult, devicesStatus;
    private LinearLayout scanResults, myWifiInfo, channelList, devicesList;
    private LinearLayout sectionScan, sectionMyWifi, sectionChannel, sectionSpeed, sectionDevices;
    private ProgressBar myWifiSignalBar, speedProgress;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean ok = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (ok || true) {
                try {
                    List<ScanResult> results = wifiManager.getScanResults();
                    if (results != null && !results.isEmpty()) {
                        lastResults = new ArrayList<>(results);
                        showScanResults();
                        showChannels();
                    } else {
                        scanStatus.setText("কোনো WiFi পাওয়া যায়নি। লোকেশন চালু আছে কি না দেখো, আরেকবার স্ক্যান করো।");
                    }
                } catch (SecurityException e) {
                    scanStatus.setText("অনুমতি দরকার — লোকেশনের অনুমতি দাও।");
                }
            }
        }
    };

    private final Runnable signalUpdater = new Runnable() {
        @Override
        public void run() {
            if (sectionMyWifi.getVisibility() == View.VISIBLE) {
                showMyWifi();
            }
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        warningText = findViewById(R.id.warningText);
        scanStatus = findViewById(R.id.scanStatus);
        myWifiSignalLabel = findViewById(R.id.myWifiSignalLabel);
        channelAdvice = findViewById(R.id.channelAdvice);
        speedResult = findViewById(R.id.speedResult);
        devicesStatus = findViewById(R.id.devicesStatus);
        scanResults = findViewById(R.id.scanResults);
        myWifiInfo = findViewById(R.id.myWifiInfo);
        channelList = findViewById(R.id.channelList);
        devicesList = findViewById(R.id.devicesList);
        sectionScan = findViewById(R.id.sectionScan);
        sectionMyWifi = findViewById(R.id.sectionMyWifi);
        sectionChannel = findViewById(R.id.sectionChannel);
        sectionSpeed = findViewById(R.id.sectionSpeed);
        sectionDevices = findViewById(R.id.sectionDevices);
        myWifiSignalBar = findViewById(R.id.myWifiSignalBar);
        speedProgress = findViewById(R.id.speedProgress);

        findViewById(R.id.tabScan).setOnClickListener(v -> showSection(sectionScan));
        findViewById(R.id.tabMyWifi).setOnClickListener(v -> { showSection(sectionMyWifi); showMyWifi(); });
        findViewById(R.id.tabChannel).setOnClickListener(v -> { showSection(sectionChannel); showChannels(); });
        findViewById(R.id.tabSpeed).setOnClickListener(v -> showSection(sectionSpeed));
        findViewById(R.id.tabDevices).setOnClickListener(v -> showSection(sectionDevices));

        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnRefreshMyWifi).setOnClickListener(v -> showMyWifi());
        findViewById(R.id.btnSpeed).setOnClickListener(v -> runSpeedTest());
        findViewById(R.id.btnDevices).setOnClickListener(v -> runDeviceScan());
        findViewById(R.id.btnWifiSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        findViewById(R.id.btnRouterPage).setOnClickListener(v -> openRouterPage());

        checkPermissions();
        handler.post(signalUpdater);
    }

    private void showSection(LinearLayout section) {
        sectionScan.setVisibility(View.GONE);
        sectionMyWifi.setVisibility(View.GONE);
        sectionChannel.setVisibility(View.GONE);
        sectionSpeed.setVisibility(View.GONE);
        sectionDevices.setVisibility(View.GONE);
        section.setVisibility(View.VISIBLE);
    }

    // ---------- Permissions & location ----------

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void checkPermissions() {
        updateWarning();
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, neededPermissions(), REQ_PERMS);
        } else {
            startScan();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updateWarning();
        if (hasPermissions()) {
            startScan();
        } else {
            Toast.makeText(this, "লোকেশনের অনুমতি না দিলে স্ক্যান দেখাবে না।", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isLocationOn() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Exception e) {
            return false;
        }
    }

    private void updateWarning() {
        boolean show = !hasPermissions() || !isLocationOn();
        warningText.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            if (!hasPermissions()) {
                warningText.setText("⚠️ স্ক্যান করতে লোকেশনের অনুমতি দিতে হবে। অনুমতি না দিলে আশেপাশের WiFi দেখাবে না।");
            } else {
                warningText.setText("⚠️ ফোনের লোকেশন বন্ধ আছে। লোকেশন চালু করো, নাহলে স্ক্যান খালি দেখাবে।");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateWarning();
        if (!scanReceiverRegistered) {
            registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            scanReceiverRegistered = true;
        }
        if (sectionMyWifi.getVisibility() == View.VISIBLE) showMyWifi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanReceiverRegistered) {
            try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
            scanReceiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(signalUpdater);
    }

    // ---------- Scan ----------

    private void startScan() {
        if (wifiManager == null) return;
        if (!hasPermissions()) {
            checkPermissions();
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            scanStatus.setText("WiFi বন্ধ আছে — ফোনের WiFi চালু করো, তারপর স্ক্যান করো।");
            Toast.makeText(this, "WiFi বন্ধ আছে", Toast.LENGTH_SHORT).show();
            return;
        }
        updateWarning();
        scanStatus.setText("স্ক্যান হচ্ছে…");
        boolean started;
        try {
            started = wifiManager.startScan();
        } catch (SecurityException e) {
            scanStatus.setText("অনুমতি দরকার — লোকেশনের অনুমতি দাও।");
            return;
        }
        if (!started) {
            // Android limits scans (4 per 2 min). Show the last results instead.
            if (!lastResults.isEmpty()) {
                showScanResults();
                scanStatus.setText("এইমাত্র স্ক্যান করা যায়নি (অ্যান্ড্রয়েডের সীমা) — আগের ফল দেখাচ্ছি, একটু পরে আবার চাপো।");
            } else {
                scanStatus.setText("স্ক্যান শুরু করা যায়নি — একটু পরে আবার চাপো।");
            }
        }
    }

    private void showScanResults() {
        scanResults.removeAllViews();
        if (lastResults.isEmpty()) {
            scanStatus.setText("কোনো WiFi পাওয়া যায়নি।");
            return;
        }
        // Strongest first, one row per SSID (strongest BSSID wins).
        List<ScanResult> sorted = new ArrayList<>(lastResults);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.level, a.level));
        Map<String, ScanResult> best = new HashMap<>();
        for (ScanResult r : sorted) {
            String key = (r.SSID == null || r.SSID.isEmpty()) ? ("(লুকানো) " + r.BSSID) : r.SSID;
            if (!best.containsKey(key)) best.put(key, r);
        }
        List<ScanResult> rows = new ArrayList<>(best.values());
        Collections.sort(rows, (a, b) -> Integer.compare(b.level, a.level));

        WifiInfo info = null;
        try { info = wifiManager.getConnectionInfo(); } catch (Exception ignored) {}
        if (info != null && info.getSSID() != null) {
            connectedSsid = info.getSSID().replace("\"", "");
        }
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        scanStatus.setText(rows.size() + "টি WiFi পাওয়া গেছে • " + time + " • শক্তিশালীটা উপরে");

        for (ScanResult r : rows) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(0xFFFFFFFF);
            card.setPadding(24, 20, 24, 20);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 16);
            card.setLayoutParams(lp);

            String ssid = (r.SSID == null || r.SSID.isEmpty()) ? "লুকানো নেটওয়ার্ক" : r.SSID;
            boolean isConnected = ssid.equals(connectedSsid);
            TextView title = new TextView(this);
            title.setText((isConnected ? "✅ যুক্ত আছেন — " : "") + ssid + "  " + signalBars(r.level));
            title.setTextSize(16);
            title.setTextColor(0xFF0f172a);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(title);

            TextView line2 = new TextView(this);
            line2.setText("সিগন্যাল: " + r.level + " dBm (" + signalWord(r.level) + ") • "
                    + bandName(r.frequency) + " • চ্যানেল " + freqToChannel(r.frequency));
            line2.setTextSize(13);
            line2.setTextColor(0xFF334155);
            card.addView(line2);

            TextView line3 = new TextView(this);
            line3.setText("নিরাপত্তা: " + securityName(r.capabilities) + " • BSSID: " + r.BSSID);
            line3.setTextSize(13);
            line3.setTextColor(0xFF64748b);
            card.addView(line3);

            scanResults.addView(card);
        }
    }

    private String signalBars(int level) {
        if (level >= -50) return "▂▄▆█";
        if (level >= -60) return "▂▄▆";
        if (level >= -70) return "▂▄";
        return "▂";
    }

    private String signalWord(int level) {
        if (level >= -50) return "খুব ভালো";
        if (level >= -60) return "ভালো";
        if (level >= -70) return "মাঝারি";
        if (level >= -80) return "দুর্বল";
        return "খুব দুর্বল";
    }

    private int signalPercent(int level) {
        if (level >= -50) return 100;
        if (level <= -90) return 5;
        return (int) ((level + 90) * 100.0 / 40.0);
    }

    private String bandName(int freqMHz) {
        if (freqMHz >= 2400 && freqMHz < 2500) return "2.4 GHz";
        if (freqMHz >= 5000 && freqMHz < 6000) return "5 GHz";
        if (freqMHz >= 6000) return "6 GHz";
        return freqMHz + " MHz";
    }

    private int freqToChannel(int freqMHz) {
        if (freqMHz >= 2412 && freqMHz <= 2472) return (freqMHz - 2412) / 5 + 1;
        if (freqMHz == 2484) return 14;
        if (freqMHz >= 5180 && freqMHz <= 5240) return (freqMHz - 5180) / 5 + 36;
        if (freqMHz >= 5260 && freqMHz <= 5320) return (freqMHz - 5260) / 5 + 52;
        if (freqMHz >= 5500 && freqMHz <= 5700) return (freqMHz - 5500) / 5 + 100;
        if (freqMHz >= 5745 && freqMHz <= 5825) return (freqMHz - 5745) / 5 + 149;
        return 0;
    }

    private String securityName(String caps) {
        if (caps == null) return "অজানা";
        if (caps.contains("WPA3")) return "WPA3";
        if (caps.contains("WPA2")) return "WPA2";
        if (caps.contains("WPA")) return "WPA";
        if (caps.contains("WEP")) return "WEP";
        return "খোলা (পাসওয়ার্ড লাগে না)";
    }

    // ---------- Channels ----------

    private void showChannels() {
        channelList.removeAllViews();
        if (lastResults.isEmpty()) {
            channelAdvice.setText("প্রথমে স্ক্যান ট্যাবে গিয়ে স্ক্যান করো, তারপর এখানে চ্যানেলের ভিড় দেখাবে।");
            return;
        }
        Map<Integer, Integer> count24 = new HashMap<>();
        Map<Integer, Integer> count5 = new HashMap<>();
        for (ScanResult r : lastResults) {
            int ch = freqToChannel(r.frequency);
            if (ch == 0) continue;
            if (r.frequency < 2500) {
                count24.put(ch, count24.getOrDefault(ch, 0) + 1);
            } else {
                count5.put(ch, count5.getOrDefault(ch, 0) + 1);
            }
        }
        int best24 = bestChannel(count24, new int[]{1, 6, 11});
        int best5 = bestChannel(count5, new int[]{36, 40, 44, 48, 149, 153, 157, 161, 165});
        channelAdvice.setText("✅ সবচেয়ে ফাঁকা চ্যানেল: 2.4 GHz-এ " + best24 + ", 5 GHz-এ " + best5
                + "। রাউটার এই চ্যানেলে বসালে নেট ভালো চলবে।");

        addChannelHeader("2.4 GHz চ্যানেল (ভিড় বেশি হলে সিগন্যাল খারাপ হয়)");
        int max24 = 1;
        for (int v : count24.values()) max24 = Math.max(max24, v);
        for (int ch = 1; ch <= 13; ch++) {
            int c = count24.getOrDefault(ch, 0);
            addChannelRow(ch, c, max24);
        }
        addChannelHeader("5 GHz চ্যানেল");
        int max5 = 1;
        for (int v : count5.values()) max5 = Math.max(max5, v);
        int[] chans5 = {36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165};
        for (int ch : chans5) {
            int c = count5.getOrDefault(ch, 0);
            if (c > 0) addChannelRow(ch, c, max5);
        }
        if (count5.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("আশেপাশে কোনো 5 GHz WiFi পাওয়া যায়নি।");
            t.setTextColor(0xFF64748b);
            channelList.addView(t);
        }
    }

    private int bestChannel(Map<Integer, Integer> counts, int[] candidates) {
        int best = candidates[0];
        int bestCount = Integer.MAX_VALUE;
        for (int ch : candidates) {
            int c = counts.getOrDefault(ch, 0);
            if (c < bestCount) { bestCount = c; best = ch; }
        }
        return best;
    }

    private void addChannelHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextColor(0xFF0f172a);
        t.setPadding(0, 16, 0, 6);
        channelList.addView(t);
    }

    private void addChannelRow(int channel, int count, int max) {
        TextView t = new TextView(this);
        StringBuilder bar = new StringBuilder();
        int n = max > 0 ? (int) Math.round(count * 12.0 / max) : 0;
        for (int i = 0; i < n; i++) bar.append("█");
        t.setText("চ্যানেল " + channel + ": " + bar + " " + count + "টি");
        t.setTextSize(14);
        t.setTextColor(count == 0 ? 0xFF64748b : 0xFF0f172a);
        t.setPadding(0, 2, 0, 2);
        channelList.addView(t);
    }

    // ---------- My WiFi ----------

    private void showMyWifi() {
        myWifiInfo.removeAllViews();
        WifiInfo info = null;
        try { info = wifiManager.getConnectionInfo(); } catch (Exception ignored) {}
        if (info == null || info.getNetworkId() == -1) {
            myWifiSignalLabel.setText("সিগন্যাল: কোনো WiFi-তে যুক্ত নেই");
            myWifiSignalBar.setProgress(0);
            addInfoRow("অবস্থা", "কোনো WiFi-তে যুক্ত নেই — ফোনের WiFi চালু করে কোনো নেটওয়ার্কে যুক্ত হও।");
            return;
        }
        String ssid = info.getSSID() != null ? info.getSSID().replace("\"", "") : "অজানা";
        int rssi = info.getRssi();
        myWifiSignalLabel.setText("সিগন্যাল: " + rssi + " dBm (" + signalWord(rssi) + ") " + signalBars(rssi));
        myWifiSignalBar.setProgress(signalPercent(rssi));
        addInfoRow("WiFi-র নাম", ssid);
        addInfoRow("রাউটার (BSSID)", info.getBSSID() != null ? info.getBSSID() : "—");
        addInfoRow("গতি (লিংক স্পিড)", info.getLinkSpeed() + " Mbps");
        addInfoRow("ফ্রিকোয়েন্সি", bandName(info.getFrequency()) + " • চ্যানেল " + freqToChannel(info.getFrequency()));
        addInfoRow("ফোনের IP", ipString(info.getIpAddress()));
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        if (dhcp != null) {
            addInfoRow("রাউটারের IP (গেটওয়ে)", ipString(dhcp.gateway));
            addInfoRow("নেটমাস্ক", ipString(dhcp.netmask));
            addInfoRow("DNS ১", ipString(dhcp.dns1));
            addInfoRow("DNS ২", ipString(dhcp.dns2));
            addInfoRow("লিজের মেয়াদ", (dhcp.leaseDuration / 3600) + " ঘণ্টা");
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        addInfoRow("ইন্টারনেট", (ni != null && ni.isConnected()) ? "চালু আছে ✅" : "নেই ❌");
    }

    private void addInfoRow(String label, String value) {
        TextView t = new TextView(this);
        t.setText(label + ": " + value);
        t.setTextSize(14);
        t.setTextColor(0xFF0f172a);
        t.setPadding(0, 4, 0, 4);
        myWifiInfo.addView(t);
    }

    private String ipString(int ip) {
        return Formatter.formatIpAddress(ip);
    }

    private void openRouterPage() {
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        String gw = dhcp != null ? ipString(dhcp.gateway) : "";
        if (gw.isEmpty() || gw.equals("0.0.0.0")) {
            Toast.makeText(this, "রাউটারের IP পাওয়া যায়নি — প্রথমে WiFi-তে যুক্ত হও।", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://" + gw)));
        } catch (Exception e) {
            Toast.makeText(this, "ব্রাউজার খোলা যায়নি।", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Speed test ----------

    private void runSpeedTest() {
        if (speedRunning) return;
        speedRunning = true;
        speedProgress.setVisibility(View.VISIBLE);
        speedProgress.setProgress(0);
        speedResult.setText("মাপা হচ্ছে… প্রথমে পিং, তারপর ডাউনলোড, তারপর আপলোড। একটু অপেক্ষা করো।");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                long pingMs = measurePing("8.8.8.8", 53, 2000);
                out.append("পিং: ").append(pingMs >= 0 ? pingMs + " ms" : "মাপা যায়নি").append("\n");
                runOnUiThread(() -> speedProgress.setProgress(25));

                double down = measureDownload();
                out.append("ডাউনলোড: ").append(down > 0 ? String.format(Locale.US, "%.1f Mbps", down) : "মাপা যায়নি").append("\n");
                runOnUiThread(() -> speedProgress.setProgress(65));

                double up = measureUpload();
                out.append("আপলোড: ").append(up > 0 ? String.format(Locale.US, "%.1f Mbps", up) : "মাপা যায়নি").append("\n");
                out.append("\nনোট: স্পিড সার্ভার আর লাইনের উপর নির্ভর করে — একই সময়ে দুবার মাপলে একটু আলাদা আসতে পারে।");
                runOnUiThread(() -> speedProgress.setProgress(100));
            } catch (Exception e) {
                out.append("স্পিড মাপা যায়নি: ইন্টারনেট আছে কি না দেখো।");
            }
            String result = out.toString();
            runOnUiThread(() -> {
                speedResult.setText(result);
                speedProgress.setVisibility(View.GONE);
                speedRunning = false;
            });
        }).start();
    }

    private long measurePing(String host, int port, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            return -1;
        }
    }

    private double measureDownload() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://speed.cloudflare.com/__down?bytes=20000000");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.connect();
            if (conn.getResponseCode() != 200) return -1;
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[32 * 1024];
            long total = 0;
            long t0 = System.currentTimeMillis();
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (System.currentTimeMillis() - t0 > 15000) break;
            }
            long elapsedMs = System.currentTimeMillis() - t0;
            in.close();
            if (elapsedMs <= 0 || total <= 0) return -1;
            return total * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private double measureUpload() {
        HttpURLConnection conn = null;
        try {
            byte[] data = new byte[1024 * 1024];
            for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
            URL url = new URL("https://speed.cloudflare.com/__up");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setFixedLengthStreamingMode(data.length);
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            long t0 = System.currentTimeMillis();
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            long elapsedMs = System.currentTimeMillis() - t0;
            if (code != 200 || elapsedMs <= 0) return -1;
            return data.length * 8.0 / (elapsedMs / 1000.0) / 1_000_000.0;
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- Connected devices ----------

    private void runDeviceScan() {
        if (devicesRunning) return;
        devicesRunning = true;
        devicesList.removeAllViews();
        WifiInfo info = null;
        try { info = wifiManager.getConnectionInfo(); } catch (Exception ignored) {}
        if (info == null || info.getNetworkId() == -1) {
            devicesStatus.setText("প্রথমে কোনো WiFi-তে যুক্ত হও, তারপর ডিভাইস খুঁজো।");
            devicesRunning = false;
            return;
        }
        int ip = info.getIpAddress();
        String prefix = (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + ".";
        String myIp = ipString(ip);
        DhcpInfo dhcp = wifiManager.getDhcpInfo();
        String gateway = dhcp != null ? ipString(dhcp.gateway) : "";
        devicesStatus.setText("খোঁজা হচ্ছে… " + prefix + "1 থেকে " + prefix + "254");
        ExecutorService pool = Executors.newFixedThreadPool(40);
        AtomicInteger done = new AtomicInteger(0);
        List<String> found = Collections.synchronizedList(new ArrayList<>());
        for (int i = 1; i <= 254; i++) {
            final String host = prefix + i;
            pool.execute(() -> {
                try {
                    InetAddress addr = InetAddress.getByName(host);
                    boolean reachable = addr.isReachable(300);
                    if (!reachable) {
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress(host, 80), 250);
                            reachable = true;
                        } catch (Exception ignored) {}
                    }
                    if (reachable) found.add(host);
                } catch (Exception ignored) {
                } finally {
                    int d = done.incrementAndGet();
                    if (d % 50 == 0) {
                        runOnUiThread(() -> devicesStatus.setText("খোঁজা হচ্ছে… " + d + "/254"));
                    }
                }
            });
        }
        pool.shutdown();
        new Thread(() -> {
            try {
                while (!pool.isTerminated()) Thread.sleep(300);
            } catch (InterruptedException ignored) {}
            Map<String, String> arp = readArpTable();
            for (String host : new ArrayList<>(found)) {
                if (!arp.containsKey(host)) arp.put(host, "—");
            }
            List<String> hosts = new ArrayList<>(arp.keySet());
            Collections.sort(hosts, (a, b) -> {
                int ai = Integer.parseInt(a.substring(a.lastIndexOf('.') + 1));
                int bi = Integer.parseInt(b.substring(b.lastIndexOf('.') + 1));
                return Integer.compare(ai, bi);
            });
            runOnUiThread(() -> {
                devicesStatus.setText(hosts.size() + "টি ডিভাইস পাওয়া গেছে (তোমার ফোনসহ)।");
                devicesList.removeAllViews();
                for (String host : hosts) {
                    String tag = "";
                    if (host.equals(myIp)) tag = " ← তোমার ফোন";
                    else if (host.equals(gateway)) tag = " ← রাউটার";
                    TextView t = new TextView(MainActivity.this);
                    t.setText("📱 " + host + "   MAC: " + arp.get(host) + tag);
                    t.setTextSize(14);
                    t.setTextColor(0xFF0f172a);
                    t.setPadding(0, 4, 0, 4);
                    devicesList.addView(t);
                }
                if (hosts.isEmpty()) {
                    TextView t = new TextView(MainActivity.this);
                    t.setText("কোনো ডিভাইস পাওয়া যায়নি।");
                    t.setTextColor(0xFF64748b);
                    devicesList.addView(t);
                }
                devicesRunning = false;
            });
        }).start();
    }

    private Map<String, String> readArpTable() {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            br.readLine();
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4) {
                    String mac = parts[3];
                    if (!mac.equals("00:00:00:00:00:00")) {
                        map.put(parts[0], mac);
                    }
                }
            }
        } catch (Exception ignored) {}
        return map;
    }
}
