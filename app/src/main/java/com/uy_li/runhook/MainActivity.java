package com.uy_li.runhook;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.RangeSlider;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private SharedPreferences sharedPrefs;
    private boolean startingService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        sharedPrefs = ConfigStore.prefs(this);
        ConfigStore.ensureDefaultOff(this);

        // 初始化步频配置 UI
        RangeSlider rangeSlider = findViewById(R.id.range_slider);
        TextView tvSliderValue = findViewById(R.id.tv_slider_value);
        
        float minVal = ConfigStore.clampCadence(sharedPrefs.getFloat(ConfigStore.KEY_MIN, ConfigStore.DEFAULT_MIN));
        float maxVal = ConfigStore.clampCadence(sharedPrefs.getFloat(ConfigStore.KEY_MAX, ConfigStore.DEFAULT_MAX));
        if (maxVal < minVal) {
            float swap = minVal;
            minVal = maxVal;
            maxVal = swap;
        }
        rangeSlider.setValues(minVal, maxVal);
        tvSliderValue.setText((int) minVal + " - " + (int) maxVal);

        rangeSlider.addOnChangeListener((slider, value, fromUser) -> {
            float min = slider.getValues().get(0);
            float max = slider.getValues().get(1);
            tvSliderValue.setText((int) min + " - " + (int) max);
            if (!fromUser) return;
            // commit，避免进程马上被杀掉时 apply 还没落盘。
            ConfigStore.saveCadence(this, min, max);
        });

        // 注册现代的 ActivityResultCallback 处理悬浮窗授权回调
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    startingService = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.canDrawOverlays(this)) {
                            // 授权成功
                            startFloatingServiceAndFinish();
                        } else {
                            // 拒绝授权
                            Toast.makeText(this, "需要悬浮窗权限才能显示控制按钮", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> actuallyStartAndBackground()
        );

        // 绑定按钮事件
        MaterialButton btnStart = findViewById(R.id.btn_start);
        btnStart.setOnClickListener(v -> checkOverlayPermissionAndStart());
    }

    private void checkOverlayPermissionAndStart() {
        if (startingService) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startFloatingServiceAndFinish();
            return;
        }

        // su 和 Thread.sleep 不能放在主线程上，否则 Magisk 确认框一慢就会 ANR。
        startingService = true;
        new Thread(() -> {
            boolean granted = grantOverlayPermissionViaRoot();
            if (granted) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    startingService = false;
                    return;
                }
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "已通过 Root 自动授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                    startFloatingServiceAndFinish();
                    return;
                }
                startingService = false;
                Toast.makeText(this, "Root 授权失败，请手动开启悬浮窗", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                overlayPermissionLauncher.launch(intent);
            });
        }, "overlay-grant").start();
    }

    /**
     * 尝试通过 Root 执行 appops 命令静默授予悬浮窗权限。
     * 必须读掉 su 的输出，否则管道写满后 waitFor 会一直卡住。
     */
    private boolean grantOverlayPermissionViaRoot() {
        Process process = null;
        try {
            process = new ProcessBuilder("su").redirectErrorStream(true).start();
            Process running = process;
            Thread drain = new Thread(() -> {
                try (InputStream in = running.getInputStream()) {
                    byte[] buffer = new byte[256];
                    while (in.read(buffer) != -1) {
                        // 丢掉 su 的提示，避免缓冲区塞满。
                    }
                } catch (Exception ignored) {
                }
            }, "su-drain");
            drain.start();

            try (DataOutputStream os = new DataOutputStream(process.getOutputStream())) {
                os.writeBytes("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow\n");
                os.writeBytes("exit\n");
                os.flush();
            }
            if (!waitForExit(process, 8000)) {
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** waitFor(timeout) 和 destroyForcibly 都是 API 26 才有。 */
    private boolean waitForExit(Process process, long timeoutMs) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= 26) {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return true;
            process.destroyForcibly();
            return false;
        }
        Thread killer = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
                process.destroy();
            } catch (InterruptedException ignored) {
            }
        }, "su-timeout");
        killer.start();
        process.waitFor();
        killer.interrupt();
        return true;
    }

    private void startFloatingServiceAndFinish() {
        // 先把通知权限的结果收回来再退到后台，否则系统弹窗会跟着 Activity 一起被丢掉。
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        actuallyStartAndBackground();
    }

    private void actuallyStartAndBackground() {
        startingService = false;
        Intent intent = new Intent(this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
        // 启动完毕后调用 moveTaskToBack(true) 将程序退到后台而不是直接销毁，保留界面状态
        moveTaskToBack(true);
    }
}