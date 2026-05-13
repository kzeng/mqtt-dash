package com.example.mqttdashboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.mqttdashboard.nativeui.NativeShellActivity;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<String> pickImageLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<Intent> nativeDeviceEntryLauncher;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null && !result.getContents().isEmpty()) {
                deliverScanResultToPage(result.getContents());
            }
        });

        pickImageLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                decodeQrFromImage(uri);
            }
        });

        cameraPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                launchCameraScanner();
            } else {
                showToast("未授予相机权限，无法扫码");
            }
        });

        nativeDeviceEntryLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                return;
            }

            Intent data = result.getData();
            syncNativeDeviceDataToPage(
                    data.getStringExtra(NativeShellActivity.EXTRA_SELECTED_DEVICE_ID),
                    data.getStringExtra(NativeShellActivity.EXTRA_SELECTED_DEVICE_NAME),
                    data.getStringExtra(NativeShellActivity.EXTRA_DEVICE_LIST_JSON)
            );
        });

        webView.addJavascriptInterface(new AndroidScannerBridge(), "AndroidScanner");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                // 使用自定义对话框，去掉URL前缀标题
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient());

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void showScanOptionsDialog() {
        String[] items = new String[]{"摄像头扫码", "相册识别"};
        new AlertDialog.Builder(this)
                .setTitle("扫码录入")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        startCameraScan();
                    } else {
                        openImagePicker();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startCameraScan() {
        if (checkSelfPermissionCompat(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraScanner();
            return;
        }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private int checkSelfPermissionCompat(@NonNull String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(permission);
    }

    private void launchCameraScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("将设备二维码放入取景框内");
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        scanLauncher.launch(options);
    }

    private void openImagePicker() {
        pickImageLauncher.launch("image/*");
    }

    private void decodeQrFromImage(Uri uri) {
        new Thread(() -> {
            try {
                Bitmap bitmap = loadBitmapFromUri(uri);
                if (bitmap == null) {
                    showToast("无法读取图片");
                    return;
                }

                int width = bitmap.getWidth();
                int height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

                RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
                BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));
                Result result = new MultiFormatReader().decode(binaryBitmap);
                deliverScanResultToPage(result.getText());
            } catch (NotFoundException e) {
                showToast("未识别到二维码，请更换清晰图片重试");
            } catch (IOException e) {
                showToast("读取图片失败");
            } catch (RuntimeException e) {
                showToast("图片解析失败，请重试");
            }
        }).start();
    }

    private Bitmap loadBitmapFromUri(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int maxEdge = Math.max(width, height);
                if (maxEdge > 1600) {
                    float scale = 1600f / maxEdge;
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMutableRequired(false);
            });
        }
        return MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
    }

    private void deliverScanResultToPage(String value) {
        String escaped = org.json.JSONObject.quote(value);
        runOnUiThread(() -> webView.evaluateJavascript("scaned(" + escaped + ");", null));
    }

    private void showToast(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    private void openNativeDeviceEntry() {
        Intent intent = new Intent(this, NativeShellActivity.class);
        intent.putExtra(NativeShellActivity.EXTRA_RETURN_RESULT, true);
        nativeDeviceEntryLauncher.launch(intent);
    }

    private void syncNativeDeviceDataToPage(String deviceId, String deviceName, String deviceListJson) {
        String escapedDeviceId = org.json.JSONObject.quote(deviceId == null ? "" : deviceId);
        String escapedDeviceName = org.json.JSONObject.quote(deviceName == null ? "" : deviceName);
        String escapedDeviceListJson = org.json.JSONObject.quote(deviceListJson == null ? "[]" : deviceListJson);

        runOnUiThread(() -> webView.evaluateJavascript(
                "if (typeof applyNativeDeviceData === 'function') { applyNativeDeviceData(" + escapedDeviceId + ", " + escapedDeviceName + ", " + escapedDeviceListJson + "); }",
                null
        ));
    }

    private final class AndroidScannerBridge {
        @JavascriptInterface
        public void showScanOptions() {
            runOnUiThread(MainActivity.this::showScanOptionsDialog);
        }

        @JavascriptInterface
        public void openNativeDeviceEntry() {
            runOnUiThread(MainActivity.this::openNativeDeviceEntry);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
