package ai.minash.gyeot.core;

import ai.minash.gyeot.core.CareEngine.AlertKind;
import ai.minash.gyeot.core.CareEngine.Config;
import ai.minash.gyeot.core.CareEngine.Frame;
import ai.minash.gyeot.core.CareEngine.Interval;
import ai.minash.gyeot.core.CareEngine.PostureState;
import ai.minash.gyeot.core.CareEngine.Result;
import ai.minash.gyeot.core.CareEngine.SleepState;

public final class CareEngineTest {
    private static int assertions;

    public static void main(String[] args) {
        sleepAndWake();
        visibilityStopsSleep();
        staleFramesStopSleep();
        unknownEyesStopSleep();
        tripRecordsObservedIntervals();
        staleAndInvalidFramesRecordGaps();
        recalibrationPreservesTrip();
        blinksDoNotCountAsSleep();
        postureCalibrationAndCooldown();
        postureResetsAfterGaps();
        postureResetsAfterSilentFrameGap();
        postureFrameGapBoundary();
        postureCalibrationRequiresContinuousFrames();
        postureDoesNotCombineDirections();
        unstableReferenceAndYawDoNotTriggerPosture();
        invalidInputDoesNotMutate();
        vomitingIsExplicitAndBounded();
        resetRecalibrates();
        System.out.println("CORE_TESTS_PASSED assertions=" + assertions);
    }

    private static CareEngine engine() {
        return new CareEngine(new Config(2_000, 1_000, 1_000, 1_500, 0.25f, 30));
    }

    private static Frame eyes(long time, float probability) {
        return new Frame(time, true, probability, probability, 0, 0);
    }

    private static Result asleep(CareEngine engine) {
        engine.accept(eyes(0, 1));
        engine.accept(eyes(100, 0.1f));
        engine.accept(eyes(1_100, 0.1f));
        return engine.accept(eyes(2_100, 0.1f));
    }

    private static void sleepAndWake() {
        CareEngine engine = engine();
        Result result = asleep(engine);
        equal(SleepState.ASLEEP, result.sleepState);
        equal(100L, result.sleepStartedAtMs);
        equal(2_000L, result.totalSleepMs);
        check(result.requestQuiet, "Sleep transition requests quiet");
        check(!engine.snapshot(2_200).requestQuiet, "Snapshots never replay quiet commands");
        result = engine.accept(eyes(2_500, 0.1f));
        check(!result.requestQuiet, "Continued sleep does not request quiet twice");
        equal(2_400L, result.totalSleepMs);
        result = engine.accept(eyes(3_000, 1));
        equal(SleepState.ASLEEP, result.sleepState);
        result = engine.accept(eyes(4_000, 1));
        equal(SleepState.AWAKE, result.sleepState);
        equal(2_900L, result.totalSleepMs);
        equal(-1L, result.sleepStartedAtMs);
        equal(1L, count(result, AlertKind.SLEEP));
        equal(1L, count(result, AlertKind.WAKE));
        check(!result.requestQuiet, "Waking never requests a playback action");
        result = engine.accept(eyes(4_500, 0.1f));
        engine.accept(eyes(5_500, 0.1f));
        result = engine.accept(eyes(6_500, 0.1f));
        equal(4_900L, result.totalSleepMs);
        check(result.requestQuiet, "A new sleep episode can request quiet");
    }

    private static void visibilityStopsSleep() {
        CareEngine engine = engine();
        asleep(engine);
        Result result = engine.accept(new Frame(2_500, false, -1, -1, 0, 0));
        equal(SleepState.UNKNOWN, result.sleepState);
        equal(2_000L, result.totalSleepMs);
        equal(0L, count(result, AlertKind.VISIBILITY));
        result = engine.snapshot(4_000);
        equal(1L, count(result, AlertKind.VISIBILITY));
        equal(2_000L, result.totalSleepMs);
        equal(1L, count(engine.snapshot(20_000), AlertKind.VISIBILITY));
        result = engine.accept(eyes(20_001, 0.1f));
        equal(SleepState.FALLING_ASLEEP, result.sleepState);
        equal(2_000L, result.totalSleepMs);
        engine.accept(new Frame(20_002, false, -1, -1, 0, 0));
        result = engine.snapshot(21_502);
        equal(2L, count(result, AlertKind.VISIBILITY));
    }

