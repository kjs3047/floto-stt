package com.floto.stt.model;

import com.floto.stt.audio.Recording;

public interface SpeechTranscriber {
    String transcribe(Recording recording) throws Exception;
}
