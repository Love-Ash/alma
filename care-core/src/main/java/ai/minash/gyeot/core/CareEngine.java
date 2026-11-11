package ai.minash.gyeot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CareEngine {
    private static final int ALERT_LIMIT = 32;
    private static final long ALERT_COOLDOWN_MS = 30_000;

    public enum SleepState { UNKNOWN, AWAKE, FALLING_ASLEEP, ASLEEP }
    public enum AlertKind { POSTURE, VOMIT_SUSPECTED, VISIBILITY, SLEEP, WAKE }

    public static final class Config {
        public final long sleepAfterMs;
        public final long wakeAfterMs;
        public final long postureAfterMs;
        public final long visibilityAfterMs;
        public final float closedEyeThreshold;
        public final float postureDegrees;

        public Config(long sleepAfterMs, long wakeAfterMs, long postureAfterMs,
                long visibilityAfterMs, float closedEyeThreshold, float postureDegrees) {
            if (sleepAfterMs <= 0 || wakeAfterMs <= 0 || postureAfterMs <= 0
                    || visibilityAfterMs <= 0) {
                throw new IllegalArgumentException("Durations must be positive");
            }
            if (!Float.isFinite(closedEyeThreshold) || closedEyeThreshold <= 0
                    || closedEyeThreshold >= 1 || !Float.isFinite(postureDegrees)
                    || postureDegrees <= 0 || postureDegrees > 180) {
                throw new IllegalArgumentException("Invalid eye or posture threshold");
            }
            this.sleepAfterMs = sleepAfterMs;
            this.wakeAfterMs = wakeAfterMs;
            this.postureAfterMs = postureAfterMs;
            this.visibilityAfterMs = visibilityAfterMs;
            this.closedEyeThreshold = closedEyeThreshold;
            this.postureDegrees = postureDegrees;
        }
    }

    public static final class Frame {
        public final long timestampMs;
        public final boolean visible;
        public final float leftEyeOpen;
        public final float rightEyeOpen;
        public final float pitchDegrees;
        public final float rollDegrees;

        public Frame(long timestampMs, boolean visible, float leftEyeOpen, float rightEyeOpen,
                float pitchDegrees, float rollDegrees) {
            this.timestampMs = timestampMs;
            this.visible = visible;
            this.leftEyeOpen = leftEyeOpen;
            this.rightEyeOpen = rightEyeOpen;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
        }
    }

    public static final class Alert {
        public final String id;
        public final AlertKind kind;
        public final String message;
        public final long timestampMs;

        private Alert(String id, AlertKind kind, String message, long timestampMs) {
            this.id = id;
            this.kind = kind;
            this.message = message;
            this.timestampMs = timestampMs;
        }
    }

    public static final class Result {
        public final SleepState sleepState;
        public final long totalSleepMs;
        public final long sleepStartedAtMs;
        public final List<Alert> alerts;
        public final boolean requestQuiet;

        private Result(SleepState sleepState, long totalSleepMs, long sleepStartedAtMs,
                List<Alert> alerts, boolean requestQuiet) {
            this.sleepState = sleepState;
            this.totalSleepMs = totalSleepMs;
            this.sleepStartedAtMs = sleepStartedAtMs;
            this.alerts = alerts;
            this.requestQuiet = requestQuiet;
        }
    }

    private final Config config;
    private final List<Alert> alerts = new ArrayList<>();
    private SleepState sleepState = SleepState.UNKNOWN;
    private long clockMs = -1;
    private long lastFrameMs = -1;
    private long closedSinceMs = -1;
    private long wakeSinceMs = -1;
    private long sleepStartedAtMs = -1;
    private long sleepObservedUntilMs = -1;
    private long finishedSleepMs;
    private long hiddenSinceMs = -1;
    private boolean hiddenAlerted;
    private boolean calibrated;
    private float baselinePitch;
    private float baselineRoll;
    private long postureSinceMs = -1;
    private boolean postureAlerted;
    private long lastPostureAlertMs = -1;
    private long lastVomitingAlertMs = -1;
    private long nextAlertId;

    public CareEngine(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized Result accept(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        validateFrame(frame);
        advanceClock(frame.timestampMs);
        expireObservation(frame.timestampMs);
        lastFrameMs = frame.timestampMs;

        if (!frame.visible) {
            loseObservation(frame.timestampMs);
            checkVisibility(frame.timestampMs);
            return result(false);
        }

        hiddenSinceMs = -1;
        hiddenAlerted = false;
        checkPosture(frame);
        if (frame.leftEyeOpen < 0 || frame.rightEyeOpen < 0) {
            finishSleep();
            sleepState = SleepState.UNKNOWN;
            closedSinceMs = -1;
            wakeSinceMs = -1;
            return result(false);
        }

        boolean closed = frame.leftEyeOpen <= config.closedEyeThreshold
                && frame.rightEyeOpen <= config.closedEyeThreshold;
        boolean quiet = false;
        if (closed) {
            wakeSinceMs = -1;
            if (sleepState == SleepState.ASLEEP) {
                sleepObservedUntilMs = frame.timestampMs;
            } else {
                if (closedSinceMs < 0) closedSinceMs = frame.timestampMs;
                sleepState = SleepState.FALLING_ASLEEP;
                if (frame.timestampMs - closedSinceMs >= config.sleepAfterMs) {
                    sleepState = SleepState.ASLEEP;
                    sleepStartedAtMs = closedSinceMs;
                    sleepObservedUntilMs = frame.timestampMs;
                    addAlert(AlertKind.SLEEP, "아이가 잠든 것 같아요.", frame.timestampMs);
                    quiet = true;
                }
            }
        } else {
            closedSinceMs = -1;
            if (sleepState == SleepState.ASLEEP) {
                if (wakeSinceMs < 0) {
                    wakeSinceMs = frame.timestampMs;
                    sleepObservedUntilMs = frame.timestampMs;
                }
                if (frame.timestampMs - wakeSinceMs >= config.wakeAfterMs) {
                    finishSleep();
                    sleepState = SleepState.AWAKE;
                    wakeSinceMs = -1;
                    addAlert(AlertKind.WAKE, "아이가 깨어난 것 같아요.", frame.timestampMs);
                }
            } else {
                sleepState = SleepState.AWAKE;
                wakeSinceMs = -1;
            }
        }
        return result(quiet);
    }

    public synchronized Result snapshot(long nowMs) {
        advanceClock(nowMs);
        expireObservation(nowMs);
        checkVisibility(nowMs);
        return result(false);
    }

    public synchronized Result markVomitingSuspected(long nowMs) {
        advanceClock(nowMs);
        expireObservation(nowMs);
        if (lastVomitingAlertMs < 0 || nowMs - lastVomitingAlertMs >= ALERT_COOLDOWN_MS) {
            addAlert(AlertKind.VOMIT_SUSPECTED,
                    "구토 의심 상황이 수동으로 등록됐어요. 안전하게 정차해 아이를 확인해 주세요.", nowMs);
            lastVomitingAlertMs = nowMs;
        }
        return result(false);
    }

    public synchronized void reset() {
        sleepState = SleepState.UNKNOWN;
        clockMs = -1;
        lastFrameMs = -1;
        closedSinceMs = -1;
        wakeSinceMs = -1;
        sleepStartedAtMs = -1;
        sleepObservedUntilMs = -1;
        finishedSleepMs = 0;
        hiddenSinceMs = -1;
        hiddenAlerted = false;
        calibrated = false;
        postureSinceMs = -1;
        postureAlerted = false;
        lastPostureAlertMs = -1;
        lastVomitingAlertMs = -1;
        alerts.clear();
    }

    private void checkPosture(Frame frame) {
        if (!calibrated) {
            baselinePitch = frame.pitchDegrees;
            baselineRoll = frame.rollDegrees;
            calibrated = true;
        }
        boolean changed = angleDistance(frame.pitchDegrees, baselinePitch) >= config.postureDegrees
                || angleDistance(frame.rollDegrees, baselineRoll) >= config.postureDegrees;
        if (!changed) {
            postureSinceMs = -1;
            postureAlerted = false;
        } else {
            if (postureSinceMs < 0) postureSinceMs = frame.timestampMs;
            if (!postureAlerted && frame.timestampMs - postureSinceMs >= config.postureAfterMs
                    && (lastPostureAlertMs < 0
                    || frame.timestampMs - lastPostureAlertMs >= ALERT_COOLDOWN_MS)) {
                addAlert(AlertKind.POSTURE,
                        "고개가 처음 자세와 크게 달라진 상태가 이어져요. 안전하게 정차한 뒤 확인해 주세요.",
                        frame.timestampMs);
                postureAlerted = true;
                lastPostureAlertMs = frame.timestampMs;
            }
        }
    }

    private void expireObservation(long nowMs) {
        if (lastFrameMs >= 0 && nowMs - lastFrameMs > config.visibilityAfterMs) {
            loseObservation(lastFrameMs);
            checkVisibility(nowMs);
        }
    }

    private void loseObservation(long sinceMs) {
        finishSleep();
        sleepState = SleepState.UNKNOWN;
        closedSinceMs = -1;
        wakeSinceMs = -1;
        postureSinceMs = -1;
        postureAlerted = false;
        if (hiddenSinceMs < 0) hiddenSinceMs = sinceMs;
    }

    private void checkVisibility(long nowMs) {
        if (hiddenSinceMs >= 0 && !hiddenAlerted
                && nowMs - hiddenSinceMs >= config.visibilityAfterMs) {
            addAlert(AlertKind.VISIBILITY,
                    "아이 상태를 확인할 수 없어요. 카메라 위치와 연결을 확인해 주세요.", nowMs);
            hiddenAlerted = true;
        }
    }

    private void finishSleep() {
        if (sleepStartedAtMs >= 0) {
            finishedSleepMs += Math.max(0, sleepObservedUntilMs - sleepStartedAtMs);
            sleepStartedAtMs = -1;
            sleepObservedUntilMs = -1;
        }
    }

    private void advanceClock(long nowMs) {
        if (nowMs < 0 || nowMs < clockMs) {
            throw new IllegalArgumentException("Timestamps must be nonnegative and monotonic");
        }
        clockMs = nowMs;
    }

    private void validateFrame(Frame frame) {
        if (!probability(frame.leftEyeOpen) || !probability(frame.rightEyeOpen)
                || !Float.isFinite(frame.pitchDegrees) || !Float.isFinite(frame.rollDegrees)
                || Math.abs(frame.pitchDegrees) > 180 || Math.abs(frame.rollDegrees) > 180) {
            throw new IllegalArgumentException("Invalid eye probability or head angle");
        }
    }

    private static boolean probability(float value) {
        return Float.isFinite(value) && (value == -1 || (value >= 0 && value <= 1));
    }

    private static float angleDistance(float angle, float baseline) {
        float difference = Math.abs(angle - baseline);
        return Math.min(difference, 360 - difference);
    }

    private void addAlert(AlertKind kind, String message, long timestampMs) {
        alerts.add(new Alert(kind.name() + "-" + ++nextAlertId, kind, message, timestampMs));
        if (alerts.size() > ALERT_LIMIT) alerts.remove(0);
    }

    private Result result(boolean quiet) {
        long activeMs = sleepStartedAtMs < 0 ? 0 : sleepObservedUntilMs - sleepStartedAtMs;
        return new Result(sleepState, finishedSleepMs + Math.max(0, activeMs), sleepStartedAtMs,
                Collections.unmodifiableList(new ArrayList<>(alerts)), quiet);
    }
}
