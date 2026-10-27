package com.socteddy.tokenprinter;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Android's own speech recognizer (the page's browser speech API doesn't work inside an app).
 * Reports to the page as: start, partial, final, error, end. Main thread only.
 */
class VoiceInput implements RecognitionListener {
    interface Sink { void send(String kind, JSONObject data); }

    private final Context context;
    private final Sink sink;
    private SpeechRecognizer recognizer;
    private boolean ended = true;

    VoiceInput(Context context, Sink sink) {
        this.context = context;
        this.sink = sink;
    }

    void start() {
        if (recognizer != null) release();
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            emit("error", "code", "unavailable");
            emit("end");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(this);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        ended = false;
        emit("start");
        recognizer.startListening(i);
    }

    /** Stop listening; whatever was heard so far still comes back as a result. */
    void stop() {
        if (recognizer != null) recognizer.stopListening();
    }

    /** Drop everything (app going to the background). */
    void cancel() {
        if (recognizer == null) return;
        recognizer.cancel();
        finish();
    }

    private void finish() {
        if (!ended) {
            ended = true;
            emit("end");
        }
        release();
    }

    private void release() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private void emit(String type, String... extra) {
        JSONObject o = MainActivity.obj("type", type);
        try {
            for (int i = 0; i + 1 < extra.length; i += 2) o.put(extra[i], extra[i + 1]);
        } catch (Exception ignored) { }
        sink.send("voice", o);
    }

    private static String best(Bundle b) {
        ArrayList<String> list = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    @Override public void onPartialResults(Bundle b) {
        if (!ended) emit("partial", "text", best(b));
    }

    @Override public void onResults(Bundle b) {
        if (ended) return;
        String text = best(b);
        if (text.isEmpty()) emit("error", "code", "no-speech");
        else emit("final", "text", text);
        finish();
    }

    @Override public void onError(int error) {
        if (ended) return;   // some phones send a stray error after the result
        String code;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: code = "no-speech"; break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: code = "not-allowed"; break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: code = "network"; break;
            default: code = "other";
        }
        emit("error", "code", code);
        finish();
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
