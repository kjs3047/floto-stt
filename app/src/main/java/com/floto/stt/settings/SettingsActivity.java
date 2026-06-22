package com.floto.stt.settings;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.floto.stt.overlay.OverlayService;
import com.floto.stt.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SettingsActivity extends Activity {
    private static final int REQ_MODEL_FILE = 41;
    private static final int REQ_AUDIO = 42;
    private static final int REQ_NOTIFICATIONS = 43;

    private EditText apiKeyField;
    private EditText localModelPathField;
    private EditText languageField;
    private EditText promptField;
    private CheckBox appendSpaceCheck;
    private TextView localProfileHelp;
    private TextView permissionStatus;
    private Button downloadModelButton;
    private ProgressBar modelDownloadProgress;
    private TextView modelDownloadStatus;
    private volatile boolean modelDownloadRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 24));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Floto");
        title.setTextSize(28);
        title.setPadding(0, 0, 0, Ui.dp(this, 4));
        root.addView(title);

        TextView subtitle = Ui.help(this, "Floating speech-to-text input for focused Android text fields.");
        root.addView(subtitle);

        root.addView(Ui.label(this, "Recognition provider"));
        Spinner providerSpinner = new Spinner(this);
        providerSpinner.setAdapter(simpleAdapter(AppSettings.PROVIDER_LABELS));
        providerSpinner.setSelection(AppSettings.indexOf(AppSettings.PROVIDER_VALUES, AppSettings.getProvider(this)));
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppSettings.setProvider(SettingsActivity.this, AppSettings.PROVIDER_VALUES[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(providerSpinner);
        root.addView(Ui.help(this, "Use System on-device first for fast dictation. OpenAI is the accuracy fallback. Local Whisper.cpp is CPU-bound and kept only for offline experiments."));

        root.addView(Ui.label(this, "OpenAI transcription model"));
        Spinner openAiModelSpinner = new Spinner(this);
        openAiModelSpinner.setAdapter(simpleAdapter(AppSettings.OPENAI_MODELS));
        openAiModelSpinner.setSelection(AppSettings.indexOf(AppSettings.OPENAI_MODELS, AppSettings.getOpenAiModel(this)));
        openAiModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppSettings.setOpenAiModel(SettingsActivity.this, AppSettings.OPENAI_MODELS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(openAiModelSpinner);

        root.addView(Ui.label(this, "OpenAI API key"));
        apiKeyField = new EditText(this);
        apiKeyField.setSingleLine(true);
        apiKeyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyField.setHint("sk-...");
        apiKeyField.setText(AppSettings.getOpenAiKey(this));
        root.addView(apiKeyField);
        root.addView(Ui.help(this, "For a personal APK this can call OpenAI directly. For distribution, use a backend token proxy instead of embedding a long-lived key."));

        root.addView(Ui.label(this, "Local model profile"));
        Spinner localProfileSpinner = new Spinner(this);
        String[] profileLabels = new String[AppSettings.LOCAL_MODEL_PROFILES.length];
        for (int i = 0; i < AppSettings.LOCAL_MODEL_PROFILES.length; i++) {
            profileLabels[i] = AppSettings.LOCAL_MODEL_PROFILES[i].toString();
        }
        localProfileSpinner.setAdapter(simpleAdapter(profileLabels));
        int selectedProfile = Math.min(AppSettings.getLocalProfile(this), AppSettings.LOCAL_MODEL_PROFILES.length - 1);
        localProfileSpinner.setSelection(selectedProfile);
        localProfileHelp = Ui.help(this, "");
        updateLocalProfileHelp(selectedProfile);
        localProfileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppSettings.setLocalProfile(SettingsActivity.this, position);
                updateLocalProfileHelp(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        root.addView(localProfileSpinner);
        root.addView(localProfileHelp);

        root.addView(Ui.label(this, "Local model file"));
        localModelPathField = new EditText(this);
        localModelPathField.setSingleLine(false);
        localModelPathField.setMinLines(2);
        localModelPathField.setText(AppSettings.getLocalModelPath(this));
        localModelPathField.setHint("Choose a ggml-*.bin model file");
        root.addView(localModelPathField);

        Button chooseModelButton = new Button(this);
        chooseModelButton.setText("Choose and copy model file");
        chooseModelButton.setOnClickListener(v -> chooseModelFile());
        root.addView(chooseModelButton);

        downloadModelButton = new Button(this);
        downloadModelButton.setText("Download selected local model");
        downloadModelButton.setOnClickListener(v -> downloadSelectedModel());
        root.addView(downloadModelButton);

        modelDownloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modelDownloadProgress.setMax(100);
        modelDownloadProgress.setProgress(0);
        modelDownloadProgress.setVisibility(View.GONE);
        root.addView(modelDownloadProgress);

        modelDownloadStatus = Ui.help(this, "No model download running");
        root.addView(modelDownloadStatus);

        root.addView(Ui.label(this, "Language code"));
        languageField = new EditText(this);
        languageField.setSingleLine(true);
        languageField.setText(AppSettings.getLanguage(this));
        languageField.setHint("ko");
        root.addView(languageField);

        root.addView(Ui.label(this, "Recognition prompt"));
        promptField = new EditText(this);
        promptField.setSingleLine(false);
        promptField.setMinLines(2);
        promptField.setHint("Optional words, names, or formatting hints");
        promptField.setText(AppSettings.getPrompt(this));
        root.addView(promptField);

        appendSpaceCheck = new CheckBox(this);
        appendSpaceCheck.setText("Append a trailing space after inserted text");
        appendSpaceCheck.setChecked(AppSettings.shouldAppendSpace(this));
        root.addView(appendSpaceCheck);

        Button saveButton = new Button(this);
        saveButton.setText("Save settings");
        saveButton.setOnClickListener(v -> {
            saveFields();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
        root.addView(saveButton);

        root.addView(Ui.label(this, "Required Android permissions"));
        permissionStatus = Ui.help(this, "");
        root.addView(permissionStatus);

        Button overlayPermissionButton = new Button(this);
        overlayPermissionButton.setText("Open overlay permission");
        overlayPermissionButton.setOnClickListener(v -> openOverlayPermission());
        root.addView(overlayPermissionButton);

        Button accessibilityButton = new Button(this);
        accessibilityButton.setText("Open accessibility settings");
        accessibilityButton.setOnClickListener(v -> openAccessibilitySettings());
        root.addView(accessibilityButton);

        root.addView(Ui.help(this, "Accessibility must be enabled so Floto can insert text into the currently focused field. Overlay permission must be enabled so the floating button can stay above other apps."));

        Button startButton = new Button(this);
        startButton.setText("Start floating button");
        startButton.setOnClickListener(v -> startOverlay());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        startParams.setMargins(0, Ui.dp(this, 18), 0, 0);
        startButton.setLayoutParams(startParams);
        root.addView(startButton);

        setContentView(scrollView);
        updatePermissionStatus();
    }

    private ArrayAdapter<String> simpleAdapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void updateLocalProfileHelp(int index) {
        AppSettings.LocalModelProfile profile = AppSettings.LOCAL_MODEL_PROFILES[index];
        localProfileHelp.setText(profile.description + "\nDownload: " + profile.downloadUrl);
    }

    private void saveFields() {
        AppSettings.setOpenAiKey(this, apiKeyField.getText().toString());
        AppSettings.setLocalModelPath(this, localModelPathField.getText().toString());
        AppSettings.setLanguage(this, languageField.getText().toString());
        AppSettings.setPrompt(this, promptField.getText().toString());
        AppSettings.setAppendSpace(this, appendSpaceCheck.isChecked());
    }

    private void chooseModelFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_MODEL_FILE);
    }

    private void downloadSelectedModel() {
        if (modelDownloadRunning) {
            Toast.makeText(this, "Model download is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = Math.min(AppSettings.getLocalProfile(this), AppSettings.LOCAL_MODEL_PROFILES.length - 1);
        AppSettings.LocalModelProfile profile = AppSettings.LOCAL_MODEL_PROFILES[index];
        setModelDownloadRunning(true);
        updateModelDownloadStatus("Starting " + profile.fileName, 0, 0);
        new Thread(() -> {
            try {
                String path = downloadModel(profile);
                runOnUiThread(() -> {
                    localModelPathField.setText(path);
                    AppSettings.setLocalModelPath(this, path);
                    updateModelDownloadStatus("Ready: " + profile.fileName, 1, 1);
                    setModelDownloadRunning(false);
                    Toast.makeText(this, "Local model ready", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setModelDownloadRunning(false);
                    modelDownloadStatus.setText("Download failed: " + e.getMessage());
                    modelDownloadStatus.setTextColor(Color.rgb(180, 35, 24));
                    Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "floto-model-download").start();
    }

    private void openOverlayPermission() {
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void openAccessibilitySettings() {
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    private void startOverlay() {
        saveFields();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable overlay permission first", Toast.LENGTH_LONG).show();
            openOverlayPermission();
            return;
        }
        if (!isAccessibilityEnabled(this)) {
            Toast.makeText(this, "Enable Floto text insertion first", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Floating button started", Toast.LENGTH_LONG).show();
    }

    private void requestRuntimePermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private boolean isAccessibilityEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null) {
            return false;
        }
        String expected = context.getPackageName() + "/com.floto.stt.accessibility.FlotoAccessibilityService";
        return enabled.toLowerCase().contains(expected.toLowerCase());
    }

    private void updatePermissionStatus() {
        if (permissionStatus == null) {
            return;
        }
        boolean overlayEnabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean accessibilityEnabled = isAccessibilityEnabled(this);
        boolean audioEnabled = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        permissionStatus.setText(
                "Microphone: " + status(audioEnabled)
                        + "\nOverlay: " + status(overlayEnabled)
                        + "\nText insertion accessibility: " + status(accessibilityEnabled)
        );
        permissionStatus.setTextColor(accessibilityEnabled && overlayEnabled && audioEnabled
                ? Color.rgb(18, 118, 76)
                : Color.rgb(180, 35, 24));
    }

    private String status(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MODEL_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                String copiedPath = copyModelIntoAppStorage(data.getData());
                localModelPathField.setText(copiedPath);
                AppSettings.setLocalModelPath(this, copiedPath);
                Toast.makeText(this, "Model copied", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not copy model: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private String copyModelIntoAppStorage(Uri uri) throws Exception {
        File modelsDir = new File(getFilesDir(), "models");
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw new IllegalStateException("Could not create model directory");
        }
        String name = sanitizeFileName(queryDisplayName(uri));
        if (name.isEmpty()) {
            name = AppSettings.LOCAL_MODEL_PROFILES[AppSettings.getLocalProfile(this)].fileName;
        }
        File target = new File(modelsDir, name);
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) {
                throw new IllegalStateException("Selected file could not be opened");
            }
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        return target.getAbsolutePath();
    }

    private String downloadModel(AppSettings.LocalModelProfile profile) throws Exception {
        File modelsDir = new File(getFilesDir(), "models");
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            throw new IllegalStateException("Could not create model directory");
        }
        File target = new File(modelsDir, profile.fileName);
        File partialTarget = new File(modelsDir, profile.fileName + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(profile.downloadUrl).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(300000);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        long totalBytes = connection.getContentLengthLong();
        if (target.isFile() && totalBytes > 0 && target.length() == totalBytes) {
            updateModelDownloadStatus("Already downloaded: " + profile.fileName, totalBytes, totalBytes);
            return target.getAbsolutePath();
        }
        if (partialTarget.exists() && !partialTarget.delete()) {
            throw new IllegalStateException("Could not clear previous partial download");
        }
        try (InputStream input = connection.getInputStream();
             OutputStream output = new FileOutputStream(partialTarget)) {
            byte[] buffer = new byte[1024 * 256];
            int read;
            long downloadedBytes = 0;
            long lastUiUpdate = 0;
            updateModelDownloadStatus("Downloading " + profile.fileName, 0, totalBytes);
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloadedBytes += read;
                if (downloadedBytes - lastUiUpdate >= 1024 * 512 || downloadedBytes == totalBytes) {
                    lastUiUpdate = downloadedBytes;
                    updateModelDownloadStatus("Downloading " + profile.fileName, downloadedBytes, totalBytes);
                }
            }
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not replace old model file");
        }
        if (!partialTarget.renameTo(target)) {
            throw new IllegalStateException("Could not finalize downloaded model");
        }
        return target.getAbsolutePath();
    }

    private void setModelDownloadRunning(boolean running) {
        modelDownloadRunning = running;
        if (downloadModelButton != null) {
            downloadModelButton.setEnabled(!running);
        }
        if (modelDownloadProgress != null) {
            modelDownloadProgress.setVisibility(View.VISIBLE);
            modelDownloadProgress.setIndeterminate(running && modelDownloadProgress.getProgress() == 0);
        }
    }

    private void updateModelDownloadStatus(String label, long downloadedBytes, long totalBytes) {
        runOnUiThread(() -> {
            if (modelDownloadProgress == null || modelDownloadStatus == null) {
                return;
            }
            if (totalBytes > 0) {
                int percent = (int) Math.min(100, Math.max(0, downloadedBytes * 100 / totalBytes));
                modelDownloadProgress.setIndeterminate(false);
                modelDownloadProgress.setProgress(percent);
                modelDownloadStatus.setText(label + " - " + percent + "% (" + formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes) + ")");
            } else {
                modelDownloadProgress.setIndeterminate(true);
                modelDownloadStatus.setText(label + " - " + formatBytes(downloadedBytes));
            }
            modelDownloadStatus.setTextColor(Color.rgb(52, 64, 84));
        });
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format("%.2f GB", bytes / 1024f / 1024f / 1024f);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format("%.1f MB", bytes / 1024f / 1024f);
        }
        if (bytes >= 1024L) {
            return String.format("%.1f KB", bytes / 1024f);
        }
        return bytes + " B";
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        }
        return "";
    }

    private String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