    private static void staleFramesStopSleep() {
        CareEngine engine = engine();
        asleep(engine);
        Result result = engine.snapshot(3_000);
        equal(SleepState.ASLEEP, result.sleepState);
        equal(2_000L, result.totalSleepMs);
        result = engine.snapshot(3_601);
        equal(SleepState.UNKNOWN, result.sleepState);
        equal(2_000L, result.totalSleepMs);
        equal(1L, count(result, AlertKind.VISIBILITY));
        result = engine.accept(eyes(4_000, 0.1f));
        equal(SleepState.FALLING_ASLEEP, result.sleepState);

        engine = engine();
        engine.accept(eyes(0, 0));
        result = engine.accept(eyes(3_000, 0));
        equal(SleepState.FALLING_ASLEEP, result.sleepState);
        equal(0L, result.totalSleepMs);
        check(!result.requestQuiet, "Unobserved gap cannot satisfy sleep duration");
    }

    private static void unknownEyesStopSleep() {
        CareEngine engine = engine();
        asleep(engine);
        Result result = engine.accept(new Frame(2_500, true, -1, 0.1f, 0, 0));
        equal(SleepState.UNKNOWN, result.sleepState);
        equal(2_000L, result.totalSleepMs);
        engine.accept(new Frame(3_000, true, -1, -1, 0, 0));
        engine.accept(new Frame(4_000, true, -1, -1, 0, 0));
        result = engine.accept(new Frame(5_000, true, -1, -1, 0, 0));
        equal(SleepState.UNKNOWN, result.sleepState);
        equal(2_000L, result.totalSleepMs);
        check(!result.requestQuiet, "Unknown eyes never count as closed eyes");
    }

    private static void tripRecordsObservedIntervals() {
        CareEngine engine = engine();
        engine.beginTrip(50);
        Result result = engine.snapshot(80);
        interval(50, 80, result.currentObservationGap);
        expectInvalid(() -> engine.accept(eyes(79, 0)));
        result = engine.accept(eyes(100, 0));
        interval(50, 100, result.observationGaps.get(0));
        check(result.currentObservationGap == null, "A usable frame closes the initial gap");
        engine.accept(eyes(1_100, 0));
        engine.accept(eyes(2_100, 0));
        Result beforeGap = engine.snapshot(2_200);
        interval(100, 2_100, beforeGap.currentSleepInterval);
        equal(0, beforeGap.sleepIntervals.size());
        equal(2_000, beforeGap.totalSleepMs);

        result = engine.markObservationUnavailable(2_400);
        interval(100, 2_100, result.sleepIntervals.get(0));
        interval(2_100, 2_400, result.currentObservationGap);
        check(result.currentSleepInterval == null, "Background observation loss closes sleep");
        result = engine.snapshot(4_100);
        equal(2_000, result.totalSleepMs);
        equal(1, result.sleepIntervals.size());
        interval(2_100, 4_100, result.currentObservationGap);

        engine.accept(eyes(4_300, 0));
        engine.accept(eyes(5_300, 0));
        result = engine.accept(eyes(6_300, 0));
        equal(2, result.observationGaps.size());
        interval(2_100, 4_300, result.observationGaps.get(1));
        interval(4_300, 6_300, result.currentSleepInterval);
        engine.accept(eyes(6_400, 1));
        result = engine.accept(eyes(7_400, 1));
        equal(2, result.sleepIntervals.size());
        interval(4_300, 6_400, result.sleepIntervals.get(1));
        equal(4_100, result.totalSleepMs);
        equal(0, beforeGap.sleepIntervals.size());
        equal(1, beforeGap.observationGaps.size());
        interval(100, 2_100, beforeGap.currentSleepInterval);

        engine.beginTrip(8_000);
        result = engine.snapshot(8_000);
        equal(0, result.sleepIntervals.size());
        equal(0, result.observationGaps.size());
        equal(0, result.totalSleepMs);
        interval(8_000, 8_000, result.currentObservationGap);
    }

    private static void staleAndInvalidFramesRecordGaps() {
        CareEngine engine = engine();
        asleep(engine);
        check(engine.snapshot(3_600).currentObservationGap == null,
                "A gap is recorded only once the last sample is stale or observation fails");
        Result result = engine.snapshot(3_601);
        interval(2_100, 3_601, result.currentObservationGap);
        result = engine.accept(eyes(4_000, 1));
        interval(2_100, 4_000, result.observationGaps.get(0));
        result = engine.accept(new Frame(4_200, true, -1, 0.1f, 0, 0));
        interval(4_000, 4_200, result.currentObservationGap);
        engine.accept(new Frame(4_300, false, -1, -1, 0, 0));
        result = engine.accept(eyes(4_400, 1));
        equal(2, result.observationGaps.size());
        interval(4_000, 4_400, result.observationGaps.get(1));
        equal(2_000, result.totalSleepMs);
    }

