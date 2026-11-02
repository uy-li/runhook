package com.uy_li.runhook;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.View;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private SharedPreferences sharedPrefs;
    private boolean startingService;
    private boolean updatingFields;
    private TextInputLayout inputMin;
    private TextInputLayout inputMax;
    private TextInputEditText etMin;
    private TextInputEditText etMax;
    private TextView tvSliderValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        sharedPrefs = ConfigStore.prefs(this);
        ConfigStore.ensureDefaultOff(this);

        inputMin = findViewById(R.id.input_min);
        inputMax = findViewById(R.id.input_max);
        etMin = findViewById(R.id.et_min);
        etMax = findViewById(R.id.et_max);
        tvSliderValue = findViewById(R.id.tv_slider_value);
        bindCadenceInputs();

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
        MaterialButton btnTheme = findViewById(R.id.btn_theme);
        boolean dark = ConfigStore.isDarkTheme(this);
        btnTheme.setIconResource(dark ? R.drawable.ic_theme_sun : R.drawable.ic_theme_moon);
        btnTheme.setContentDescription(getString(dark ? R.string.theme_switch_to_light : R.string.theme_switch_to_dark));
        btnTheme.setOnClickListener(v -> ConfigStore.setDarkTheme(this, !ConfigStore.isDarkTheme(this)));

        View githubLink = findViewById(R.id.link_github);
        githubLink.setOnClickListener(v -> openGithub());

        MaterialButton btnStart = findViewById(R.id.btn_start);
        btnStart.setOnClickListener(v -> {
            if (!commitCadenceFromInputs()) {
                Toast.makeText(this, "步频需要是大于 0 的数字", Toast.LENGTH_SHORT).show();
                return;
            }
            checkOverlayPermissionAndStart();
        });
    }

    @Override
    protected void onPause() {
        commitCadenceFromInputs();
        super.onPause();
    }

    private void bindCadenceInputs() {
        float minVal = ConfigStore.sanitizeCadence(
                sharedPrefs.getFloat(ConfigStore.KEY_MIN, ConfigStore.DEFAULT_MIN),
                ConfigStore.DEFAULT_MIN);
        float maxVal = ConfigStore.sanitizeCadence(
                sharedPrefs.getFloat(ConfigStore.KEY_MAX, ConfigStore.DEFAULT_MAX),
                ConfigStore.DEFAULT_MAX);
        if (maxVal < minVal) {
            float swap = minVal;
            minVal = maxVal;
            maxVal = swap;
        }
        setFieldText(etMin, minVal);
        setFieldText(etMax, maxVal);
        updateSummary(minVal, maxVal);

        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (updatingFields) return;
                Float min = ConfigStore.parseCadence(textOf(etMin));
                Float max = ConfigStore.parseCadence(textOf(etMax));
                if (min != null && max != null) {
                    updateSummary(Math.min(min, max), Math.max(min, max));
                }
            }
        };
        etMin.addTextChangedListener(watcher);
        etMax.addTextChangedListener(watcher);
        etMin.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitCadenceFromInputs();
        });
        etMax.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitCadenceFromInputs();
        });
        etMax.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitCadenceFromInputs();
            }
            return false;
        });
    }

    private void setFieldText(TextInputEditText field, float value) {
        updatingFields = true;
        field.setText(formatCadence(value));
        updatingFields = false;
    }

    private static String formatCadence(float value) {
        if (value == (float) Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return Float.toString(value);
    }

    private static String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    /** 两个输入都合法才落盘。空、非数字、0 和负数只标错，不改已保存的值，也不崩。 */
    private boolean commitCadenceFromInputs() {
        if (updatingFields || etMin == null || etMax == null) return false;
        Float min = ConfigStore.parseCadence(textOf(etMin));
        Float max = ConfigStore.parseCadence(textOf(etMax));
        inputMin.setError(min == null ? "请输入大于 0 的数字" : null);
        inputMax.setError(max == null ? "请输入大于 0 的数字" : null);
        if (min == null || max == null) return false;
        float low = Math.min(min, max);
        float high = Math.max(min, max);
        ConfigStore.saveCadence(this, low, high);
        updateSummary(low, high);
        if (!etMin.hasFocus() && !etMax.hasFocus() && (min > max)) {
            setFieldText(etMin, low);
            setFieldText(etMax, high);
        }
        return true;
    }

    private void updateSummary(float min, float max) {
        tvSliderValue.setText(formatCadence(min) + " - " + formatCadence(max));
    }

    private void openGithub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_project_url))));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
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