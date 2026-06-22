package com.floto.stt.audio;

import java.io.File;

public final class Recording {
    private final File wavFile;
    private final float[] monoSamples;
    private final int sampleRate;

    public Recording(File wavFile, float[] monoSamples, int sampleRate) {
        this.wavFile = wavFile;
        this.monoSamples = monoSamples;
        this.sampleRate = sampleRate;
    }

    public File getWavFile() {
        return wavFile;
    }

    public float[] getMonoSamples() {
        return monoSamples;
    }

    public int getSampleRate() {
        return sampleRate;
    }
}
