package com.maisha.wifiscanner.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.PlanarYUVLuminanceSource;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class QrActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    private static final int REQ_CAMERA = 201;

    private TextureView previewView;
    private LinearLayout scanBox, genBox, resultBox;
    private TextView scanHint, resultSsid, resultSec, resultPass, resultNote, genNote;
    private ImageView qrImage;
    private EditText etSsid, etPass;
    private Spinner spSec;
    private CheckBox cbHidden;

    private WifiManager wifiManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private MultiFormatReader reader;
    private boolean scanning = false;
    private long lastDecodeMs = 0;
    private String foundSsid, foundPass, foundSec;
    private boolean foundHidden;
    private Bitmap generatedQr;
    private String generatedName = "wifi-qr";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        previewView = findViewById(R.id.previewView);
        scanBox = findViewById(R.id.scanBox);
        genBox = findViewById(R.id.genBox);
        resultBox = findViewById(R.id.resultBox);
        scanHint = findViewById(R.id.scanHint);
        resultSsid = findViewById(R.id.resultSsid);
        resultSec = findViewById(R.id.resultSec);
        resultPass = findViewById(R.id.resultPass);
        resultNote = findViewById(R.id.resultNote);
        genNote = findViewById(R.id.genNote);
        qrImage = findViewById(R.id.qrImage);
        etSsid = findViewById(R.id.etSsid);
        etPass = findViewById(R.id.etPass);
        spSec = findViewById(R.id.spSec);
        cbHidden = findViewById(R.id.cbHidden);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        reader = new MultiFormatReader();
        reader.setHints(hints);

        ArrayAdapter<String> secAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"WPA / WPA2", "WPA3", "WEP", "খোলা (পাসওয়ার্ড নেই)"});
        secAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSec.setAdapter(secAdapter);

        // Prefill the connected WiFi's name (password cannot be read — user types it).
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info != null && info.getSSID() != null) {
                etSsid.setText(info.getSSID().replace("\"", ""));
            }
        } catch (Exception ignored) {}

        findViewById(R.id.btnModeScan).setOnClickListener(v -> showScan());
        findViewById(R.id.btnModeGen).setOnClickListener(v -> showGen());
        findViewById(R.id.btnConnect).setOnClickListener(v -> connectToFoundWifi());
        findViewById(R.id.btnCopyPass).setOnClickListener(v -> copyFoundPassword());
        findViewById(R.id.btnScanAgain).setOnClickListener(v -> {
            resultBox.setVisibility(View.GONE);
            resultNote.setText("");
            scanHint.setText("WiFi-র QR কোডটা ক্যামেরার সামনে ধরো।");
            scanning = true;
        });
        findViewById(R.id.btnGenerate).setOnClickListener(v -> generateQr());
        findViewById(R.id.btnSaveQr).setOnClickListener(v -> saveQr());

        if ("generate".equals(getIntent().getStringExtra(EXTRA_MODE))) {
            showGen();
        } else {
            showScan();
        }
    }

    private void showScan() {
        genBox.setVisibility(View.GONE);
        scanBox.setVisibility(View.VISIBLE);
        resultBox.setVisibility(View.GONE);
        scanning = true;
        scanHint.setText("WiFi-র QR কোডটা ক্যামেরার সামনে ধরো।");
        startCameraIfReady();
    }

    private void showGen() {
        scanning = false;
        stopCamera();
        scanBox.setVisibility(View.GONE);
        genBox.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scanBox.getVisibility() == View.VISIBLE) startCameraIfReady();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        stopBgThread();
    }

    // ---------- Camera ----------

    private void startCameraIfReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) { openCamera(); }
                @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
                @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraIfReady();
            } else {
                scanHint.setText("ক্যামেরার অনুমতি না দিলে QR স্ক্যান করা যাবে না।");
                Toast.makeText(this, "ক্যামেরার অনুমতি দরকার", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startBgThread() {
        if (bgThread == null) {
            bgThread = new HandlerThread("qr-camera");
            bgThread.start();
            bgHandler = new Handler(bgThread.getLooper());
        }
    }

    private void stopBgThread() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try { bgThread.join(); } catch (InterruptedException ignored) {}
            bgThread = null;
            bgHandler = null;
        }
    }

    private void openCamera() {
        startBgThread();
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); cameraDevice = null; }
                @Override public void onError(@NonNull CameraDevice camera, int error) { camera.close(); cameraDevice = null; }
            }, bgHandler);
        } catch (CameraAccessException e) {
            scanHint.setText("ক্যামেরা খোলা যায়নি।");
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture texture = previewView.getSurfaceTexture();
            if (texture == null || cameraDevice == null) return;
            texture.setDefaultBufferSize(1280, 720);
            Surface previewSurface = new Surface(texture);
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::tryDecode, bgHandler);
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(previewSurface);
                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                session.setRepeatingRequest(builder.build(), null, bgHandler);
                            } catch (CameraAccessException ignored) {}
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {}
                    }, bgHandler);
        } catch (CameraAccessException ignored) {}
    }

    private void stopCamera() {
        scanning = false;
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
        } catch (Exception ignored) {}
    }

    // ---------- QR decode ----------

    private void tryDecode(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || !scanning) return;
            long now = System.currentTimeMillis();
            if (now - lastDecodeMs < 200) return;
            lastDecodeMs = now;
            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane yPlane = image.getPlanes()[0];
            ByteBuffer buf = yPlane.getBuffer();
            int rowStride = yPlane.getRowStride();
            byte[] data = new byte[w * h];
            if (rowStride == w) {
                buf.get(data);
            } else {
                byte[] row = new byte[rowStride];
                for (int y = 0; y < h; y++) {
                    buf.get(row);
                    System.arraycopy(row, 0, data, y * w, w);
                }
            }
            PlanarYUVLuminanceSource source =
                    new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
            Result result = this.reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            handleQrText(result.getText());
        } catch (NotFoundException ignored) {
            // No QR in this frame — keep scanning.
        } catch (Exception ignored) {
        } finally {
            if (image != null) image.close();
            try { QrActivity.this.reader.reset(); } catch (Exception ignored) {}
        }
    }

    private void handleQrText(String text) {
        WifiQr wifi = parseWifiQr(text);
        runOnUiThread(() -> {
            if (wifi == null) {
                scanHint.setText("এটা WiFi QR না — WiFi-র QR কোডটা আবার ধরো।");
                return;
            }
            scanning = false;
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(120);
            } catch (Exception ignored) {}
            foundSsid = wifi.ssid;
            foundPass = wifi.pass;
            foundSec = wifi.sec;
            foundHidden = wifi.hidden;
            resultSsid.setText("WiFi: " + wifi.ssid);
            resultSec.setText("নিরাপত্তা: " + wifi.sec + (wifi.hidden ? " • লুকানো" : ""));
            resultPass.setText("পাসওয়ার্ড: " + (wifi.pass == null || wifi.pass.isEmpty() ? "(নেই — খোলা WiFi)" : wifi.pass));
            resultNote.setText("");
            resultBox.setVisibility(View.VISIBLE);
            scanHint.setText("পাওয়া গেছে ✅ — নিচে নাম আর পাসওয়ার্ড দেখো।");
        });
    }

    // ---------- WiFi QR format ----------

    static class WifiQr {
        String ssid, pass, sec;
        boolean hidden;
    }

    static WifiQr parseWifiQr(String text) {
        if (text == null || !text.startsWith("WIFI:")) return null;
        String body = text.substring(5);
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                cur.append(c);
                cur.append(body.charAt(++i));
            } else if (c == ';') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        WifiQr w = new WifiQr();
        for (String p : parts) {
            int idx = p.indexOf(':');
            if (idx <= 0) continue;
            String k = p.substring(0, idx);
            String v = unescape(p.substring(idx + 1));
            if (k.equals("S")) w.ssid = v;
            else if (k.equals("P")) w.pass = v;
            else if (k.equals("T")) w.sec = v;
            else if (k.equals("H")) w.hidden = v.equalsIgnoreCase("true");
        }
        if (w.ssid == null || w.ssid.isEmpty()) return null;
        if (w.sec == null || w.sec.isEmpty()) w.sec = "WPA";
        return w;
    }

    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
            else out.append(c);
        }
        return out.toString();
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace(":", "\\:").replace("\"", "\\\"");
    }

    static String formatWifiQr(String ssid, String pass, String sec, boolean hidden) {
        StringBuilder sb = new StringBuilder("WIFI:");
        sb.append("T:").append(sec).append(';');
        sb.append("S:").append(escape(ssid)).append(';');
        if (pass != null && !pass.isEmpty()) sb.append("P:").append(escape(pass)).append(';');
        if (hidden) sb.append("H:true;");
        sb.append(';');
        return sb.toString();
    }

    // ---------- Connect & copy ----------

    private void connectToFoundWifi() {
        if (foundSsid == null) return;
        if (Build.VERSION.SDK_INT < 29) {
            resultNote.setText("এই ফোনে অ্যাপ থেকে যুক্ত করা যায় না — পাসওয়ার্ড কপি করে WiFi সেটিংস থেকে যুক্ত হও।");
            return;
        }
        if ("WEP".equalsIgnoreCase(foundSec)) {
            resultNote.setText("WEP WiFi-তে অ্যাপ থেকে যুক্ত করা যায় না — পাসওয়ার্ড কপি করে WiFi সেটিংস থেকে যুক্ত হও।");
            return;
        }
        try {
            WifiNetworkSuggestion.Builder b = new WifiNetworkSuggestion.Builder().setSsid(foundSsid);
            if (foundHidden) b.setIsHiddenSsid(true);
            if ("nopass".equalsIgnoreCase(foundSec)) {
                // Open network — no passphrase.
            } else if ("WPA3".equalsIgnoreCase(foundSec)) {
                b.setWpa3Passphrase(foundPass == null ? "" : foundPass);
            } else {
                b.setWpa2Passphrase(foundPass == null ? "" : foundPass);
            }
            int status = wifiManager.addNetworkSuggestions(Collections.singletonList(b.build()));
            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                resultNote.setText("অনুমোদন চাইলে Allow দাও — ফোন নিজে নিজে এই WiFi-তে যুক্ত হবে।");
            } else {
                resultNote.setText("যুক্ত করা যায়নি — পাসওয়ার্ড কপি করে WiFi সেটিংস থেকে যুক্ত হও।");
            }
        } catch (Throwable t) {
            resultNote.setText("যুক্ত করা যায়নি — পাসওয়ার্ড কপি করে WiFi সেটিংস থেকে যুক্ত হও।");
        }
    }

    private void copyFoundPassword() {
        if (foundPass == null || foundPass.isEmpty()) {
            Toast.makeText(this, "এই WiFi-তে পাসওয়ার্ড নেই", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("WiFi password", foundPass));
        Toast.makeText(this, "পাসওয়ার্ড কপি হয়েছে ✅", Toast.LENGTH_SHORT).show();
    }

    // ---------- Generate & save ----------

    private void generateQr() {
        String ssid = etSsid.getText().toString().trim();
        String pass = etPass.getText().toString();
        int pos = spSec.getSelectedItemPosition();
        String sec = pos == 0 ? "WPA" : pos == 1 ? "WPA3" : pos == 2 ? "WEP" : "nopass";
        if (ssid.isEmpty()) {
            genNote.setText("WiFi-র নাম লেখো।");
            return;
        }
        if (!sec.equals("nopass") && pass.isEmpty()) {
            genNote.setText("পাসওয়ার্ড লেখো — খোলা WiFi হলে 'খোলা' বেছে নাও।");
            return;
        }
        String wifi = formatWifiQr(ssid, pass, sec, cbHidden.isChecked());
        try {
            BitMatrix m = new QRCodeWriter().encode(wifi, BarcodeFormat.QR_CODE, 512, 512);
            Bitmap bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 512; x++) {
                for (int y = 0; y < 512; y++) {
                    bmp.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            generatedQr = bmp;
            generatedName = ssid;
            qrImage.setImageBitmap(bmp);
            qrImage.setVisibility(View.VISIBLE);
            findViewById(R.id.btnSaveQr).setVisibility(View.VISIBLE);
            genNote.setText("QR তৈরি ✅ — কেউ এটা স্ক্যান করলেই '" + ssid + "' WiFi-র নাম আর পাসওয়ার্ড পেয়ে যাবে।");
        } catch (WriterException e) {
            genNote.setText("QR বানানো যায়নি — নাম/পাসওয়ার্ড ছোট করে আবার চেষ্টা করো।");
        }
    }

    private void saveQr() {
        if (generatedQr == null) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "wifi-" + generatedName + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WiFiScanner");
            }
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                genNote.setText("সেভ করা যায়নি।");
                return;
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                generatedQr.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            genNote.setText("সেভ হয়েছে ✅ — গ্যালারির WiFiScanner ফোল্ডারে দেখো।");
        } catch (Exception e) {
            genNote.setText("সেভ করা যায়নি।");
        }
    }
}
