package ai.minash.gyeot.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.ServiceCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import java.util.function.Consumer;

public final class ObservationService extends Service implements LifecycleOwner {
    static final String ACTION_START = "ai.minash.gyeot.companion.START_OBSERVATION";
    private static final String ACTION_STOP = "ai.minash.gyeot.companion.STOP_OBSERVATION";
    private static final String CHANNEL = "camera_observation";
    private static final int NOTIFICATION_ID = 31;

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final LocalBinder binder = new LocalBinder();
    private Consumer<Boolean> observer;
    private PowerManager.WakeLock wakeLock;
    private boolean observing;

    final class LocalBinder extends Binder {
        ObservationService service() { return ObservationService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.screen_off_observation_notification), NotificationManager.IMPORTANCE_LOW));
    }

    @Override public Lifecycle getLifecycle() { return lifecycle; }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopObservation();
            return START_NOT_STICKY;
        }
        if (observing) return START_NOT_STICKY;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } catch (SecurityException | IllegalStateException unavailable) {
            stopObservation();
            return START_NOT_STICKY;
        }
        if (observer == null || !canShowNotification(this)
                || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopObservation();
            return START_NOT_STICKY;
        }
        observing = true;
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        observer.accept(true);
        return START_NOT_STICKY;
    }

    static boolean canShowNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    void setObserver(Consumer<Boolean> observer) { this.observer = observer; }

    boolean isObserving() { return observing; }

    @SuppressLint("WakelockTimeout")
    void setScreenOffObservation(boolean enabled) {
        if (!enabled || !observing) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            return;
        }
        if (wakeLock == null) {
            PowerManager power = getSystemService(PowerManager.class);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gyeot:camera_observation");
            wakeLock.setReferenceCounted(false);
        }
        // Keep analysis running only while this visible, stoppable session is in the background.
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    void stopObservation() {
        if (observing) {
            observing = false;
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        }
        setScreenOffObservation(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        Consumer<Boolean> currentObserver = observer;
        if (currentObserver != null) currentObserver.accept(false);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (observing) lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        observing = false;
        setScreenOffObservation(false);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        Consumer<Boolean> currentObserver = observer;
        observer = null;
        if (currentObserver != null) currentObserver.accept(false);
        super.onDestroy();
    }

    private Notification notification() {
        Intent open = new Intent(this, CompanionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, ObservationService.class).setAction(ACTION_STOP);
        PendingIntent stopObservation = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_gyeot)
                .setContentTitle(getString(R.string.screen_off_observation_notification))
                .setContentIntent(openApp)
                .setDeleteIntent(stopObservation)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.observation_stop), stopObservation).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }
}
