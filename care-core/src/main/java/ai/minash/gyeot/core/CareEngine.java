package ai.minash.gyeot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CareEngine {
    private static final int ALERT_LIMIT = 32;
    private static final long ALERT_COOLDOWN_MS = 30_000;
    private static final long POSTURE_REFERENCE_MS = 600;
    private static final long POSTURE_FRAME_GAP_MS = 1_000;
    private static final float POSTURE_REFERENCE_JITTER_DEGREES = 6f;
    private static final float POSTURE_MAX_YAW_DEGREES = 25f;

    public enum SleepState { UNKNOWN, AWAKE, FALLING_ASLEEP, ASLEEP }
    public enum AlertKind { POSTURE, VOMIT_SUSPECTED, VISIBILITY, SLEEP, WAKE }
    public enum PostureState { UNOBSERVABLE, CALIBRATING, OBSERVED }
    private enum PostureDirection { DOWN, UP, SIDE }

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
        public final float yawDegrees;

        public Frame(long timestampMs, boolean visible, float leftEyeOpen, float rightEyeOpen,
                float pitchDegrees, float rollDegrees) {
            this(timestampMs, visible, leftEyeOpen, rightEyeOpen, pitchDegrees, rollDegrees, 0);
        }

        public Frame(long timestampMs, boolean visible, float leftEyeOpen, float rightEyeOpen,
                float pitchDegrees, float rollDegrees, float yawDegrees) {
            this.timestampMs = timestampMs;
            this.visible = visible;
            this.leftEyeOpen = leftEyeOpen;
            this.rightEyeOpen = rightEyeOpen;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            this.yawDegrees = yawDegrees;
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

    public static final class Interval {
        public final long startMs;
        public final long endMs;

        private Interval(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public static final class Result {
        public final SleepState sleepState;
        public final long totalSleepMs;
        public final long sleepStartedAtMs;
        public final List<Alert> alerts;
        public final boolean requestQuiet;
        public final List<Interval> sleepIntervals;
        public final List<Interval> observationGaps;
        public final Interval currentSleepInterval;
        public final Interval currentObservationGap;
        public final PostureState postureState;

        private Result(SleepState sleepState, long totalSleepMs, long sleepStartedAtMs,
                List<Alert> alerts, boolean requestQuiet, List<Interval> sleepIntervals,
                List<Interval> observationGaps, Interval currentSleepInterval,
                Interval currentObservationGap, PostureState postureState) {
            this.sleepState = sleepState;
            this.totalSleepMs = totalSleepMs;
            this.sleepStartedAtMs = sleepStartedAtMs;
            this.alerts = alerts;
            this.requestQuiet = requestQuiet;
            this.sleepIntervals = sleepIntervals;
            this.observationGaps = observationGaps;
            this.currentSleepInterval = currentSleepInterval;
            this.currentObservationGap = currentObservationGap;
            this.postureState = postureState;
        }
    }

    private final Config config;
    private final List<Alert> alerts = new ArrayList<>();
    private final List<Interval> sleepIntervals = new ArrayList<>();
    private final List<Interval> observationGaps = new ArrayList<>();
    private SleepState sleepState = SleepState.UNKNOWN;
    private long clockMs = -1;
    private long lastFrameMs = -1;
    private long lastUsableFrameMs = -1;
    private long gapStartedAtMs = -1;
    private long closedSinceMs = -1;
    private long wakeSinceMs = -1;
    private long sleepStartedAtMs = -1;
    private long sleepObservedUntilMs = -1;
    private long finishedSleepMs;
    private long hiddenSinceMs = -1;
    private boolean hiddenAlerted;
    private boolean calibrated;
    private PostureState postureState = PostureState.UNOBSERVABLE;
    private long referenceSinceMs = -1;
    private int referenceSamples;
    private float baselinePitch;
    private float baselineRoll;
    private float referenceYaw;
    private long postureSinceMs = -1;
    private PostureDirection postureDirection;
    private boolean postureAlerted;
    private long lastPostureAlertMs = -1;
    private long lastVomitingAlertMs = -1;
    private long nextAlertId;

    public CareEngine(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized void beginTrip(long nowMs) {
        if (nowMs < 0) throw new IllegalArgumentException("Trip start must be nonnegative");
        reset();
        clockMs = nowMs;
        gapStartedAtMs = nowMs;
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
            startGap(frame.timestampMs);
            sleepState = SleepState.UNKNOWN;
            closedSinceMs = -1;
            wakeSinceMs = -1;
            return result(false);
        }

        finishGap(frame.timestampMs);
        lastUsableFrameMs = frame.timestampMs;
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

    public synchronized Result markObservationUnavailable(long nowMs) {
        advanceClock(nowMs);
        expireObservation(nowMs);
        loseObservation(nowMs);
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

    public synchronized void resetPostureReference() {
        calibrated = false;
        suspendPosture();
        postureAlerted = false;
    }

    public synchronized void reset() {
        sleepState = SleepState.UNKNOWN;
        clockMs = -1;
        lastFrameMs = -1;
        lastUsableFrameMs = -1;
        gapStartedAtMs = -1;
        closedSinceMs = -1;
        wakeSinceMs = -1;
        sleepStartedAtMs = -1;
        sleepObservedUntilMs = -1;
        finishedSleepMs = 0;
        hiddenSinceMs = -1;
        hiddenAlerted = false;
        resetPostureReference();
        lastPostureAlertMs = -1;
        lastVomitingAlertMs = -1;
        alerts.clear();
        sleepIntervals.clear();
        observationGaps.clear();
    }

    private void checkPosture(Frame frame) {
        if (Math.abs(frame.yawDegrees) > POSTURE_MAX_YAW_DEGREES) {
            suspendPosture();
            return;
        }
        if (!calibrated) {
            updatePostureReference(frame);
            return;
        }
        postureState = PostureState.OBSERVED;
        PostureDirection direction = postureDirection(frame);
        if (direction == null) {
            postureSinceMs = -1;
            postureDirection = null;
            postureAlerted = false;
            return;
        }
        if (direction != postureDirection) {
            postureDirection = direction;
            postureSinceMs = frame.timestampMs;
        }
        if (!postureAlerted && frame.timestampMs - postureSinceMs >= config.postureAfterMs
                && (lastPostureAlertMs < 0
                || frame.timestampMs - lastPostureAlertMs >= ALERT_COOLDOWN_MS)) {
            String message = switch (direction) {
                case DOWN -> "고개가 앞으로 크게 숙여진 상태가 이어져요.";
                case UP -> "고개가 위로 크게 젖혀진 상태가 이어져요.";
                case SIDE -> "고개가 옆으로 크게 기운 상태가 이어져요.";
            };
            addAlert(AlertKind.POSTURE, message + " 안전하게 정차한 뒤 확인해 주세요.",
                    frame.timestampMs);
            postureAlerted = true;
            lastPostureAlertMs = frame.timestampMs;
        }
    }

    private void updatePostureReference(Frame frame) {
        postureState = PostureState.CALIBRATING;
        if (referenceSinceMs < 0
                || angleDistance(frame.pitchDegrees, baselinePitch) > POSTURE_REFERENCE_JITTER_DEGREES
                || angleDistance(frame.rollDegrees, baselineRoll) > POSTURE_REFERENCE_JITTER_DEGREES
                || angleDistance(frame.yawDegrees, referenceYaw) > POSTURE_REFERENCE_JITTER_DEGREES) {
            referenceSinceMs = frame.timestampMs;
            referenceSamples = 1;
            baselinePitch = frame.pitchDegrees;
            baselineRoll = frame.rollDegrees;
            referenceYaw = frame.yawDegrees;
            return;
        }
        referenceSamples++;
        if (referenceSamples >= 3 && frame.timestampMs - referenceSinceMs >= POSTURE_REFERENCE_MS) {
            calibrated = true;
            postureState = PostureState.OBSERVED;
        }
    }

    private PostureDirection postureDirection(Frame frame) {
        float pitch = angleDifference(frame.pitchDegrees, baselinePitch);
        if (pitch <= -config.postureDegrees) return PostureDirection.DOWN;
        if (pitch >= config.postureDegrees) return PostureDirection.UP;
        if (angleDistance(frame.rollDegrees, baselineRoll) >= config.postureDegrees) {
            return PostureDirection.SIDE;
        }
        return null;
    }

    private void suspendPosture() {
        postureState = PostureState.UNOBSERVABLE;
        referenceSinceMs = -1;
        referenceSamples = 0;
        postureSinceMs = -1;
        postureDirection = null;
    }

    private void expireObservation(long nowMs) {
        if (lastFrameMs < 0) return;
        long frameGapMs = nowMs - lastFrameMs;
        if (frameGapMs > config.visibilityAfterMs) {
            loseObservation(lastFrameMs);
            checkVisibility(nowMs);
        } else if (frameGapMs > POSTURE_FRAME_GAP_MS) {
            suspendPosture();
        }
    }

    private void loseObservation(long sinceMs) {
        finishSleep();
        startGap(sinceMs);
        sleepState = SleepState.UNKNOWN;
        closedSinceMs = -1;
        wakeSinceMs = -1;
        suspendPosture();
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
            sleepIntervals.add(new Interval(sleepStartedAtMs, sleepObservedUntilMs));
            finishedSleepMs += sleepObservedUntilMs - sleepStartedAtMs;
            sleepStartedAtMs = -1;
            sleepObservedUntilMs = -1;
        }
    }

    private void startGap(long sinceMs) {
        if (gapStartedAtMs < 0) {
            gapStartedAtMs = lastUsableFrameMs >= 0 ? lastUsableFrameMs : sinceMs;
        }
    }

    private void finishGap(long endMs) {
        if (gapStartedAtMs >= 0 && endMs > gapStartedAtMs) {
            observationGaps.add(new Interval(gapStartedAtMs, endMs));
        }
        gapStartedAtMs = -1;
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
                || !Float.isFinite(frame.yawDegrees)
                || Math.abs(frame.pitchDegrees) > 180 || Math.abs(frame.rollDegrees) > 180
                || Math.abs(frame.yawDegrees) > 180) {
            throw new IllegalArgumentException("Invalid eye probability or head angle");
        }
    }

    private static boolean probability(float value) {
        return Float.isFinite(value) && (value == -1 || (value >= 0 && value <= 1));
    }

    private static float angleDistance(float angle, float baseline) {
        return Math.abs(angleDifference(angle, baseline));
    }

    private static float angleDifference(float angle, float baseline) {
        float difference = angle - baseline;
        if (difference > 180) return difference - 360;
        if (difference < -180) return difference + 360;
        return difference;
    }

    private void addAlert(AlertKind kind, String message, long timestampMs) {
        alerts.add(new Alert(kind.name() + "-" + ++nextAlertId, kind, message, timestampMs));
        if (alerts.size() > ALERT_LIMIT) alerts.remove(0);
    }

    private Result result(boolean quiet) {
        Interval currentSleep = sleepStartedAtMs < 0 ? null
                : new Interval(sleepStartedAtMs, sleepObservedUntilMs);
        Interval currentGap = gapStartedAtMs < 0 ? null : new Interval(gapStartedAtMs, clockMs);
        long activeMs = currentSleep == null ? 0 : currentSleep.endMs - currentSleep.startMs;
        return new Result(sleepState, finishedSleepMs + activeMs, sleepStartedAtMs,
                Collections.unmodifiableList(new ArrayList<>(alerts)), quiet,
                Collections.unmodifiableList(new ArrayList<>(sleepIntervals)),
                Collections.unmodifiableList(new ArrayList<>(observationGaps)), currentSleep, currentGap,
                postureState);
    }
}
