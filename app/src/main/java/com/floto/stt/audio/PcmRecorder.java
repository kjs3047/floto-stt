package com.floto.stt.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class PcmRecorder {
    public static final int SAMPLE_RATE = 16000;

    private final Context context;
    private final Object lock = new Object();

    private AudioRecord audioRecord;
    private Thread recordThread;
    private ByteArrayOutputStream pcm;
    private volatile boolean running;

    public PcmRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            int minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Microphone permission is not granted");
            }
            int bufferSize = Math.max(minBuffer, SAMPLE_RATE);
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = null;
                throw new IllegalStateException("AudioRecord could not be initialized");
            }

            pcm = new ByteArrayOutputStream(bufferSize * 4);
            running = true;
            audioRecord.startRecording();
            recordThread = new Thread(() -> recordLoop(bufferSize), "floto-audio-recorder");
            recordThread.start();
        }
    }

    public Recording stop() throws IOException {
        AudioRecord localRecord;
        Thread localThread;
        synchronized (lock) {
            if (!running || audioRecord == null) {
                throw new IllegalStateException("Recorder is not running");
            }
            running = false;
            localRecord = audioRecord;
            localThread = recordThread;
            try {
                localRecord.stop();
            } catch (IllegalStateException ignored) {
            }
        }

        try {
            localThread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (lock) {
            if (audioRecord != null) {
                audioRecord.release();
            }
            audioRecord = null;
            recordThread = null;
            byte[] pcmData = pcm == null ? new byte[0] : pcm.toByteArray();
            pcm = null;
            if (pcmData.length < 2) {
                throw new IllegalStateException("No audio was recorded");
            }
            File wav = createWavFile(pcmData);
            return new Recording(wav, pcm16ToFloat(pcmData), SAMPLE_RATE);
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void recordLoop(int bufferSizeBytes) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        short[] buffer = new short[Math.max(1024, bufferSizeBytes / 2)];
        while (running && audioRecord != null) {
            int read = audioRecord.read(buffer, 0, buffer.length);
            if (read > 0) {
                synchronized (lock) {
                    if (pcm != null) {
                        for (int i = 0; i < read; i++) {
                            short sample = buffer[i];
                            pcm.write(sample & 0xff);
                            pcm.write((sample >> 8) & 0xff);
                        }
                    }
                }
            }
        }
    }

    private File createWavFile(byte[] pcmData) throws IOException {
        File dir = new File(context.getCacheDir(), "recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create recording cache");
        }
        File wav = File.createTempFile("floto-", ".wav", dir);
        try (FileOutputStream output = new FileOutputStream(wav)) {
            writeWavHeader(output, pcmData.length, SAMPLE_RATE, 1, 16);
            output.write(pcmData);
        }
        return wav;
    }

    private void writeWavHeader(FileOutputStream output, int pcmBytes, int sampleRate, int channels, int bitsPerSample) throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmBytes;
        int riffSize = 36 + dataSize;

        output.write(new byte[]{
                'R', 'I', 'F', 'F',
                (byte) (riffSize & 0xff), (byte) ((riffSize >> 8) & 0xff), (byte) ((riffSize >> 16) & 0xff), (byte) ((riffSize >> 24) & 0xff),
                'W', 'A', 'V', 'E',
                'f', 'm', 't', ' ',
                16, 0, 0, 0,
                1, 0,
                (byte) channels, 0,
                (byte) (sampleRate & 0xff), (byte) ((sampleRate >> 8) & 0xff), (byte) ((sampleRate >> 16) & 0xff), (byte) ((sampleRate >> 24) & 0xff),
                (byte) (byteRate & 0xff), (byte) ((byteRate >> 8) & 0xff), (byte) ((byteRate >> 16) & 0xff), (byte) ((byteRate >> 24) & 0xff),
                (byte) blockAlign, 0,
                (byte) bitsPerSample, 0,
                'd', 'a', 't', 'a',
                (byte) (dataSize & 0xff), (byte) ((dataSize >> 8) & 0xff), (byte) ((dataSize >> 16) & 0xff), (byte) ((dataSize >> 24) & 0xff)
        });
    }

    private float[] pcm16ToFloat(byte[] pcmData) {
        int samples = pcmData.length / 2;
        float[] floats = new float[samples];
        for (int i = 0; i < samples; i++) {
            int lo = pcmData[i * 2] & 0xff;
            int hi = pcmData[i * 2 + 1];
            short value = (short) ((hi << 8) | lo);
            floats[i] = value / 32768.0f;
        }
        return floats;
    }
}
