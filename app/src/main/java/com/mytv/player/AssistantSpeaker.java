package com.mytv.player;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class AssistantSpeaker {
    private static final String TAG = "AssistantSpeaker";
    private TextToSpeech tts;
    private boolean isReady = false;

    public AssistantSpeaker(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                int res = tts.setLanguage(Locale.getDefault());
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                isReady = true;
            } else {
                Log.e(TAG, "TTS Initialization failed: " + status);
            }
        });
    }

    public void speak(String text) {
        if (!isReady || tts == null || text == null || text.trim().isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ZH_VOICE_OUT");
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isReady = false;
        }
    }
}