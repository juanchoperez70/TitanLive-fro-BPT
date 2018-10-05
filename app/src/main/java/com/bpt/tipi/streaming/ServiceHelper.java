package com.bpt.tipi.streaming;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.bpt.tipi.streaming.mqtt.MqttService;
import com.bpt.tipi.streaming.service.LocationService;
import com.bpt.tipi.streaming.service.RecorderService;

/**
 * Created by jpujolji on 12/03/18.
 */

public class ServiceHelper {

    public static void startAllServices(Context context) {
        startLocationService(context);
        startMqttService(context);
        startRecorderService(context);
    }

    public static void stopAllServices(Context context) {
        stopLocationService(context);
        stopMqttService(context);
    }

    public static synchronized void startLocationService(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String idDevice = preferences.getString(context.getString(R.string.id_device), "");

        if (!idDevice.isEmpty()) {
            if (!Utils.isServiceRunning(context, LocationService.class)) {
                context.startService(new Intent(context, LocationService.class));
            }
        }
    }

    public static synchronized void stopLocationService(Context context) {
        if (Utils.isServiceRunning(context, LocationService.class)) {
            context.stopService(new Intent(context, LocationService.class));
        }
    }

    public static synchronized void stopMqttService(Context context) {
        if (Utils.isServiceRunning(context, MqttService.class)) {
            context.stopService(new Intent(context, MqttService.class));
        }
    }

    public static synchronized void startMqttService(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String idDevice = preferences.getString(context.getString(R.string.id_device), "");

        if (!idDevice.isEmpty()) {
            if (!Utils.isServiceRunning(context, MqttService.class)) {
                context.startService(new Intent(context, MqttService.class));
            }
        }
    }

    public static synchronized void stopRecorderService(Context context) {
        if (Utils.isServiceRunning(context, RecorderService.class)) {
            context.stopService(new Intent(context, RecorderService.class));
        }
    }

    public static synchronized void startRecorderService(Context context) {
        if (!Utils.isServiceRunning(context, RecorderService.class)) {
            context.startService(new Intent(context, RecorderService.class));
        }
    }
}
