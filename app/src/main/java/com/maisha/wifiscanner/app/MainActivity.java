package com.maisha.wifiscanner.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.TrafficStats;
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
import android.widget.EditText;
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
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

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
    private TextView phoneCountText, otherCountText, devicesUpdatedText;
    private TextView newDeviceBanner, mapNow, mapSummary, speedDiarySummary;
    private TextView securityResult, dataUsageResult, topAppsResult;
    private Button btnSaveKnown;
    private EditText roomNameInput;
    private boolean devicesScannedOnce = false;
    private LinearLayout scanResults, myWifiInfo, channelList, devicesList;
    private LinearLayout sectionScan, sectionMyWifi, sectionChannel, sectionSpeed, sectionDevices, sectionQr, sectionMap;
    private LinearLayout mapList, speedDiaryList, speedChartBox;
    private ProgressBar myWifiSignalBar, speedProgress;
    private SharedPreferences prefs;
    private SpeedChartView speedChartView;
    private Set<String> lastSeenMacs = new HashSet<>();
    private String securityExtra = "";
    private long lastSecurityCheckMs = 0;
    private long lastDataCheckMs = 0;

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
            if (sectionMap.getVisibility() == View.VISIBLE) {
                showSignalMap();
            }
            handler.postDelayed(this, 3000);
        }
    };

    // Live device count: re-scan every 30 seconds while the Devices tab is open.
    private final Runnable liveDevicesUpdater = new Runnable() {
        @Override
        public void run() {
            if (sectionDevices.getVisibility() == View.VISIBLE && !devicesRunning) {
                runDeviceScan();
            }
            handler.postDelayed(this, 30000);
        }
    };

    // MAC address prefixes (first 3 bytes) of well-known phone makers.
    // Identification is approximate: phones with a randomised/private MAC
    // may show up under "other devices" instead.
    private static final java.util.Set<String> PHONE_OUI = new java.util.HashSet<>(java.util.Arrays.asList(
            // Samsung
            "00:12:FB", "00:16:6B", "00:1B:2A", "00:21:19", "00:23:39", "04:FE:31",
            "34:BE:00", "5C:51:88", "60:6B:BD", "70:F9:27", "78:59:2E", "84:25:DB",
            "88:30:EA", "8C:77:12", "9C:02:98", "A8:9C:ED", "AC:5F:3E", "C0:BD:D1",
            "D0:22:BE", "DC:66:CF", "E4:7D:BD", "F0:08:F6",
            // Apple (iPhone)
            "00:03:93", "00:0A:27", "00:0D:93", "00:14:51", "00:17:F2", "00:1B:63",
            "00:1C:B3", "00:1D:4F", "00:1E:C2", "00:1F:5B", "00:21:E9", "00:23:12",
            "00:23:32", "00:23:6C", "00:23:DF", "00:24:36", "00:25:00", "00:26:08",
            "00:26:BB", "04:0C:CE", "04:15:52", "04:26:65", "04:54:53", "08:66:98",
            "0C:30:21", "10:41:7F", "10:9A:DD", "14:10:9F", "18:20:32", "1C:1A:C0",
            "28:37:8A", "34:15:9E", "38:0F:4A", "3C:06:30", "40:33:1A", "44:2A:60",
            "48:43:7C", "4C:57:CA", "50:32:75", "54:26:96", "58:55:CA", "5C:95:AE",
            "60:03:08", "64:20:0C", "68:09:27", "6C:19:8F", "70:11:24", "74:40:BE",
            "78:31:C1", "7C:04:D0", "80:00:6D", "84:08:E2", "88:1E:59", "8C:00:6D",
            "90:03:B7", "94:27:90", "98:01:A7", "9C:04:EB", "A0:18:28", "A4:5E:60",
            "A8:1D:16", "AC:1F:0F", "B0:34:95", "B4:18:D1", "B8:09:8A", "BC:4B:2B",
            "C0:1A:DA", "C4:13:85", "C8:02:10", "CC:08:8D", "D0:03:DF", "D4:1D:6F",
            "D8:02:9B", "DC:07:FA", "E0:0A:F6", "E4:12:89", "E8:04:D8", "EC:08:8B",
            "F0:18:98", "F4:0F:24", "F8:0D:60", "FC:01:9E",
            // Xiaomi / Redmi / POCO
            "00:9E:C8", "0C:1D:AF", "14:F6:5A", "18:59:36", "20:47:DA", "28:6C:07",
            "34:80:B3", "38:A4:ED", "3C:BD:D8", "40:31:3C", "48:46:FB", "50:64:2B",
            "58:44:98", "64:09:80", "64:CC:2E", "68:DF:DD", "74:23:44", "78:02:F8",
            "7C:1D:D9", "80:AD:16", "84:38:38", "88:9F:6F", "98:FA:E3", "A0:86:C6",
            "A4:50:46", "AC:C1:EE", "B0:E2:35", "C4:0B:CB", "D4:97:0B", "DC:1D:9F",
            "E0:DC:FF", "F0:2F:74", "F4:09:D8", "F8:A4:5F",
            // Huawei / Honor
            "00:18:82", "00:1E:10", "00:22:A1", "00:25:9E", "00:46:4B", "04:02:1F",
            "04:47:2D", "04:56:E5", "04:BD:88", "08:63:61", "0C:37:DC", "10:1F:74",
            "10:C6:1F", "10:D7:B5", "18:1E:78", "18:47:2D", "20:2B:20", "24:09:95",
            "24:47:0E", "28:31:52", "2C:33:7A", "30:55:ED", "34:12:F9", "34:6A:17",
            "38:3B:26", "3C:71:BF", "40:02:48", "40:4D:7F", "44:23:AA", "48:1A:84",
            "48:27:EA", "4C:09:B4", "4C:11:BF", "50:06:14", "50:2F:9B", "54:29:0F",
            "54:35:30", "58:02:03", "58:10:8C", "5C:02:2C", "5C:09:4D", "60:08:3A",
            "60:12:5B", "64:0D:7E", "64:12:69", "68:05:71", "68:10:51", "6C:15:60",
            "6C:1E:1F", "70:18:87", "70:24:CB", "74:04:2B", "74:06:6D",
            // Oppo / Vivo / Realme / OnePlus / Infinix / Tecno / Nokia / Motorola / Pixel / Sony
            "00:1F:8E", "08:57:00", "0C:5A:19", "1C:5A:0B", "20:82:C0", "28:3A:4D",
            "30:74:96", "34:6B:46", "34:6F:92", "34:7E:39", "34:96:72", "34:A8:4E",
            "34:C3:D2", "34:CE:94", "38:6E:A0", "38:7F:2B", "38:BC:1A", "38:F8:CA",
            "3C:8C:93", "3C:BD:3E", "3C:CB:7C", "3C:CD:93", "40:5D:82", "40:74:E0",
            "40:84:76", "40:89:67", "40:8E:2E", "40:92:3A", "40:9F:38", "40:B9:8C",
            "40:CB:30", "40:E3:D6", "40:F2:01", "44:33:4C", "44:4F:8A", "44:5A:B7",
            "44:85:00", "44:97:5B", "44:A6:E5", "44:C6:04", "44:C9:A2", "44:CC:16",
            "44:D1:FA", "44:E1:37", "44:F4:9C", "48:1D:42", "48:1F:6E", "48:3B:38",
            "48:51:CA", "48:55:CC", "48:72:92", "48:7A:55", "48:8A:6A", "48:9E:BD",
            "48:A6:B8", "48:AD:08", "48:B2:F1", "48:C5:8A", "48:CC:3C", "48:D8:45",
            "48:DB:50", "48:E7:DA", "48:F7:F1", "48:FB:65", "4C:1F:CC", "4C:48:DA",
            "4C:49:96", "4C:54:99", "4C:60:D0", "4C:7F:62", "4C:8B:EF", "4C:B1:67",
            "4C:C0:81", "4C:CC:6A", "4C:D0:8A", "4C:E1:75", "50:0B:91", "50:3E:AA",
            "50:4B:ED", "50:5B:C2", "50:5F:65", "50:76:91", "50:78:B3", "50:7E:5D",
            "50:8A:CB", "50:96:3D", "50:9A:4C", "50:A6:7F", "50:A7:2B", "50:C7:BF",
            "50:D2:F5", "50:D8:19", "50:E0:EF", "50:FA:AB", "54:2D:52", "54:39:DF",
            "54:4A:16", "54:64:D9", "54:75:D0", "54:89:98", "54:8F:55", "54:A3:FA",
            "54:A5:1B", "54:B8:0A", "54:BE:F7", "54:C8:0F", "54:E3:19", "58:03:1C",
            "58:1C:1B", "58:2A:33", "58:32:8A", "58:3C:3E", "58:4B:F0", "58:53:10",
            "58:7F:57", "58:82:A8", "58:90:77", "58:9B:3D", "58:9D:24", "58:A2:C2",
            "58:B0:3E", "58:C3:8B", "58:CE:2A", "58:D0:71", "58:D6:9E", "58:DF:8B",
            "58:E8:76", "58:EC:23", "58:F0:84", "5C:09:DD", "5C:11:93", "5C:16:A6",
            "5C:19:2B", "5C:26:0A", "5C:33:8E", "5C:3C:27", "5C:3C:6A", "5C:3D:5A",
            "5C:4C:A9", "5C:5A:2B", "5C:66:CF", "5C:6D:20", "5C:7D:5E", "5C:86:2E",
            "5C:93:A2", "5C:A6:E6", "5C:B3:95", "5C:B4:3E", "5C:B8:C1", "5C:BD:5A",
            "5C:C3:07", "5C:C8:80", "5C:D1:35", "5C:D2:0E", "5C:E0:CA", "5C:EB:68",
            "5C:EA:1D", "5C:EF:68", "60:21:C0", "60:33:4B", "60:38:E0", "60:4B:3A",
            "60:4E:56", "60:6E:41", "60:71:8C", "60:89:3C", "60:8C:2B", "60:8D:17",
            "60:90:8C", "60:A4:BD", "60:B7:DA", "60:B9:E2", "60:C3:97", "60:C8:5A",
            "60:CC:4A", "60:D1:1A", "60:D2:3C", "60:D9:A0", "60:DE:44", "60:E0:6C",
            "60:E1:66", "60:E7:9E", "60:EB:69", "60:F1:89", "60:F4:2A", "60:F8:1D",
            "64:13:6C", "64:16:8D", "64:1C:2E", "64:2D:8F", "64:3A:EA", "64:3E:8C",
            "64:42:14", "64:49:7D", "64:4C:36", "64:6C:B2", "64:70:33", "64:76:25",
            "64:87:88", "64:8E:14", "64:A0:E7", "64:A2:F9", "64:A7:DD", "64:BC:0C",
            "64:C6:67", "64:CF:2D", "64:D1:A3", "64:DB:18", "64:E8:81", "64:F6:9D"
    ));

    private boolean isPhone(String mac, boolean isMyPhone) {
        if (isMyPhone) return true;
        if (mac == null || mac.length() < 8) return false;
        String prefix = mac.substring(0, 8).toUpperCase(Locale.US);
        return PHONE_OUI.contains(prefix);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        prefs = getSharedPreferences("wifi_scanner", MODE_PRIVATE);

        warningText = findViewById(R.id.warningText);
        scanStatus = findViewById(R.id.scanStatus);
        myWifiSignalLabel = findViewById(R.id.myWifiSignalLabel);
        channelAdvice = findViewById(R.id.channelAdvice);
        speedResult = findViewById(R.id.speedResult);
        devicesStatus = findViewById(R.id.devicesStatus);
        phoneCountText = findViewById(R.id.phoneCountText);
        otherCountText = findViewById(R.id.otherCountText);
        devicesUpdatedText = findViewById(R.id.devicesUpdatedText);
        newDeviceBanner = findViewById(R.id.newDeviceBanner);
        btnSaveKnown = findViewById(R.id.btnSaveKnown);
        mapNow = findViewById(R.id.mapNow);
        mapSummary = findViewById(R.id.mapSummary);
        mapList = findViewById(R.id.mapList);
        roomNameInput = findViewById(R.id.roomName);
        speedDiarySummary = findViewById(R.id.speedDiarySummary);
        speedDiaryList = findViewById(R.id.speedDiaryList);
        speedChartBox = findViewById(R.id.speedChartBox);
        securityResult = findViewById(R.id.securityResult);
        dataUsageResult = findViewById(R.id.dataUsageResult);
        topAppsResult = findViewById(R.id.topAppsResult);
        scanResults = findViewById(R.id.scanResults);
        myWifiInfo = findViewById(R.id.myWifiInfo);
        channelList = findViewById(R.id.channelList);
        devicesList = findViewById(R.id.devicesList);
        sectionScan = findViewById(R.id.sectionScan);
        sectionMyWifi = findViewById(R.id.sectionMyWifi);
        sectionChannel = findViewById(R.id.sectionChannel);
        sectionSpeed = findViewById(R.id.sectionSpeed);
        sectionDevices = findViewById(R.id.sectionDevices);
        sectionQr = findViewById(R.id.sectionQr);
        sectionMap = findViewById(R.id.sectionMap);
        myWifiSignalBar = findViewById(R.id.myWifiSignalBar);
        speedProgress = findViewById(R.id.speedProgress);
        speedChartView = new SpeedChartView(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (220 * getResources().getDisplayMetrics().density));
        speedChartView.setLayoutParams(chartLp);
        speedChartBox.addView(speedChartView);

        findViewById(R.id.tabScan).setOnClickListener(v -> showSection(sectionScan));
        findViewById(R.id.tabMyWifi).setOnClickListener(v -> { showSection(sectionMyWifi); showMyWifi(); });
        findViewById(R.id.tabChannel).setOnClickListener(v -> { showSection(sectionChannel); showChannels(); });
        findViewById(R.id.tabSpeed).setOnClickListener(v -> { showSection(sectionSpeed); showSpeedDiary(); });
        findViewById(R.id.tabDevices).setOnClickListener(v -> {
            showSection(sectionDevices);
            if (!devicesScannedOnce) runDeviceScan();
        });
        findViewById(R.id.tabQr).setOnClickListener(v -> showSection(sectionQr));
        findViewById(R.id.tabMap).setOnClickListener(v -> { showSection(sectionMap); showSignalMap(); });
        findViewById(R.id.btnMeasureRoom).setOnClickListener(v -> measureRoom());
        findViewById(R.id.btnClearMap).setOnClickListener(v -> {
            prefs.edit().remove("signal_map").apply();
            showSignalMap();
            Toast.makeText(this, "ম্যাপ মুছে ফেলা হয়েছে।", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btnClearDiary).setOnClickListener(v -> {
            prefs.edit().remove("speed_diary").apply();
            showSpeedDiary();
            Toast.makeText(this, "ডায়েরি মুছে ফেলা হয়েছে।", Toast.LENGTH_SHORT).show();
        });
        btnSaveKnown.setOnClickListener(v -> saveKnownMacs());
        findViewById(R.id.btnOpenScan).setOnClickListener(v -> openQr("scan"));
        findViewById(R.id.btnOpenGen).setOnClickListener(v -> openQr("generate"));

        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnRefreshMyWifi).setOnClickListener(v -> showMyWifi());
        findViewById(R.id.btnSpeed).setOnClickListener(v -> runSpeedTest());
        findViewById(R.id.btnDevices).setOnClickListener(v -> runDeviceScan());
        findViewById(R.id.btnWifiSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        findViewById(R.id.btnRouterPage).setOnClickListener(v -> openRouterPage());

        checkPermissions();
        handler.post(signalUpdater);
        handler.post(liveDevicesUpdater);
        CrashLog.showIfExists(this);
    }

    private void showSection(LinearLayout section) {
        sectionScan.setVisibility(View.GONE);
        sectionMyWifi.setVisibility(View.GONE);
        sectionChannel.setVisibility(View.GONE);
        sectionSpeed.setVisibility(View.GONE);
        sectionDevices.setVisibility(View.GONE);
        sectionQr.setVisibility(View.GONE);
        sectionMap.setVisibility(View.GONE);
        section.setVisibility(View.VISIBLE);
    }

    private void openQr(String mode) {
        Intent intent = new Intent(this, QrActivity.class);
        intent.putExtra(QrActivity.EXTRA_MODE, mode);
        startActivity(intent);
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
        handler.removeCallbacks(liveDevicesUpdater);
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
            securityResult.setText("কোনো WiFi-তে যুক্ত নেই।");
            dataUsageResult.setText("WiFi-তে যুক্ত হলে ডেটা হিসাব দেখাবে।");
            topAppsResult.setText("");
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
        showSecurityCheck(ssid);
        showDataUsage();
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
            final double[] vals = new double[]{-1, -1};
            final long[] pingVal = new long[]{-1};
            try {
                long pingMs = measurePing("8.8.8.8", 53, 2000);
                pingVal[0] = pingMs;
                out.append("পিং: ").append(pingMs >= 0 ? pingMs + " ms" : "মাপা যায়নি").append("\n");
                runOnUiThread(() -> speedProgress.setProgress(25));

                double down = measureDownload();
                vals[0] = down;
                out.append("ডাউনলোড: ").append(down > 0 ? String.format(Locale.US, "%.1f Mbps", down) : "মাপা যায়নি").append("\n");
                runOnUiThread(() -> speedProgress.setProgress(65));

                double up = measureUpload();
                vals[1] = up;
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
                if (vals[0] > 0) {
                    saveSpeedResult(vals[0], vals[1], pingVal[0]);
                    showSpeedDiary();
                }
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

    // ---------- Signal map ----------

    private static class MapEntry {
        String room;
        int dbm;
        long time;

        MapEntry(String r, int d, long t) {
            room = r;
            dbm = d;
            time = t;
        }
    }

    private List<MapEntry> loadMapEntries() {
        List<MapEntry> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("signal_map", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new MapEntry(o.optString("room", "ঘর"), o.optInt("dbm", -80), o.optLong("time", 0)));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void saveMapEntries(List<MapEntry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (MapEntry e : list) {
                JSONObject o = new JSONObject();
                o.put("room", e.room);
                o.put("dbm", e.dbm);
                o.put("time", e.time);
                arr.put(o);
            }
            prefs.edit().putString("signal_map", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void measureRoom() {
        WifiInfo info = null;
        try {
            info = wifiManager.getConnectionInfo();
        } catch (Exception ignored) {}
        if (info == null || info.getNetworkId() == -1) {
            Toast.makeText(this, "প্রথমে কোনো WiFi-তে যুক্ত হও, তারপর মাপো।", Toast.LENGTH_LONG).show();
            return;
        }
        String room = roomNameInput.getText().toString().trim();
        if (room.isEmpty()) {
            room = "ঘর " + (loadMapEntries().size() + 1);
        }
        int rssi = info.getRssi();
        List<MapEntry> list = loadMapEntries();
        boolean replaced = false;
        for (MapEntry e : list) {
            if (e.room.equalsIgnoreCase(room)) {
                e.dbm = rssi;
                e.time = System.currentTimeMillis();
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(new MapEntry(room, rssi, System.currentTimeMillis()));
            if (list.size() > 20) list.remove(0);
        }
        saveMapEntries(list);
        roomNameInput.setText("");
        showSignalMap();
        Toast.makeText(this, room + ": " + rssi + " dBm (" + signalWord(rssi) + ")", Toast.LENGTH_SHORT).show();
    }

    private void showSignalMap() {
        WifiInfo info = null;
        try {
            info = wifiManager.getConnectionInfo();
        } catch (Exception ignored) {}
        if (info != null && info.getNetworkId() != -1) {
            String ssid = info.getSSID() != null ? info.getSSID().replace("\"", "") : "";
            mapNow.setText("এখন: " + ssid + " — " + info.getRssi() + " dBm (" + signalWord(info.getRssi()) + ")");
        } else {
            mapNow.setText("এখন: কোনো WiFi-তে যুক্ত নেই");
        }
        List<MapEntry> list = loadMapEntries();
        mapList.removeAllViews();
        if (list.isEmpty()) {
            mapSummary.setText("এখনো কোনো ঘর মাপা হয়নি। ঘরের নাম লিখে 'এই ঘরে সিগন্যাল মাপো' চাপো।");
            return;
        }
        Collections.sort(list, (a, b) -> Integer.compare(b.dbm, a.dbm));
        MapEntry best = list.get(0);
        MapEntry worst = list.get(list.size() - 1);
        mapSummary.setText("✅ সবচেয়ে ভালো: " + best.room + " (" + best.dbm + " dBm)\n"
                + "🔻 সবচেয়ে দুর্বল: " + worst.room + " (" + worst.dbm + " dBm)\n"
                + "রাউটার ভালো ঘরের কাছাকাছি আছে — দুর্বল ঘরে নেট ধীর চলবে।");
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        for (MapEntry e : list) {
            int n = Math.max(1, signalPercent(e.dbm) / 10);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < n; i++) bar.append("█");
            TextView t = new TextView(this);
            t.setText(e.room + ": " + bar + " " + e.dbm + " dBm (" + signalWord(e.dbm) + ") • " + tf.format(new Date(e.time)));
            t.setTextSize(14);
            t.setTextColor(0xFF0f172a);
            t.setPadding(0, 4, 0, 4);
            mapList.addView(t);
        }
    }

    // ---------- Speed diary ----------

    private void saveSpeedResult(double down, double up, long pingMs) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("speed_diary", "[]"));
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("d", down);
            o.put("u", up);
            o.put("p", pingMs);
            arr.put(o);
            while (arr.length() > 50) arr.remove(0);
            prefs.edit().putString("speed_diary", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void showSpeedDiary() {
        List<double[]> rows = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("speed_diary", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                rows.add(new double[]{o.optLong("t", 0), o.optDouble("d", -1), o.optDouble("u", -1), o.optLong("p", -1)});
            }
        } catch (Exception ignored) {}
        List<Float> downs = new ArrayList<>();
        for (int i = Math.max(0, rows.size() - 10); i < rows.size(); i++) {
            if (rows.get(i)[1] > 0) downs.add((float) rows.get(i)[1]);
        }
        speedChartView.setData(downs);
        speedDiaryList.removeAllViews();
        if (rows.isEmpty()) {
            speedDiarySummary.setText("এখনো কোনো গতি মাপা হয়নি — উপরে স্পিড মাপলেই এখানে জমা হবে।");
            return;
        }
        double best = 0, sum = 0;
        int n = 0;
        double[] bucketSum = new double[4];
        int[] bucketN = new int[4];
        for (double[] r : rows) {
            if (r[1] <= 0) continue;
            best = Math.max(best, r[1]);
            sum += r[1];
            n++;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis((long) r[0]);
            int h = c.get(Calendar.HOUR_OF_DAY);
            int b = h < 6 ? 3 : h < 12 ? 0 : h < 17 ? 1 : h < 22 ? 2 : 3;
            bucketSum[b] += r[1];
            bucketN[b]++;
        }
        String[] bucketName = {"সকালে", "দুপুরে", "সন্ধ্যায়", "রাতে"};
        StringBuilder sb = new StringBuilder();
        sb.append("মোট ").append(n).append(" বার মাপা হয়েছে • সেরা ডাউনলোড ")
                .append(String.format(Locale.US, "%.1f Mbps", best))
                .append(" • গড় ").append(String.format(Locale.US, "%.1f Mbps", n > 0 ? sum / n : 0));
        for (int b = 0; b < 4; b++) {
            if (bucketN[b] > 0) {
                sb.append("\n").append(bucketName[b]).append(" গড়: ")
                        .append(String.format(Locale.US, "%.1f Mbps", bucketSum[b] / bucketN[b]))
                        .append(" (").append(bucketN[b]).append(" বার)");
            }
        }
        speedDiarySummary.setText(sb.toString());
        SimpleDateFormat df = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault());
        for (int i = rows.size() - 1; i >= Math.max(0, rows.size() - 5); i--) {
            double[] r = rows.get(i);
            TextView t = new TextView(this);
            t.setText(df.format(new Date((long) r[0])) + " — ডাউন " + String.format(Locale.US, "%.1f Mbps", r[1])
                    + " • আপ " + String.format(Locale.US, "%.1f Mbps", r[2])
                    + " • পিং " + (r[3] >= 0 ? (long) r[3] + " ms" : "—"));
            t.setTextSize(13);
            t.setTextColor(0xFF334155);
            t.setPadding(0, 3, 0, 3);
            speedDiaryList.addView(t);
        }
    }

    // ---------- Security check & data usage ----------

    private void showSecurityCheck(String ssid) {
        String caps = null;
        for (ScanResult r : lastResults) {
            if (r.SSID != null && r.SSID.equals(ssid)) {
                caps = r.capabilities;
                break;
            }
        }
        String rating;
        if (caps == null) {
            rating = "⚠️ নিরাপত্তা জানা যায়নি — প্রথমে স্ক্যান ট্যাবে গিয়ে স্ক্যান করো।";
        } else if (caps.contains("WPA3")) {
            rating = "🟢 সবচেয়ে ভালো — WPA3 সুরক্ষা আছে।";
        } else if (caps.contains("WPA2")) {
            rating = "🟢 ভালো — WPA2 সুরক্ষা আছে।";
        } else if (caps.contains("WPA")) {
            rating = "🟡 মোটামুটি — শুধু WPA; সম্ভব হলে রাউটারে WPA2/WPA3 করো।";
        } else if (caps.contains("WEP")) {
            rating = "🔴 খুব দুর্বল — WEP সহজে ভাঙা যায়; এক্ষুনি WPA2-তে বদলাও।";
        } else {
            rating = "🔴 খোলা নেটওয়ার্ক — পাসওয়ার্ড লাগে না, যে কেউ ঢুকতে পারে। ব্যাংক/পাসওয়ার্ডের কাজ এখানে করো না।";
        }
        securityResult.setText(rating + (securityExtra.isEmpty() ? "" : "\n" + securityExtra));
        long now = System.currentTimeMillis();
        if (now - lastSecurityCheckMs > 60000) {
            lastSecurityCheckMs = now;
            new Thread(() -> {
                String extra = checkGatewayAndDns();
                runOnUiThread(() -> {
                    securityExtra = extra;
                    securityResult.setText(rating + "\n" + extra);
                });
            }).start();
        }
    }

    private String checkGatewayAndDns() {
        StringBuilder sb = new StringBuilder();
        try {
            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            String gw = dhcp != null ? ipString(dhcp.gateway) : "";
            if (!gw.isEmpty() && !gw.equals("0.0.0.0")) {
                long t0 = System.currentTimeMillis();
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(gw, 80), 800);
                    sb.append("রাউটার সাড়া দিচ্ছে (").append(System.currentTimeMillis() - t0).append(" ms)");
                } catch (Exception e) {
                    sb.append("রাউটার সাড়া দিচ্ছে না");
                }
            }
            try {
                InetAddress.getByName("google.com");
                if (sb.length() > 0) sb.append(" • ");
                sb.append("DNS কাজ করছে");
            } catch (Exception e) {
                if (sb.length() > 0) sb.append(" • ");
                sb.append("DNS কাজ করছে না");
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private void showDataUsage() {
        long totalRx = TrafficStats.getTotalRxBytes();
        long totalTx = TrafficStats.getTotalTxBytes();
        long mobRx = TrafficStats.getMobileRxBytes();
        long mobTx = TrafficStats.getMobileTxBytes();
        long wifiRx = Math.max(0, totalRx - Math.max(0, mobRx));
        long wifiTx = Math.max(0, totalTx - Math.max(0, mobTx));
        dataUsageResult.setText("WiFi দিয়ে: ডাউন " + formatBytes(wifiRx) + " • আপ " + formatBytes(wifiTx)
                + "\nমোবাইল ডেটা দিয়ে: ডাউন " + formatBytes(Math.max(0, mobRx)) + " • আপ " + formatBytes(Math.max(0, mobTx)));
        long now = System.currentTimeMillis();
        if (now - lastDataCheckMs > 60000) {
            lastDataCheckMs = now;
            new Thread(this::loadTopApps).start();
        }
    }

    private void loadTopApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            List<String[]> rows = new ArrayList<>();
            for (ApplicationInfo app : apps) {
                long rx = TrafficStats.getUidRxBytes(app.uid);
                long tx = TrafficStats.getUidTxBytes(app.uid);
                if (rx < 0 && tx < 0) continue;
                long total = Math.max(0, rx) + Math.max(0, tx);
                if (total <= 0) continue;
                String label = String.valueOf(pm.getApplicationLabel(app));
                rows.add(new String[]{label, String.valueOf(total)});
            }
            if (rows.isEmpty()) {
                runOnUiThread(() -> topAppsResult.setText("এই ফোনে অ্যাপভিত্তিক হিসাব দেখানো যায় না।"));
                return;
            }
            Collections.sort(rows, (a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
            StringBuilder sb = new StringBuilder("সবচেয়ে বেশি ডেটা খায়:");
            for (int i = 0; i < Math.min(5, rows.size()); i++) {
                sb.append("\n").append(i + 1).append(". ").append(rows.get(i)[0]).append(" — ")
                        .append(formatBytes(Long.parseLong(rows.get(i)[1])));
            }
            String text = sb.toString();
            runOnUiThread(() -> topAppsResult.setText(text));
        } catch (Exception e) {
            runOnUiThread(() -> topAppsResult.setText("অ্যাপভিত্তিক হিসাব মাপা যায়নি।"));
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private void saveKnownMacs() {
        Set<String> known = prefs.getStringSet("known_macs", null);
        Set<String> updated = known == null ? new HashSet<>() : new HashSet<>(known);
        updated.addAll(lastSeenMacs);
        prefs.edit().putStringSet("known_macs", updated).apply();
        newDeviceBanner.setVisibility(View.GONE);
        btnSaveKnown.setVisibility(View.GONE);
        Toast.makeText(this, "সব ডিভাইস চেনা হিসেবে সেভ হয়েছে।", Toast.LENGTH_SHORT).show();
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
                devicesScannedOnce = true;
                int phoneCount = 0;
                for (String host : hosts) {
                    String mac = arp.get(host);
                    if (isPhone(mac, host.equals(myIp))) phoneCount++;
                }
                int otherCount = hosts.size() - phoneCount;
                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                phoneCountText.setText("📱 ফোন যুক্ত আছে: " + phoneCount + " টি");
                otherCountText.setText("💻 অন্য ডিভাইস: " + otherCount + " টি   •   মোট: " + hosts.size() + " টি");
                devicesUpdatedText.setText("🟢 লাইভ — শেষ আপডেট: " + time + " • প্রতি ৩০ সেকেন্ডে নিজে নিজে আপডেট হয়");
                devicesStatus.setText(hosts.size() + "টি ডিভাইস পাওয়া গেছে (তোমার ফোনসহ)। ফোন চেনা আনুমানিক — MAC দেখে বোঝা যায়, কিছু ফোন অন্য ডিভাইসে দেখাতে পারে। নতুন ডিভাইস শুধু স্ক্যানের সময় ধরা পড়ে — সারাক্ষণ নয়।");
                // New-device alert: compare against MACs seen before.
                Set<String> currentMacs = new HashSet<>();
                for (String host : hosts) {
                    String mac = arp.get(host);
                    if (mac != null && !mac.equals("—")) currentMacs.add(mac.toUpperCase(Locale.US));
                }
                lastSeenMacs = new HashSet<>(currentMacs);
                Set<String> known = prefs.getStringSet("known_macs", null);
                if (known == null) {
                    // First scan ever: remember everything as the baseline.
                    prefs.edit().putStringSet("known_macs", new HashSet<>(currentMacs)).apply();
                    newDeviceBanner.setVisibility(View.GONE);
                    btnSaveKnown.setVisibility(View.GONE);
                } else {
                    Set<String> newMacs = new HashSet<>(currentMacs);
                    newMacs.removeAll(known);
                    if (newMacs.isEmpty()) {
                        newDeviceBanner.setVisibility(View.GONE);
                        btnSaveKnown.setVisibility(View.GONE);
                    } else {
                        newDeviceBanner.setText("🆕 নতুন ডিভাইস পাওয়া গেছে: " + newMacs.size()
                                + " টি — আগে এই WiFi-তে দেখা যায়নি। চেনা হলে নিচের বোতামে সেভ করো।");
                        newDeviceBanner.setVisibility(View.VISIBLE);
                        btnSaveKnown.setVisibility(View.VISIBLE);
                    }
                    devicesList.removeAllViews();
                    for (String host : hosts) {
                        String tag = "";
                        boolean phone = isPhone(arp.get(host), host.equals(myIp));
                        if (host.equals(myIp)) tag = " ← তোমার ফোন";
                        else if (host.equals(gateway)) tag = " ← রাউটার";
                        String mac = arp.get(host);
                        if (mac != null && newMacs.contains(mac.toUpperCase(Locale.US))) tag += " 🆕 নতুন!";
                        TextView t = new TextView(MainActivity.this);
                        t.setText((phone ? "📱 ফোন: " : "🔌 ") + host + "   MAC: " + arp.get(host) + tag);
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
                    return;
                }
                devicesList.removeAllViews();
                for (String host : hosts) {
                    String tag = "";
                    boolean phone = isPhone(arp.get(host), host.equals(myIp));
                    if (host.equals(myIp)) tag = " ← তোমার ফোন";
                    else if (host.equals(gateway)) tag = " ← রাউটার";
                    TextView t = new TextView(MainActivity.this);
                    t.setText((phone ? "📱 ফোন: " : "🔌 ") + host + "   MAC: " + arp.get(host) + tag);
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
