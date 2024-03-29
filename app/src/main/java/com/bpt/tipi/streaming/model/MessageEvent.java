package com.bpt.tipi.streaming.model;

/**
 * Created by jpujolji on 28/08/17.
 */

public class MessageEvent {

    public static final String LOCAL_RECORD = "localRecord";

    public static final String INIT_LOCAL_RECORD = "initLocalRecord";

    public static final String START_STREAMING = "startStreaming";

    public static final String STOP_STREAMING = "stopStreaming";

    public static final String RECONNECT = "reconnect";

    public static final String ID_DEVICE_CONFIGURED = "idDeviceChanged";

    public static final String SOS_PRESSED = "sosPressed";

    public static final String STATE_FLASH = "stateFlash";

    public static final String TIME_ELAPSED = "timeElapsed";

    public static final String SOS_ENABLED = "sosEnabled";

    public static final String MACHINE_EVENT_START_LOCAL_RECORD = "machineEventStartLocalRecord";

    public static final String START_LOCAL_RECORDING = "startLocalRecording";

    public static final String STOP_LOCAL_RECORDING = "stopLocalRecording";

    public static final String FINISH_SERVICES = "finishServices";

    public static final String START_SERVICES = "startServices";

    public String key;
    public String content;

    public MessageEvent(String mKey) {
        key = mKey;
    }

    public MessageEvent(String mKey, String mContent) {
        key = mKey;
        content = mContent;
    }
}
