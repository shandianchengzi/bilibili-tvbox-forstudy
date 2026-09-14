package com.github.catvod.spider;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

/** Optional initialization hook recognized by CatVod loaders. */
public final class Init {
    private static volatile WeakReference<Activity> activity = new WeakReference<>(null);
    private static Application registeredApplication;

    private Init() { }

    public static synchronized void init(Context context) {
        if (context == null) return;
        Context current = context;
        for (int depth = 0; depth < 8; depth++) {
            if (current instanceof Activity) {
                remember((Activity) current);
                break;
            }
            if (!(current instanceof ContextWrapper)) break;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current || base == null) break;
            current = base;
        }
        Context application = context.getApplicationContext();
        if (!(application instanceof Application) && context instanceof Application) application = context;
        if (!(application instanceof Application) || registeredApplication == application) return;
        registeredApplication = (Application) application;
        registeredApplication.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity value, Bundle state) { }
            @Override public void onActivityStarted(Activity value) { }
            @Override public void onActivityResumed(Activity value) { remember(value); }
            @Override public void onActivityPaused(Activity value) { forget(value); }
            @Override public void onActivityStopped(Activity value) { forget(value); }
            @Override public void onActivitySaveInstanceState(Activity value, Bundle state) { }
            @Override public void onActivityDestroyed(Activity value) { forget(value); }
        });
    }

    private static void remember(Activity value) {
        if (usable(value)) activity = new WeakReference<>(value);
    }

    private static void forget(Activity value) {
        if (activity.get() == value) activity = new WeakReference<>(null);
    }

    private static boolean usable(Activity value) {
        return value != null && !value.isFinishing() && !value.isDestroyed();
    }

    /** A loader can initialize after the host Activity has already resumed. */
    public static Activity getActivity() {
        Activity known = activity.get();
        if (usable(known)) return known;
        try {
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("currentActivityThread").invoke(null);
            Object records = readField(type, thread, "mActivities");
            if (!(records instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) records).values()) {
                if (record == null || !Boolean.FALSE.equals(readField(record.getClass(), record, "paused"))) continue;
                Object candidate = readField(record.getClass(), record, "activity");
                if (candidate instanceof Activity && usable((Activity) candidate)) {
                    remember((Activity) candidate);
                    return (Activity) candidate;
                }
            }
        } catch (Exception ignored) {
            // Hidden framework fields are optional; the login cover remains available.
        } catch (LinkageError ignored) {
            // A host may restrict framework reflection.
        }
        return null;
    }

    private static Object readField(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    public static void run(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }
}
