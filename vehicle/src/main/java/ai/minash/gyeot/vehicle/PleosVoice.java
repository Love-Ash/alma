package ai.minash.gyeot.vehicle;

import android.content.Context;
import android.content.pm.PackageManager;
import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.listener.ResultListener;
import java.util.Locale;
import java.util.function.Consumer;

final class PleosVoice implements AutoCloseable {
    private SpeechToText recognition;
    private ai.pleos.playground.tts.TextToSpeech synthesis;
    private final Consumer<String> recognized;
    private final Consumer<String> status;
    private volatile boolean ready;
    private volatile boolean synthesisReady;
    private volatile boolean closed;
    private final ResultListener results = new ResultListener() {
        @Override public void onReady() { }
        @Override public void onUpdated(String text, boolean completed) {
            if (completed && !closed) recognized.accept(text);
        }
        @Override public void onError(ErrorType error) { status.accept("음성 인식 오류 · " + error.name()); }
        @Override public void onStartedRecognition() { status.accept("말씀해 주세요"); }
        @Override public void onEndedRecognition() { }
        @Override public void onUpdatedEpdData(long on, long off) { }
        @Override public void onUpdatedRms(double rms) { }
    };

    PleosVoice(Context context, Consumer<String> recognized, Consumer<String> status) {
        this.recognized = recognized;
        this.status = status;
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
                    if (closed) return;
                    synthesis.setLocale(Locale.KOREA);
                    synthesisReady = true;
                }
                @Override public void onFailure(ai.pleos.playground.tts.constant.ErrorType error) {
                    status.accept("Pleos 음성 안내 연결 실패 · " + error.name());
                }
            });
        } catch (IllegalStateException | SecurityException e) { status.accept("Pleos 음성 서비스 연결을 확인해 주세요"); }
    }

    void listen() {
        if (recognition == null || !ready) { status.accept("Pleos 음성 서비스 준비 대기 · 버튼으로 요청할 수 있어요"); return; }
        recognition.request();
    }

    void speak(String message) { if (synthesisReady) synthesis.speak(message, true); }

    void stop() {
        if (recognition != null) recognition.stop();
        if (synthesis != null) synthesis.stop();
    }

    @Override public void close() {
        closed = true;
        ready = false;
        synthesisReady = false;
        if (recognition != null) { recognition.removeListener(results); recognition.release(); }
        if (synthesis != null) synthesis.release();
    }
}
