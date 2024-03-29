package com.max.player.util;

import java.lang.reflect.Method;

import com.max.player.controller.RemoteControlClientCompat;

import android.media.AudioManager;
import android.util.Log;

@SuppressWarnings({"unchecked", "rawtypes"})
public class RemoteControlHelper {
    private static final String TAG = "RemoteControlHelper";

    private static boolean sHasRemoteControlAPIs = false;

    private static Method sRegisterRemoteControlClientMethod;
    private static Method sUnregisterRemoteControlClientMethod;

    static {
        try {
            ClassLoader classLoader = RemoteControlHelper.class.getClassLoader();
            Class sRemoteControlClientClass =
                    RemoteControlClientCompat.getActualRemoteControlClientClass(classLoader);
            sRegisterRemoteControlClientMethod = AudioManager.class.getMethod(
                    "registerRemoteControlClient", new Class[]{sRemoteControlClientClass});
            sUnregisterRemoteControlClientMethod = AudioManager.class.getMethod(
                    "unregisterRemoteControlClient", new Class[]{sRemoteControlClientClass});
            sHasRemoteControlAPIs = true;
        } catch (ClassNotFoundException e) {
            // Silently fail when running on an OS before ICS.
        } catch (NoSuchMethodException e) {
            // Silently fail when running on an OS before ICS.
        } catch (IllegalArgumentException e) {
            // Silently fail when running on an OS before ICS.
        } catch (SecurityException e) {
            // Silently fail when running on an OS before ICS.
        }
    }

    public static void registerRemoteControlClient(AudioManager audioManager,
            RemoteControlClientCompat remoteControlClient) {
        if (!sHasRemoteControlAPIs) {
            return;
        }

        try {
            sRegisterRemoteControlClientMethod.invoke(audioManager,
                    remoteControlClient.getActualRemoteControlClientObject());
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }


    public static void unregisterRemoteControlClient(AudioManager audioManager,
            RemoteControlClientCompat remoteControlClient) {
        if (!sHasRemoteControlAPIs) {
            return;
        }

        try {
            sUnregisterRemoteControlClientMethod.invoke(audioManager,
                    remoteControlClient.getActualRemoteControlClientObject());
        } catch (Exception e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }
}
