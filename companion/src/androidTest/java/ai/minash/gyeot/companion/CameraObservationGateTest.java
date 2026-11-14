package ai.minash.gyeot.companion;

public final class CameraObservationGateTest {
    public static void main(String[] args) {
        CameraObservationGate gate = new CameraObservationGate();
        int beforeReset = gate.epoch();
        require(gate.accepts(beforeReset, 1_000, 1_000), "Immediate result");
        require(gate.accepts(beforeReset, 1_000, 1_999), "999 ms result");
        require(!gate.accepts(beforeReset, 1_000, 2_000), "1,000 ms result");
        require(!gate.accepts(beforeReset, 1_000, 2_001), "Expired result");
        require(!gate.accepts(beforeReset, 1_000, 999), "Completion precedes analyzer arrival");

        gate.invalidate();
        require(!gate.accepts(beforeReset, 1_999, 2_000), "Pending result from previous observation");
        int afterReset = gate.epoch();
        require(gate.accepts(afterReset, 2_000, 2_001), "Fresh result after reset");
        System.out.println("CAMERA_OBSERVATION_GATE_CHECKS_PASSED checks=7");
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