    private static void recalibrationPreservesTrip() {
        CareEngine engine = engine();
        asleep(engine);
        engine.resetPostureReference();
        Result result = engine.accept(new Frame(2_500, true, 0, 0, 60, 0));
        equal(0, count(result, AlertKind.POSTURE));
        interval(100, 2_500, result.currentSleepInterval);
        equal(2_400, result.totalSleepMs);
        result = engine.accept(new Frame(3_500, true, 0, 0, 60, 0));
        equal(0, count(result, AlertKind.POSTURE));
        equal(1, count(result, AlertKind.SLEEP));
        interval(100, 3_500, result.currentSleepInterval);
    }

    private static void blinksDoNotCountAsSleep() {
        CareEngine engine = engine();
        engine.accept(eyes(0, 1));
        equal(SleepState.FALLING_ASLEEP, engine.accept(eyes(100, 0)).sleepState);
        equal(SleepState.AWAKE, engine.accept(eyes(200, 1)).sleepState);
        engine.accept(eyes(1_000, 0));
        engine.accept(eyes(2_000, 0));
        Result result = engine.accept(eyes(2_999, 0));
        equal(SleepState.FALLING_ASLEEP, result.sleepState);
        equal(0L, result.totalSleepMs);
        check(!result.requestQuiet, "Threshold is not satisfied early");
    }

    private static void postureCalibrationAndCooldown() {
        CareEngine engine = engine();
        engine.accept(new Frame(0, true, 1, 1, 25, 15));
        engine.accept(new Frame(400, true, 1, 1, 25, 15));
        engine.accept(new Frame(1_000, true, 1, 1, 25, 15));
        equal(0L, count(engine.snapshot(1_000), AlertKind.POSTURE));
        engine.accept(new Frame(1_100, true, 1, 1, 60, 15));
        Result result = engine.accept(new Frame(2_100, true, 1, 1, 60, 15));
        equal(1L, count(result, AlertKind.POSTURE));
        check(!result.alerts.get(0).message.contains("호흡"), "Posture alert is not a breathing diagnosis");
        check(result.alerts.get(0).message.contains("위로"), "Positive pitch is labeled upward");
        engine.accept(new Frame(3_000, true, 1, 1, 60, 15));
        equal(1L, count(engine.snapshot(3_000), AlertKind.POSTURE));
        engine.accept(new Frame(3_100, true, 1, 1, 25, 15));
        engine.accept(new Frame(3_200, true, 1, 1, 60, 15));
        result = engine.accept(new Frame(4_200, true, 1, 1, 60, 15));
        equal(1L, count(result, AlertKind.POSTURE));
        for (long time = 5_200; time <= 32_200; time += 1_000) {
            result = engine.accept(new Frame(time, true, 1, 1, 60, 15));
        }
        equal(2L, count(result, AlertKind.POSTURE));
        for (long time = 33_200; time <= 70_200; time += 1_000) {
            result = engine.accept(new Frame(time, true, 1, 1, 60, 15));
        }
        equal(2L, count(result, AlertKind.POSTURE));

        engine = engine();
        engine.accept(new Frame(0, true, 1, 1, 179, 0));
        engine.accept(new Frame(300, true, 1, 1, -179, 0));
        equal(PostureState.OBSERVED, engine.accept(new Frame(700, true, 1, 1, -179, 0)).postureState);
        engine.accept(new Frame(1_000, true, 1, 1, -178, 0));
        equal(0L, count(engine.accept(new Frame(2_000, true, 1, 1, -178, 0)), AlertKind.POSTURE));
    }

