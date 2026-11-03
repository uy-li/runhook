package com.uy_li.runhook;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

public class FloatingService extends Service {
    private static final String CHANNEL_ID = "runhook_overlay";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams layoutParams;
    private Button toggleButton;
    private GradientDrawable toggleShape;

    // 跟配置文件一致，默认关闭。不能再写死成开。
    private boolean isWorking = false;
    private long lastClickTime = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable reattachRunnable = new Runnable() {
        @Override
        public void run() {
            reattachFloatingView();
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)
                    || Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                // 自动锁屏会把非前台应用的悬浮窗从 WindowManager 里摘掉，解锁后要自己加回去。
                scheduleReattach();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onCreate() {
        super.onCreate();
        ConfigStore.ensureDefaultOff(this);
        isWorking = ConfigStore.isEnabled(this);
        startInForeground();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager != null) {
            createFloatingView();
        }
        registerScreenReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (floatingView == null) {
            if (windowManager != null) createFloatingView();
        } else if (windowManager != null && !floatingView.isAttachedToWindow()) {
            reattachFloatingView();
        }
        return START_STICKY;
    }

    private void startInForeground() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable ignored) {
            // startForegroundService 发出去之后如果这里失败，系统会在几秒后把进程杀掉。
            stopSelf();
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.fgs_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.fgs_channel_desc));
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_cadence)
                .setContentTitle(getString(R.string.fgs_notification_title))
                .setContentText(getString(R.string.fgs_notification_text))
                .setOngoing(true)
                .build();
    }

    @SuppressLint({"ClickableViewAccessibility", "UnspecifiedRegisterReceiverFlag"})
    private void registerScreenReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void scheduleReattach() {
        mainHandler.removeCallbacks(reattachRunnable);
        mainHandler.post(reattachRunnable);
        mainHandler.postDelayed(reattachRunnable, 400);
        mainHandler.postDelayed(reattachRunnable, 1200);
    }

    private void reattachFloatingView() {
        if (floatingView == null || windowManager == null || layoutParams == null) return;
        try {
            windowManager.removeViewImmediate(floatingView);
        } catch (Throwable ignored) {
        }
        try {
            windowManager.addView(floatingView, layoutParams);
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createFloatingView() {
        toggleButton = new Button(this);
        toggleShape = new GradientDrawable();
        toggleShape.setShape(GradientDrawable.OVAL);
        applyButtonAppearance();
        toggleButton.setTextColor(Color.WHITE);
        toggleButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toggleButton.setBackground(toggleShape);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, getResources().getDisplayMetrics());
        layoutParams = new WindowManager.LayoutParams(
                size,
                size,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 0;
        layoutParams.y = 300;

        toggleButton.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isClick = false;
                        }
                        layoutParams.x = initialX + deltaX;
                        layoutParams.y = initialY + deltaY;
                        if (windowManager != null && floatingView.isAttachedToWindow()) {
                            try {
                                windowManager.updateViewLayout(floatingView, layoutParams);
                            } catch (Throwable ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastClickTime < 500) {
                                return true;
                            }
                            lastClickTime = currentTime;

                            isWorking = !isWorking;
                            applyButtonAppearance();
                            toggleButton.setBackground(toggleShape);

                            SharedPreferences sharedPrefs = ConfigStore.prefs(FloatingService.this);
                            float minCadence = sharedPrefs.getFloat(ConfigStore.KEY_MIN, ConfigStore.DEFAULT_MIN);
                            float maxCadence = sharedPrefs.getFloat(ConfigStore.KEY_MAX, ConfigStore.DEFAULT_MAX);
                            ConfigStore.setEnabled(FloatingService.this, isWorking, minCadence, maxCadence);
                        }
                        return true;
                }
                return false;
            }
        });

        floatingView = toggleButton;
        try {
            windowManager.addView(floatingView, layoutParams);
        } catch (Throwable ignored) {
        }
    }

    private void applyButtonAppearance() {
        if (toggleButton == null || toggleShape == null) return;
        if (isWorking) {
            toggleShape.setColor(Color.parseColor("#4CAF50"));
            toggleButton.setText("步频\nON");
        } else {
            toggleShape.setColor(Color.parseColor("#F44336"));
            toggleButton.setText("步频\nOFF");
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(reattachRunnable);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {
        }
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Throwable ignored) {
            }
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
