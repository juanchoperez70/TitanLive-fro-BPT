package com.bpt.tipi.streaming.model;

/**
 * Created by jpujolji on 7/03/18.
 */

public class RemoteConfig {
    public String deviceName, masterPwd;
    public int locationTimeInterval, sosTimeInterval, localFrameRate, localVideoDurationMin, postRecordingTimeMin, streamingFrameRate, streamingVideoSize, localVideoSize;
    public boolean vibrateAndSoundOnRecord, postRecordingEnabled, vibrateAndSoundOnStreaming;

    public GeneralParameter generalParameter;

    public RemoteConfig(String deviceName, String masterPwd, int locationTimeInterval, int sosTimeInterval, int localFrameRate, int localVideoDurationMin, int postRecordingTimeMin, int streamingFrameRate, int streamingVideoSize, int localVideoSize, boolean vibrateAndSoundOnRecord, boolean postRecordingEnabled, boolean vibrateAndSoundOnStreaming, GeneralParameter generalParameter) {
        this.deviceName = deviceName;
        this.masterPwd = masterPwd;
        this.locationTimeInterval = locationTimeInterval;
        this.sosTimeInterval = sosTimeInterval;
        this.localFrameRate = localFrameRate;
        this.localVideoDurationMin = localVideoDurationMin;
        this.postRecordingTimeMin = postRecordingTimeMin;
        this.streamingFrameRate = streamingFrameRate;
        this.streamingVideoSize = streamingVideoSize;
        this.localVideoSize = localVideoSize;
        this.vibrateAndSoundOnRecord = vibrateAndSoundOnRecord;
        this.postRecordingEnabled = postRecordingEnabled;
        this.vibrateAndSoundOnStreaming = vibrateAndSoundOnStreaming;
        this.generalParameter = generalParameter;
    }
}