    private static void postureResetsAfterGaps() {
        CareEngine engine = engine();
        engine.accept(eyes(0, 1));
        engine.accept(eyes(300, 1));
        engine.accept(eyes(600, 1));
        engine.accept(new Frame(700, true, 1, 1, -40, 0));
        equal(PostureState.UNOBSERVABLE,
                engine.accept(new Frame(800, false, -1, -1, 0, 0)).postureState);
        Result result = engine.accept(new Frame(1_800, true, 1, 1, -40, 0));
        equal(0L, count(result, AlertKind.POSTURE));
        result = engine.accept(new Frame(2_800, true, 1, 1, -40, 0));
        equal(1L, count(result, AlertKind.POSTURE));
        check(result.alerts.get(0).message.contains("앞으로"), "Negative pitch is labeled downward");
        engine.accept(new Frame(3_000, false, -1, -1, 0, 0));
        for (long time = 4_000; time <= 35_000; time += 1_000) {
            result = engine.accept(new Frame(time, true, 1, 1, -40, 0));
        }
        equal(1L, count(result, AlertKind.POSTURE));
    }

    private static void postureResetsAfterSilentFrameGap() {
        CareEngine engine = new CareEngine(new Config(8_000, 2_000, 2_500, 2_000, 0.28f, 28f));
        for (long time = 0; time <= 750; time += 250) engine.accept(eyes(time, 1));
        for (long time = 1_000; time <= 1_500; time += 250) {
            engine.accept(new Frame(time, true, 1, 1, -35, 0));
        }
        Result result = engine.accept(new Frame(3_000, true, 1, 1, -35, 0));
        equal(PostureState.OBSERVED, result.postureState);
        for (long time = 3_250; time < 5_500; time += 250) {
            result = engine.accept(new Frame(time, true, 1, 1, -35, 0));
            equal(0L, count(result, AlertKind.POSTURE));
        }
        result = engine.accept(new Frame(5_500, true, 1, 1, -35, 0));
        equal(1L, count(result, AlertKind.POSTURE));
        equal(5_500L, result.alerts.get(0).timestampMs);

        for (long time = 7_000; time <= 37_000; time += 1_000) {
            result = engine.accept(new Frame(time, true, 1, 1, -35, 0));
        }
        equal(1L, count(result, AlertKind.POSTURE));
    }

    private static void postureFrameGapBoundary() {
        for (long gap : new long[] {1_000, 1_001}) {
            CareEngine engine = engine();
            engine.accept(eyes(0, 1));
            engine.accept(eyes(300, 1));
            engine.accept(eyes(600, 1));
            engine.accept(new Frame(700, true, 1, 1, -40, 0));
            Result result = engine.snapshot(700 + gap);
            equal(gap == 1_000 ? PostureState.OBSERVED : PostureState.UNOBSERVABLE, result.postureState);
            equal(SleepState.AWAKE, result.sleepState);
            equal(0L, count(result, AlertKind.VISIBILITY));
            result = engine.accept(new Frame(700 + gap, true, 1, 1, -40, 0));
            equal(gap == 1_000 ? 1L : 0L, count(result, AlertKind.POSTURE));
        }
    }

    private static void postureCalibrationRequiresContinuousFrames() {
        CareEngine engine = engine();
        engine.accept(eyes(0, 1));
        engine.accept(eyes(300, 1));
        equal(PostureState.CALIBRATING, engine.accept(eyes(1_301, 1)).postureState);
        equal(PostureState.CALIBRATING, engine.accept(eyes(1_601, 1)).postureState);
        equal(PostureState.OBSERVED, engine.accept(eyes(1_901, 1)).postureState);
    }

    private static void postureDoesNotCombineDirections() {
        CareEngine engine = engine();
        engine.accept(eyes(0, 1));
        engine.accept(eyes(300, 1));
        equal(PostureState.OBSERVED, engine.accept(eyes(600, 1)).postureState);
        engine.accept(new Frame(700, true, 1, 1, -40, 0));
        engine.accept(new Frame(1_300, true, 1, 1, 0, 40));
        equal(0L, count(engine.accept(new Frame(1_700, true, 1, 1, 0, 40)), AlertKind.POSTURE));
        Result result = engine.accept(new Frame(2_300, true, 1, 1, 0, 40));
        equal(1L, count(result, AlertKind.POSTURE));
        check(result.alerts.get(0).message.contains("옆으로"), "Roll is labeled sideways");
    }

