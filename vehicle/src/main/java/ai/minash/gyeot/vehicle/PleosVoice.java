package ai.minash.gyeot.vehicle;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.listener.ResultListener;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class PleosVoice implements AutoCloseable {
    private static final String TAG = "GyeotVoice";
    private static final long PLAYBACK_TIMEOUT_MS = 15_000;
    private static final long STOP_TIMEOUT_MS = 2_000;
    private static final long PENDING_MAX_AGE_MS = 10_000;
    private static final int MAX_PENDING_CARE = 2;

    enum Speech { ROUTINE, RESPONSE, CARE }

    private record Utterance(Supplier<String> message, Speech kind, long receivedAt) { }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechToText recognition;
    private ai.pleos.playground.tts.TextToSpeech synthesis;
    private final Consumer<String> recognized;
    private final Consumer<String> status;
    private final boolean debug;
    private volatile boolean ready;
    private boolean synthesisReady;
    private boolean speechFailed;
    private volatile boolean closed;
    private Utterance active;
    private Utterance pending;
    private final ArrayDeque<Utterance> pendingCare = new ArrayDeque<>();
    private boolean started;
    private boolean stopping;
    private final Runnable speechTimeout = this::disableSpeech;
    private final ResultListener results = new ResultListener() {
        @Override public void onReady() { debugEvent("stt_ready"); }
        @Override public void onUpdated(String text, boolean completed) {
            if (completed && !closed) recognized.accept(text);
        }
        @Override public void onError(ErrorType error) { status.accept("음성 인식 오류 · " + error.name()); }
        @Override public void onStartedRecognition() { status.accept("말씀해 주세요"); }
        @Override public void onEndedRecognition() { }
        @Override public void onUpdatedEpdData(long on, long off) { }
        @Override public void onUpdatedRms(double rms) { }
    };
    private final ai.pleos.playground.tts.listener.EventListener synthesisEvents =
            new ai.pleos.playground.tts.listener.EventListener() {
                @Override public void onReady() { debugEvent("tts_ready"); }
                @Override public void onStart() {
                    main.post(() -> {
                        if (active == null || stopping) return;
                        started = true;
                        debugEvent("tts_playback_started");
                    });
                }
                @Override public void onUpdatedRms(double rms) { }
                @Override public void onDone() {
                    main.post(() -> {
                        if (started && !stopping) finishSpeech("tts_playback_done");
                    });
                }
                @Override public void onStop() {
                    main.post(() -> {
                        if (stopping || started) finishSpeech("tts_playback_stopped");
                    });
                }
                @Override public void onError(ai.pleos.playground.tts.constant.ErrorType error) {
                    debugEvent("tts_error=" + error.name());
                    main.post(() -> {
                        if (active != null) disableSpeech();
                    });
                }
            };

    PleosVoice(Context context, Consumer<String> recognized, Consumer<String> status) {
        this.recognized = recognized;
        this.status = status;
        debug = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return;
        try {
            recognition = new SpeechToText(context, Mode.ON_DEVICE);
            recognition.addListener(results);
            recognition.initialize(new ai.pleos.playground.stt.listener.OnInitializeListener() {
                @Override public void onSuccess() {
                    if (closed) return;
                    recognition.setLocale(Locale.KOREA);
                    ready = true;
                }
                @Override public void onFailure(ErrorType error) { status.accept("Pleos 음성 서비스 연결 실패 · " + error.name()); }
            });
            synthesis = new ai.pleos.playground.tts.TextToSpeech(context, ai.pleos.playground.tts.constant.Mode.ON_DEVICE);
            synthesis.initialize(new ai.pleos.playground.tts.listener.OnInitializeListener() {
                @Override public void onSuccess() {
                    main.post(() -> {
                        if (closed) return;
                        synthesis.setLocale(Locale.KOREA);
                        synthesis.addEventListener(synthesisEvents);
                        synthesisReady = true;
                    });
                }
                @Override public void onFailure(ai.pleos.playground.tts.constant.ErrorType error) {
                    main.post(() -> {
                        if (closed) return;
                        speechFailed = true;
                        status.accept("Pleos 음성 안내 연결 실패 · " + error.name());
                    });
                }
            });
        } catch (IllegalStateException | SecurityException e) { status.accept("Pleos 음성 서비스 연결을 확인해 주세요"); }
    }

    void listen() {
        if (recognition == null || !ready) { status.accept("Pleos 음성 서비스 준비 대기 · 버튼으로 요청할 수 있어요"); return; }
        recognition.request();
    }

    void speak(String message) {
        speak(() -> message, Speech.RESPONSE);
    }

    void speak(Supplier<String> message, Speech kind) {
        if (!synthesisReady) {
            debugEvent("tts_unavailable");
            return;
        }
        Utterance request = new Utterance(message, kind, SystemClock.elapsedRealtime());
        if (active == null) {
            play(request);
            return;
        }
        if (kind == Speech.CARE) {
            if (pendingCare.size() < MAX_PENDING_CARE) pendingCare.addLast(request);
            if (active.kind() != Speech.CARE) requestStop();
        } else if (pending == null || kind == Speech.RESPONSE) {
            pending = request;
        }
    }

    private void play(Utterance request) {
        active = request;
        started = false;
        main.postDelayed(speechTimeout, PLAYBACK_TIMEOUT_MS);
        synthesis.speak(request.message().get(), true);
    }

    private void requestStop() {
        if (active == null || stopping) return;
        stopping = true;
        main.removeCallbacks(speechTimeout);
        main.postDelayed(speechTimeout, STOP_TIMEOUT_MS);
        synthesis.stop();
    }

    private void finishSpeech(String event) {
        debugEvent(event);
        main.removeCallbacks(speechTimeout);
        active = null;
        started = false;
        stopping = false;
        while (!pendingCare.isEmpty()) {
            Utterance care = pendingCare.removeFirst();
            if (isFresh(care)) {
                play(care);
                return;
            }
        }
        Utterance next = pending;
        pending = null;
        if (next != null && isFresh(next)) play(next);
    }

    private boolean isFresh(Utterance request) {
        return SystemClock.elapsedRealtime() - request.receivedAt() <= PENDING_MAX_AGE_MS;
    }

    private void disableSpeech() {
        debugEvent("tts_session_failed");
        main.removeCallbacks(speechTimeout);
        active = null;
        pending = null;
        pendingCare.clear();
        started = false;
        stopping = false;
        synthesisReady = false;
        speechFailed = true;
        synthesis.removeEventListener(synthesisEvents);
        synthesis.stop();
        status.accept("Pleos 음성 안내를 완료하지 못했어요");
    }

    void invalidateObservationSpeech() {
        pendingCare.clear();
        discardPendingRoutine();
        if (active != null && !started && active.kind() != Speech.RESPONSE) requestStop();
    }

    void discardPendingRoutine() {
        if (pending != null && pending.kind() == Speech.ROUTINE) pending = null;
    }

    boolean hasSpeechFailure() {
        return speechFailed;
    }

    void cancelSpeech() {
        pending = null;
        pendingCare.clear();
        requestStop();
    }

    private void debugEvent(String event) {
        if (debug && !closed) Log.d(TAG, event);
    }

    void stop() {
        if (ready) recognition.stop();
        cancelSpeech();
    }

    @Override public void close() {
        closed = true;
        ready = false;
        if (recognition != null) { recognition.removeListener(results); recognition.release(); }
        if (synthesis != null) {
            main.removeCallbacksAndMessages(null);
            active = null;
            pending = null;
            pendingCare.clear();
            started = false;
            stopping = false;
            if (synthesisReady) synthesis.removeEventListener(synthesisEvents);
            synthesisReady = false;
            synthesis.release();
        }
    }
}
