package dev.local.essentials;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

/** Camera2 torch with a shared on/off state. */
final class Torch {

    private static String cameraId;
    private static boolean on;

    private Torch() {}

    static boolean isOn() {
        return on;
    }

    static String id(Context c) {
        if (cameraId != null) return cameraId;
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return null;
            String[] ids = cm.getCameraIdList();
            for (String cam : ids) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(cam);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer face = ch.get(CameraCharacteristics.LENS_FACING);
                if (flash != null && flash && face != null
                        && face == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = cam;
                    return cam;
                }
            }
            for (String cam : ids) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(cam);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (flash != null && flash) {
                    cameraId = cam;
                    return cam;
                }
            }
        } catch (CameraAccessException ignored) {
        }
        return null;
    }

    static boolean set(Context c, boolean next) {
        String id = id(c);
        if (id == null) return false;
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return false;
            cm.setTorchMode(id, next);
            on = next;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean toggle(Context c) {
        return set(c, !on);
    }
}
