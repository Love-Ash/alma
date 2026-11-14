package ai.minash.gyeot.companion;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

import androidx.annotation.MainThread;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@MainThread
final class KidsPlaybackController {
    static final String PACKAGE = "com.google.android.apps.youtube.kids";
    private static final long CONFIRMATION_TIMEOUT_MS = 2500;
    private static final String OBSERVATION_REQUIRED = "kids_observation_required";
    private static final String ACCESS_REQUIRED = "kids_access_required";
    private static final String SESSION_UNAVAILABLE = "kids_session_unavailable";
    private static final String STATE_NOT_CONFIRMED = "kids_state_not_confirmed";

    private final Context context;
    private final Handler main;
    private final String targetPackage;
    private final ComponentName listener;
    private PendingCommand pending;

    KidsPlaybackController(Context context, Handler main) {
        this(context, main, PACKAGE);
    }

    KidsPlaybackController(Context context, Handler main, String targetPackage) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.targetPackage = targetPackage;
        listener = new ComponentName(context, KidsSessionAccess.class);
    }

    boolean accessGranted() {
        return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(listener);
    }

    boolean targetInstalled() {
        try {
            context.getPackageManager().getPackageInfo(targetPackage, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    void request(boolean play, BooleanSupplier allowed, Consumer<String> finished) {
        if (!allowed.getAsBoolean()) {
            finished.accept(OBSERVATION_REQUIRED);
            return;
        }
        if (pending != null) {
            finished.accept("kids_command_in_progress");
            return;
        }
        MediaController controller;
        try {
            controller = findSession();
        } catch (Unavailable error) {
            finished.accept(error.getMessage());
            return;
        }
        PendingCommand command = new PendingCommand(controller, play, allowed, finished);
        pending = command;
        command.dispatch();
    }

    void cancel() {
        if (pending != null) pending.finish(OBSERVATION_REQUIRED);
    }

    private MediaController findSession() throws Unavailable {
        if (!targetInstalled()) throw new Unavailable("kids_app_unavailable");
        if (!accessGranted()) throw new Unavailable(ACCESS_REQUIRED);
        MediaController found = null;
        try {
            MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
            for (MediaController controller : sessions.getActiveSessions(listener)) {
                if (!targetPackage.equals(controller.getPackageName())) continue;
                if (found != null) throw new Unavailable("kids_session_ambiguous");
                found = controller;
            }
        } catch (SecurityException denied) {
            throw new Unavailable(ACCESS_REQUIRED);
        }
        if (found == null) throw new Unavailable(SESSION_UNAVAILABLE);
        return found;
    }

    private final class PendingCommand extends MediaController.Callback {
        private final MediaController controller;
        private final boolean play;
        private final int startState;
        private final int targetState;
        private final long requiredAction;
        private final BooleanSupplier allowed;
        private final Consumer<String> finished;
        private final Runnable timeout = this::onTimeout;
        private boolean registered;
        private long beforeUpdate;
        private long dispatchedAt = -1;

        PendingCommand(MediaController controller, boolean play, BooleanSupplier allowed, Consumer<String> finished) {
            this.controller = controller;
            this.play = play;
            this.allowed = allowed;
            this.finished = finished;
            startState = play ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING;
            targetState = play ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            requiredAction = play ? PlaybackState.ACTION_PLAY : PlaybackState.ACTION_PAUSE;
        }

        void dispatch() {
            try {
                controller.registerCallback(this, main);
                registered = true;
                PlaybackState before = currentSession().getPlaybackState();
                if (before == null || (before.getActions() & requiredAction) == 0) {
                    throw new Unavailable("kids_action_unsupported");
                }
                if (before.getState() != startState) throw new Unavailable("kids_start_state_mismatch");
                beforeUpdate = before.getLastPositionUpdateTime();
                if (!main.postDelayed(timeout, CONFIRMATION_TIMEOUT_MS)) throw new Unavailable(STATE_NOT_CONFIRMED);
                if (!allowed.getAsBoolean()) throw new Unavailable(OBSERVATION_REQUIRED);
                dispatchedAt = SystemClock.elapsedRealtime();
                if (play) controller.getTransportControls().play();
                else controller.getTransportControls().pause();
            } catch (Unavailable error) {
                finish(error.getMessage());
            } catch (SecurityException denied) {
                finish(accessGranted() ? SESSION_UNAVAILABLE : ACCESS_REQUIRED);
            }
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) {
            if (pending != this || dispatchedAt < 0) return;
            long now = SystemClock.elapsedRealtime();
            if (now - dispatchedAt >= CONFIRMATION_TIMEOUT_MS) {
                onTimeout();
                return;
            }
            try {
                MediaController current = currentSession();
                if (!isFreshTargetState(state, now)) return;
                PlaybackState latest = current.getPlaybackState();
                if (isFreshTargetState(latest, SystemClock.elapsedRealtime())
                        && latest.getLastPositionUpdateTime() >= state.getLastPositionUpdateTime()) {
                    if (!allowed.getAsBoolean()) throw new Unavailable(OBSERVATION_REQUIRED);
                    if (!accessGranted()) throw new Unavailable(ACCESS_REQUIRED);
                    finish(SystemClock.elapsedRealtime() - dispatchedAt < CONFIRMATION_TIMEOUT_MS
                            ? null : STATE_NOT_CONFIRMED);
                }
            } catch (Unavailable error) {
                finish(error.getMessage());
            } catch (SecurityException denied) {
                finish(accessGranted() ? SESSION_UNAVAILABLE : ACCESS_REQUIRED);
            }
        }

        @Override public void onSessionDestroyed() {
            finish("kids_session_lost");
        }

        private MediaController currentSession() throws Unavailable {
            if (!allowed.getAsBoolean()) throw new Unavailable(OBSERVATION_REQUIRED);
            MediaController current = findSession();
            if (!controller.getSessionToken().equals(current.getSessionToken())) {
                throw new Unavailable("kids_session_replaced");
            }
            return current;
        }

        private boolean isFreshTargetState(PlaybackState state, long now) {
            if (state == null || state.getState() != targetState) return false;
            long updated = state.getLastPositionUpdateTime();
            return updated >= dispatchedAt && updated > beforeUpdate && updated <= now;
        }

        private void onTimeout() {
            if (pending != this) return;
            try {
                currentSession();
                finish(STATE_NOT_CONFIRMED);
            } catch (Unavailable error) {
                finish(error.getMessage());
            } catch (SecurityException denied) {
                finish(accessGranted() ? SESSION_UNAVAILABLE : ACCESS_REQUIRED);
            }
        }

        private void finish(String error) {
            if (pending != this) return;
            pending = null;
            main.removeCallbacks(timeout);
            if (registered) controller.unregisterCallback(this);
            finished.accept(error);
        }
    }

    private static final class Unavailable extends Exception {
        Unavailable(String reason) {
            super(reason);
        }
    }
}
