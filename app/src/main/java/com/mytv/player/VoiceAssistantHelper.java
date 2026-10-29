package com.mytv.player;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

public class VoiceAssistantHelper {
    private static final String TAG = "VoiceAssistantHelper";

    public interface AssistantCallback {
        void onListeningStarted();
        void onListeningStopped();
        void onCommandRecognized(String commandText);
        void onError(String errorMessage);
    }

    private final Context context;
    private final AssistantCallback callback;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public VoiceAssistantHelper(Context context, AssistantCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        initRecognizer();
    }

    private void initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition unavailable on device.");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                if (callback != null) callback.onListeningStarted();
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                if (callback != null) callback.onListeningStopped();
            }

            @Override
            public void onError(int error) {
                isListening = false;
                if (callback != null) {
                    callback.onListeningStopped();
                    callback.onError("Speech error code: " + error);
                }
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                if (callback != null) {
                    callback.onListeningStopped();
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        callback.onCommandRecognized(matches.get(0));
                    }
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    public void startListening() {
        if (speechRecognizer == null) initRecognizer();
        if (speechRecognizer == null) {
            if (callback != null) callback.onError("Speech recognizer unavailable");
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

        mainHandler.post(() -> {
            try {
                speechRecognizer.startListening(intent);
            } catch (Exception e) {
                if (callback != null) callback.onError("Microphone error: " + e.getMessage());
            }
        });
    }

    public void stopListening() {
        if (speechRecognizer != null && isListening) {
            mainHandler.post(() -> speechRecognizer.stopListening());
        }
    }

    public void destroy() {
        if (speechRecognizer != null) {
            mainHandler.post(() -> {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
                speechRecognizer = null;
            });
        }
    }
}