    private static void unstableReferenceAndYawDoNotTriggerPosture() {
        CareEngine engine = engine();
        equal(PostureState.UNOBSERVABLE,
                engine.accept(new Frame(0, true, 1, 1, 0, 0, 55)).postureState);
        equal(PostureState.CALIBRATING, engine.accept(eyes(100, 1)).postureState);
        engine.accept(new Frame(400, true, 1, 1, -40, 0));
        engine.accept(eyes(700, 1));
        equal(PostureState.CALIBRATING, engine.accept(eyes(1_200, 1)).postureState);
        equal(PostureState.OBSERVED, engine.accept(eyes(1_300, 1)).postureState);
        engine.accept(new Frame(1_400, true, 1, 1, -40, 0));
        Result result = engine.accept(new Frame(2_400, true, 1, 1, -40, 0, 55));
        equal(PostureState.UNOBSERVABLE, result.postureState);
        equal(SleepState.AWAKE, result.sleepState);
        equal(0L, count(result, AlertKind.POSTURE));
        equal(0L, count(engine.accept(new Frame(2_500, true, 1, 1, -40, 0)), AlertKind.POSTURE));
        equal(1L, count(engine.accept(new Frame(3_500, true, 1, 1, -40, 0)), AlertKind.POSTURE));
    }

    private static void invalidInputDoesNotMutate() {
        CareEngine engine = engine();
        engine.accept(eyes(1_000, 1));
        expectInvalid(() -> engine.accept(eyes(999, 0)));
        expectInvalid(() -> engine.snapshot(999));
        expectInvalid(() -> engine.markVomitingSuspected(-1));
        expectInvalid(() -> engine.accept(eyes(2_000, Float.NaN)));
        expectInvalid(() -> engine.accept(eyes(2_000, Float.POSITIVE_INFINITY)));
        expectInvalid(() -> engine.accept(eyes(2_000, -0.5f)));
        expectInvalid(() -> engine.accept(eyes(2_000, 1.1f)));
        expectInvalid(() -> engine.accept(new Frame(2_000, true, 1, 1, 181, 0)));
        expectInvalid(() -> engine.accept(new Frame(2_000, true, 1, 1, 0, Float.NaN)));
        expectInvalid(() -> engine.accept(new Frame(2_000, true, 1, 1, 0, 0, Float.NaN)));
        equal(SleepState.AWAKE, engine.snapshot(1_000).sleepState);
        expectInvalid(() -> new Config(0, 1, 1, 1, 0.2f, 30));
        expectInvalid(() -> new Config(1, 1, 1, 1, Float.NaN, 30));
        expectInvalid(() -> new Config(1, 1, 1, 1, 0.2f, 0));
    }

    private static void vomitingIsExplicitAndBounded() {
        CareEngine engine = engine();
        Result result = engine.markVomitingSuspected(0);
        equal(1L, count(result, AlertKind.VOMIT_SUSPECTED));
        check(result.alerts.get(0).message.contains("수동"), "Manual report source is explicit");
        result = engine.markVomitingSuspected(1_000);
        equal(1L, count(result, AlertKind.VOMIT_SUSPECTED));
        for (long time = 30_000; time <= 1_200_000; time += 30_000) {
            result = engine.markVomitingSuspected(time);
        }
        equal(32L, result.alerts.size());
        try {
            result.alerts.clear();
            throw new AssertionError("Alert snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertions++;
        }
        String lastId = result.alerts.get(result.alerts.size() - 1).id;
        engine.reset();
        result = engine.markVomitingSuspected(0);
        check(!lastId.equals(result.alerts.get(0).id), "Reset does not reuse alert IDs");
    }

    private static void resetRecalibrates() {
        CareEngine engine = engine();
        asleep(engine);
        engine.reset();
        Result result = engine.accept(new Frame(0, true, 1, 1, 60, 0));
        equal(SleepState.AWAKE, result.sleepState);
        equal(0L, result.totalSleepMs);
        equal(0L, result.alerts.size());
        result = engine.accept(new Frame(1_000, true, 1, 1, 60, 0));
        equal(0L, count(result, AlertKind.POSTURE));
    }

    private static long count(Result result, AlertKind kind) {
        return result.alerts.stream().filter(alert -> alert.kind == kind).count();
    }

    private static void interval(long startMs, long endMs, Interval actual) {
        check(actual != null, "Expected an interval");
        equal(startMs, actual.startMs);
        equal(endMs, actual.endMs);
    }

    private static void equal(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void equal(long expected, long actual) {
        assertions++;
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expectInvalid(Runnable action) {
        assertions++;
        try {
            action.run();
            throw new AssertionError("Expected invalid input rejection");
        } catch (IllegalArgumentException expected) {
            // Expected invalid sensor input.
        }
    }
}
