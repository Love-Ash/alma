package ai.minash.gyeot.companion;

final class CameraObservationGate {
    private static final long MAX_AGE_MS = 1_000;
    private volatile int epoch;

    int epoch() { return epoch; }

    void invalidate() { epoch++; }

    boolean accepts(int submittedEpoch, long arrivedAtMs, long completedAtMs) {
        return submittedEpoch == epoch && isFresh(arrivedAtMs, completedAtMs);
    }

    static boolean isFresh(long arrivedAtMs, long nowMs) {
        long ageMs = nowMs - arrivedAtMs;
        return ageMs >= 0 && ageMs < MAX_AGE_MS;
    }
}
