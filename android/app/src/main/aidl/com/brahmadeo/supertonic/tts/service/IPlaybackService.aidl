package com.brahmadeo.supertonic.tts.service;

import com.brahmadeo.supertonic.tts.service.IPlaybackListener;

interface IPlaybackService {
    oneway void synthesizeAndPlay(String text, String stylePath, float speed, int steps, int startIndex);
    oneway void stop();
    boolean isServiceActive();
    oneway void setListener(IPlaybackListener listener);
    oneway void exportAudio(String text, String stylePath, float speed, int steps, String outputPath);
    int getCurrentIndex();
}