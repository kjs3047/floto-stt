package com.floto.stt.model;

import android.content.Context;

import com.floto.stt.audio.Recording;
import com.floto.stt.settings.AppSettings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OpenAiTranscriber implements SpeechTranscriber {
    private static final String ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";

    private final Context context;

    public OpenAiTranscriber(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String transcribe(Recording recording) throws Exception {
        String apiKey = AppSettings.getOpenAiKey(context);
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("OpenAI API key is missing");
        }

        String boundary = "----FlotoBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
            writeFormField(output, boundary, "model", AppSettings.getOpenAiModel(context));
            writeFormField(output, boundary, "response_format", "json");
            String language = AppSettings.getLanguage(context);
            if (!language.isEmpty()) {
                writeFormField(output, boundary, "language", language);
            }
            String prompt = AppSettings.getPrompt(context);
            if (!prompt.isEmpty()) {
                writeFormField(output, boundary, "prompt", prompt);
            }
            writeFileField(output, boundary, "file", recording.getWavFile().getName(), "audio/wav", recording.getWavFile().getAbsolutePath());
            output.writeBytes("--" + boundary + "--\r\n");
            output.flush();
        }

        int code = connection.getResponseCode();
        String body = readBody(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("OpenAI transcription failed: HTTP " + code + " " + body);
        }
        JSONObject json = new JSONObject(body);
        return json.optString("text", "").trim();
    }

    private void writeFormField(DataOutputStream output, String boundary, String name, String value) throws Exception {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.writeBytes("\r\n");
    }

    private void writeFileField(DataOutputStream output, String boundary, String name, String fileName, String contentType, String path) throws Exception {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n");
        output.writeBytes("Content-Type: " + contentType + "\r\n\r\n");
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[1024 * 128];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
        output.writeBytes("\r\n");
    }

    private String readBody(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }
}
