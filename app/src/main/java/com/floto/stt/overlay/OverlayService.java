package com.floto.stt.overlay;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.floto.stt.R;
import com.floto.stt.accessibility.FlotoAccessibilityService;
import com.floto.stt.audio.PcmRecorder;
import com.floto.stt.audio.Recording;
import com.floto.stt.model.LocalWhisperTranscriber;
import com.floto.stt.model.OpenAiTranscriber;
import com.floto.stt.model.SpeechTranscriber;
import com.floto.stt.model.SystemOnDeviceRecognizer;
import com.floto.stt.settings.AppSettings;
import com.floto.stt.settings.SettingsActivity;
import com.floto.stt.ui.Ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends Service {
    private static final String CHANNEL_ID = "floto_overlay";
    private static final int NOTIFICATION_ID = 8401;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private FrameLayout overlayView;
    private TextView mainButton;
    private PcmRecorder recorder;
    private SystemOnDeviceRecognizer systemRecognizer;
    private boolean processing;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        addOverlay();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (overlayView == null) {
            addOverlay();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addOverlay() {
        if (windowManager == null || overlayView != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is not enabled", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        overlayView = new FrameLayout(this);
        int size = Ui.dp(this, 92);
        overlayParams = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = AppSettings.getOverlayX(this);
        overlayParams.y = AppSettings.getOverlayY(this);

        mainButton = bubble("STT", 0xff147c72, 18, true);
        FrameLayout.LayoutParams mainParams = new FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64));
        mainParams.gravity = Gravity.CENTER;
        overlayView.addView(mainButton, mainParams);
        mainButton.setOnTouchListener(dragTouchListener(this::toggleRecording));

        ImageButton close = iconBubble(R.drawable.ic_close_20, 0xffd92d20, "Close Floto");
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24));
        closeParams.gravity = Gravity.TOP | Gravity.END;
        closeParams.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 4), 0);
        overlayView.addView(close, closeParams);
        close.setOnTouchListener(dragTouchListener(this::shutdown));

        ImageButton settings = iconBubble(R.drawable.ic_settings_20, 0xff344054, "Open Floto settings");
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
        settingsParams.gravity = Gravity.BOTTOM | Gravity.END;
        settingsParams.setMargins(0, 0, Ui.dp(this, 2), Ui.dp(this, 2));
        overlayView.addView(settings, settingsParams);
        settings.setOnTouchListener(dragTouchListener(this::openSettings));
        overlayView.setOnTouchListener(dragTouchListener(null));

        windowManager.addView(overlayView, overlayParams);
    }

    private View.OnTouchListener dragTouchListener(Runnable clickAction) {
        return new View.OnTouchListener() {
            private int startX;
            private int startY;
            private float downRawX;
            private float downRawY;
            private boolean dragged;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (overlayParams == null || windowManager == null || overlayView == null) {
                    return false;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = overlayParams.x;
                        startY = overlayParams.y;
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downRawX);
                        int dy = Math.round(event.getRawY() - downRawY);
                        if (!dragged && Math.hypot(dx, dy) > Ui.dp(OverlayService.this, 8)) {
                            dragged = true;
                        }
                        if (dragged) {
                            overlayParams.x = clampOverlayX(startX + dx);
                            overlayParams.y = clampOverlayY(startY + dy);
                            windowManager.updateViewLayout(overlayView, overlayParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragged) {
                            AppSettings.setOverlayPosition(OverlayService.this, overlayParams.x, overlayParams.y);
                        } else if (clickAction != null) {
                            view.performClick();
                            clickAction.run();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (dragged) {
                            AppSettings.setOverlayPosition(OverlayService.this, overlayParams.x, overlayParams.y);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

    private int clampOverlayX(int x) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int overlayWidth = overlayView == null ? Ui.dp(this, 92) : overlayView.getWidth();
        return Math.max(0, Math.min(x, Math.max(0, width - overlayWidth)));
    }

    private int clampOverlayY(int y) {
        int height = getResources().getDisplayMetrics().heightPixels;
        int overlayHeight = overlayView == null ? Ui.dp(this, 92) : overlayView.getHeight();
        return Math.max(0, Math.min(y, Math.max(0, height - overlayHeight)));
    }

    private TextView bubble(String text, int color, int textSize, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.WHITE);
        view.setTextSize(textSize);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        view.setBackground(background);
        view.setElevation(Ui.dp(this, 8));
        return view;
    }

    private ImageButton iconBubble(int iconRes, int color, String contentDescription) {
        ImageButton view = new ImageButton(this);
        view.setImageResource(iconRes);
        view.setColorFilter(Color.WHITE);
        view.setContentDescription(contentDescription);
        view.setScaleType(ImageButton.ScaleType.CENTER);
        view.setPadding(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
        view.setBackground(ovalBackground(color));
        view.setElevation(Ui.dp(this, 8));
        return view;
    }

    private GradientDrawable ovalBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        return background;
    }

    private void toggleRecording() {
        if (processing) {
            return;
        }
        if (AppSettings.PROVIDER_SYSTEM_ON_DEVICE.equals(AppSettings.getProvider(this))) {
            toggleSystemRecognition();
            return;
        }
        if (recorder != null && recorder.isRunning()) {
            stopRecordingAndTranscribe();
        } else {
            startRecording();
        }
    }

    private void toggleSystemRecognition() {
        if (systemRecognizer != null && systemRecognizer.isListening()) {
            processing = true;
            setButtonState("...", 0xff475467);
            systemRecognizer.stop();
            return;
        }
        if (!FlotoAccessibilityService.isAvailable()) {
            Toast.makeText(this, "Enable Floto text insertion in Accessibility settings first", Toast.LENGTH_LONG).show();
            openSettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show();
            openSettings();
            return;
        }
        processing = false;
        setButtonState("REC", 0xffd92d20);
        systemRecognizer = new SystemOnDeviceRecognizer(this);
        systemRecognizer.start(AppSettings.getLanguage(this), new SystemOnDeviceRecognizer.Callback() {
            @Override
            public void onReady() {
                setButtonState("REC", 0xffd92d20);
            }

            @Override
            public void onPartialResult(String text) {
                setButtonState("REC", 0xffd92d20);
            }

            @Override
            public void onFinalResult(String text) {
                handleTranscript(text);
            }

            @Override
            public void onError(String message) {
                processing = false;
                setButtonState("STT", 0xff147c72);
                Toast.makeText(OverlayService.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void startRecording() {
        if (!FlotoAccessibilityService.isAvailable()) {
            Toast.makeText(this, "Enable Floto text insertion in Accessibility settings first", Toast.LENGTH_LONG).show();
            openSettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show();
            openSettings();
            return;
        }
        try {
            recorder = new PcmRecorder(this);
            recorder.start();
            setButtonState("REC", 0xffd92d20);
        } catch (Exception e) {
            recorder = null;
            Toast.makeText(this, "Could not start recording: " + e.getMessage(), Toast.LENGTH_LONG).show();
            setButtonState("STT", 0xff147c72);
        }
    }

    private void stopRecordingAndTranscribe() {
        PcmRecorder activeRecorder = recorder;
        recorder = null;
        if (activeRecorder == null) {
            setButtonState("STT", 0xff147c72);
            return;
        }
        try {
            Recording recording = activeRecorder.stop();
            processing = true;
            setButtonState("...", 0xff475467);
            executor.submit(() -> transcribeAndInsert(recording));
        } catch (Exception e) {
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            setButtonState("STT", 0xff147c72);
        }
    }

    private void transcribeAndInsert(Recording recording) {
        try {
            SpeechTranscriber transcriber = AppSettings.PROVIDER_LOCAL_WHISPER.equals(AppSettings.getProvider(this))
                    ? new LocalWhisperTranscriber(this)
                    : new OpenAiTranscriber(this);
            String transcript = transcriber.transcribe(recording);
            mainHandler.post(() -> handleTranscript(transcript));
        } catch (Throwable t) {
            mainHandler.post(() -> {
                Toast.makeText(this, "Transcription failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                processing = false;
                setButtonState("STT", 0xff147c72);
            });
        }
    }

    private void handleTranscript(String transcript) {
        processing = false;
        setButtonState("STT", 0xff147c72);
        if (transcript == null || transcript.trim().isEmpty()) {
            Toast.makeText(this, "No speech recognized", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean inserted = FlotoAccessibilityService.insertText(this, transcript, AppSettings.shouldAppendSpace(this));
        Toast.makeText(this,
                inserted ? "Text inserted" : "Accessibility unavailable. Text copied to clipboard",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void setButtonState(String text, int color) {
        if (mainButton == null) {
            return;
        }
        mainButton.setText(text);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(color);
        mainButton.setBackground(background);
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void shutdown() {
        if (recorder != null && recorder.isRunning()) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }
        if (systemRecognizer != null) {
            systemRecognizer.destroy();
            systemRecognizer = null;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (overlayView != null && windowManager != null) {
            AppSettings.setOverlayPosition(this, overlayParams.x, overlayParams.y);
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
        if (systemRecognizer != null) {
            systemRecognizer.destroy();
            systemRecognizer = null;
        }
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, SettingsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Floto is running")
                .setContentText("Tap the floating button to start or stop dictation.")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Floto overlay",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
