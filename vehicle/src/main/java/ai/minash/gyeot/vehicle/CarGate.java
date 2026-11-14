package ai.minash.gyeot.vehicle;

import android.car.Car;
import android.car.VehicleGear;
import android.car.VehiclePropertyIds;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.function.BiConsumer;

final class CarGate implements AutoCloseable {
    enum Mode { PARKED, DRIVE, UNAVAILABLE }

    private static final String TAG = "GyeotCarGate";
    private static final int GEAR_SELECTION = VehiclePropertyIds.GEAR_SELECTION;
    private static final int GLOBAL_AREA = 0;
    private Car car;
    private CarUxRestrictionsManager manager;
    private CarPropertyManager properties;
    private CarPropertyManager.CarPropertyEventCallback gearCallback;
    private final BiConsumer<Mode, String> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed;
    private boolean uxStateKnown;
    private boolean uxAllowsPreview;
    private int connection;
    private int selection = VehicleGear.GEAR_UNKNOWN;
    private long gearTimestamp;

    CarGate(Context context, BiConsumer<Mode, String> listener) {
        this.listener = listener;
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            listener.accept(Mode.UNAVAILABLE, "일반 Android · 차량 제한 상태 없음");
            return;
        }
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (service, ready) -> main.post(() -> onService(service, ready)));
            if (car == null) listener.accept(Mode.UNAVAILABLE, "차량 서비스 연결 대기");
        } catch (IllegalStateException | IllegalArgumentException | SecurityException e) {
            listener.accept(Mode.UNAVAILABLE, "차량 연결 불가 · 화면 확인 잠김");
        }
    }

    private void onService(Car service, boolean ready) {
        if (closed || service != car) return;
        int ticket = ++connection;
        clearSources();
        publish();
        if (!ready) return;
        try {
            CarUxRestrictionsManager restrictions = service.getCarManager(CarUxRestrictionsManager.class);
            if (restrictions == null) return;
            restrictions.registerListener(state -> main.post(() -> {
                if (ticket == connection) update(state);
            }));
            manager = restrictions;
            update(restrictions.getCurrentCarUxRestrictions());
            observeGear(service, ticket);
        } catch (RuntimeException e) {
            ++connection;
            clearSources();
            Log.w(TAG, "Could not observe vehicle restrictions or gear", e);
            listener.accept(Mode.UNAVAILABLE, "차량 제한 상태 확인 불가");
        }
    }

    @SuppressWarnings("deprecation")
    private void observeGear(Car service, int ticket) {
        CarPropertyManager source = service.getCarManager(CarPropertyManager.class);
        if (source == null) return;
        CarPropertyManager.CarPropertyEventCallback callback = new CarPropertyManager.CarPropertyEventCallback() {
            @Override public void onChangeEvent(CarPropertyValue value) {
                main.post(() -> {
                    if (ticket == connection && gearCallback == this) updateGear(value);
                });
            }

            @Override public void onErrorEvent(int propertyId, int areaId) {
                if (propertyId != GEAR_SELECTION || areaId != GLOBAL_AREA) return;
                main.post(() -> {
                    if (ticket == connection && gearCallback == this) updateGear(null);
                });
            }
        };
        // Android 14 delivers the current value as the first subscription event.
        if (source.registerCallback(callback, GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
            properties = source;
            gearCallback = callback;
        } else {
            Log.w(TAG, "Could not subscribe to gear selection");
        }
    }

    private void updateGear(CarPropertyValue<?> value) {
        if (value == null) {
            selection = VehicleGear.GEAR_UNKNOWN;
            publish();
            return;
        }
        if (value.getPropertyId() != GEAR_SELECTION || value.getAreaId() != GLOBAL_AREA) return;
        long timestamp = value.getTimestamp();
        if (timestamp > 0 && timestamp < gearTimestamp) return;
        int next = value.getStatus() == CarPropertyValue.STATUS_AVAILABLE && value.getValue() instanceof Integer gear
                ? gear : VehicleGear.GEAR_UNKNOWN;
        if (timestamp <= 0 || (timestamp == gearTimestamp && next != selection)) {
            next = VehicleGear.GEAR_UNKNOWN;
        }
        gearTimestamp = Math.max(gearTimestamp, timestamp);
        selection = next;
        publish();
    }

    private void update(CarUxRestrictions state) {
        if (closed) return;
        uxStateKnown = state != null;
        uxAllowsPreview = state != null && !state.isRequiresDistractionOptimization()
                && (state.getActiveRestrictions() & CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO) == 0;
        publish();
    }

    private void publish() {
        Mode mode = Mode.UNAVAILABLE;
        if (uxAllowsPreview && selection == VehicleGear.GEAR_PARK) mode = Mode.PARKED;
        else if (uxStateKnown && selection == VehicleGear.GEAR_DRIVE) mode = Mode.DRIVE;
        listener.accept(mode, mode == Mode.PARKED
                ? "P · 주차 상태 확인 · 화면 확인 가능" : gearLabel() + " · 화면 확인 잠김");
    }

    private String gearLabel() {
        return switch (selection) {
            case VehicleGear.GEAR_PARK -> "GEAR_PARK";
            case VehicleGear.GEAR_DRIVE -> "GEAR_DRIVE";
            case VehicleGear.GEAR_NEUTRAL -> "GEAR_NEUTRAL";
            case VehicleGear.GEAR_REVERSE -> "GEAR_REVERSE";
            case VehicleGear.GEAR_FIRST -> "GEAR_1";
            case VehicleGear.GEAR_SECOND -> "GEAR_2";
            case VehicleGear.GEAR_THIRD -> "GEAR_3";
            case VehicleGear.GEAR_FOURTH -> "GEAR_4";
            case VehicleGear.GEAR_FIFTH -> "GEAR_5";
            case VehicleGear.GEAR_SIXTH -> "GEAR_6";
            case VehicleGear.GEAR_SEVENTH -> "GEAR_7";
            case VehicleGear.GEAR_EIGHTH -> "GEAR_8";
            case VehicleGear.GEAR_NINTH -> "GEAR_9";
            default -> "기어 상태 확인 불가";
        };
    }

    @SuppressWarnings("deprecation")
    private void clearSources() {
        uxStateKnown = false;
        uxAllowsPreview = false;
        selection = VehicleGear.GEAR_UNKNOWN;
        gearTimestamp = 0;
        try {
            if (manager != null) manager.unregisterListener();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not remove vehicle restrictions listener", e);
        }
        try {
            if (properties != null && gearCallback != null) properties.unregisterCallback(gearCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not remove gear selection listener", e);
        }
        manager = null;
        properties = null;
        gearCallback = null;
    }

    @Override public void close() {
        closed = true;
        ++connection;
        clearSources();
        if (car != null) car.disconnect();
        listener.accept(Mode.UNAVAILABLE, "차량 연결 종료");
    }
}
