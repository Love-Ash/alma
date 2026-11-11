package ai.minash.gyeot.vehicle;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.Gear;
import ai.pleos.playground.vehicle.constant.control.VehicleGear;
import ai.pleos.playground.vehicle.listener.GearSelectionListener;
import java.util.function.BiConsumer;

final class CarGate implements AutoCloseable {
    private Car car;
    private CarUxRestrictionsManager manager;
    private final BiConsumer<Boolean, String> listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed;
    private boolean uxAllowsPreview;
    private Vehicle vehicle;
    private Gear gear;
    private GearSelectionListener gears;
    private int connection;
    private boolean parkedGear;
    private String gearLabel = "기어 상태 확인 중";

    CarGate(Context context, BiConsumer<Boolean, String> listener) {
        this.listener = listener;
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            listener.accept(false, "일반 Android · 차량 제한 상태 없음");
            return;
        }
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                    (service, ready) -> main.post(() -> onService(context, service, ready)));
            if (car == null) listener.accept(false, "차량 서비스 연결 대기");
        } catch (IllegalStateException | IllegalArgumentException | SecurityException e) {
            listener.accept(false, "차량 연결 불가 · 화면 확인 잠김");
        }
    }

    private void onService(Context context, Car service, boolean ready) {
        if (closed) return;
        int ticket = ++connection;
        clearSources();
        publish();
        if (!ready) return;
        try {
            manager = service.getCarManager(CarUxRestrictionsManager.class);
            if (manager == null) return;
            manager.registerListener(state -> main.post(() -> {
                if (ticket == connection) update(state);
            }));
            update(manager.getCurrentCarUxRestrictions());
            vehicle = new Vehicle(context);
            vehicle.initialize();
            gear = vehicle.getGear();
            boolean[] subscribed = {false};
            gears = new GearSelectionListener() {
                @Override public void onSelected(VehicleGear selection) { receive(selection); }
                @Override public void onFailed(Exception error) { receive(null); }

                private void receive(VehicleGear selection) {
                    main.post(() -> {
                        if (ticket != connection) return;
                        subscribed[0] = true;
                        updateGear(selection);
                    });
                }
            };
            gear.registerGearSelection(gears);
            gear.getCurrentGearSelectionState(selection -> {
                main.post(() -> { if (ticket == connection && !subscribed[0]) updateGear(selection); });
                return kotlin.Unit.INSTANCE;
            }, error -> {
                main.post(() -> { if (ticket == connection && !subscribed[0]) updateGear(null); });
                return kotlin.Unit.INSTANCE;
            });
        } catch (IllegalStateException | IllegalArgumentException | SecurityException e) {
            ++connection;
            clearSources();
            listener.accept(false, "차량 제한 상태 확인 불가");
        }
    }

    private void updateGear(VehicleGear selection) {
        parkedGear = selection == VehicleGear.GEAR_PARK;
        gearLabel = selection == null ? "기어 상태 확인 불가" : selection.name();
        publish();
    }

    private void update(CarUxRestrictions state) {
        if (closed) return;
        uxAllowsPreview = state != null && !state.isRequiresDistractionOptimization()
                && (state.getActiveRestrictions() & CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO) == 0;
        publish();
    }

    private void publish() {
        boolean allowed = uxAllowsPreview && parkedGear;
        listener.accept(allowed, allowed ? "P · 주차 상태 확인 · 화면 확인 가능" : gearLabel + " · 화면 확인 잠김");
    }

    private void clearSources() {
        uxAllowsPreview = false;
        parkedGear = false;
        gearLabel = "기어 상태 확인 중";
        if (manager != null) manager.unregisterListener();
        if (gear != null && gears != null) gear.unregisterGearSelection(gears);
        if (vehicle != null) vehicle.release();
        manager = null;
        gear = null;
        gears = null;
        vehicle = null;
    }

    @Override public void close() {
        closed = true;
        ++connection;
        clearSources();
        if (car != null) car.disconnect();
        listener.accept(false, "차량 연결 종료");
    }
}
