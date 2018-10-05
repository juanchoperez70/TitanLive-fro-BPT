package com.bpt.tipi.streaming.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.TrafficStats;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import com.bpt.tipi.streaming.ConfigHelper;
import com.bpt.tipi.streaming.StateMachineHandler;
import com.bpt.tipi.streaming.UnCaughtException;
import com.bpt.tipi.streaming.Utils;
import com.bpt.tipi.streaming.helper.CameraRecorderHelper;
import com.bpt.tipi.streaming.helper.IrHelper;
import com.bpt.tipi.streaming.helper.VideoNameHelper;
import com.bpt.tipi.streaming.model.MessageEvent;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameRecorder;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class RecorderService extends Service implements Camera.PreviewCallback {

    //TAG para Log
    private static final String TAG = "RecorderService";

    //Nombre para el hilo del servicio
    public static final String THREAD_NAME = "RecorderService";

    public static final int AUDIO_BITRATE = 128000;
    public static final int AUDIO_RATE_IN_HZ = 44100;

    private final IBinder mBinder = new RecorderBinder();
    private Handler mHandler;

    private EventBus bus = EventBus.getDefault();
    public Context context;

    private String deviceId;

    //Camara a utilizar.
    private Camera camera = null;

    int sequence = 1;

    /* Variables de streaming */
    private FFmpegFrameRecorder streamingRecorder;
    private Frame streamingYuvImage = null;

    private AudioRecord streamingAudioRecord;
    private StreamingAudioRecordRunnable streamingAudioRecordRunnable;
    private Thread streamingAudioThread;
    volatile boolean streamingRunAudioThread = true;
    long streamingStartTime = 0;
    public boolean isStreamingRecording = false;

    /* Variables de local */
    private FFmpegFrameRecorder localRecorder;
    private Frame localYuvImage = null;

    private AudioRecord localAudioRecord;
    private RecorderService.LocalAudioRecordRunnable localAudioRecordRunnable;
    private Thread localAudioThread;
    volatile boolean localRunAudioThread = true;
    long localStartTime = 0;
    public boolean isLocalRecording = false;

    public boolean isSos = false;
    int videoDuration = 0;

    boolean flashOn = false;
    boolean proccesingStreming = false;

    CounterLocalVideo counterLocalVideo;

    CounterPostVideo counterPostVideo;

    CounterFlash counterFlash;

    long mStartTX;
    Date streamingStarted;

    StateMachineHandler machineHandler;

    boolean sosPressed = false;

    public class RecorderBinder extends Binder {
        public RecorderService getService() {
            return RecorderService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new UnCaughtException(this));
        context = RecorderService.this;
        HandlerThread thread = new HandlerThread(THREAD_NAME);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        Log.i(TAG, "RecorderService onCreate()");

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        bus.register(this);
        if (!OpenCVLoader.initDebug()) {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, loaderCallback);
        } else {
            loaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
        machineHandler = new StateMachineHandler(RecorderService.this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        bus.unregister(this);
        Log.i(TAG, "RecorderService onDestroy()");
        finishCamera();
        super.onDestroy();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onMessageEvent(MessageEvent event) {
        Log.e("Depuracion", "onMessageEvent " + event.key);
        switch (event.key) {
            case MessageEvent.SOS_PRESSED:
                if (!isSos) {
                    if (!isLocalRecording) {
                        setLocalRecorderStateMachine();
                    }
                    sosPressed = true;
                    isSos = true;
                    sendSos();
                } else {
                    isSos = false;
                    setLocalRecorderStateMachine();
                }
                break;
            case MessageEvent.LOCAL_RECORD:
                if (isSos) {
                    isSos = false;
                }
                setLocalRecorderStateMachine();
                break;
            case MessageEvent.START_STREAMING:
                if (!proccesingStreming) {
                    proccesingStreming = true;
                    if (!isStreamingRecording) {
                        machineHandler.sendEmptyMessage(StateMachineHandler.STREAMING);
                        mStartTX = TrafficStats.getTotalTxBytes();
                        Calendar cal = Calendar.getInstance();
                        streamingStarted = cal.getTime();
                    } else {
                        proccesingStreming = false;
                    }
                }
                break;
            case MessageEvent.STOP_STREAMING:
                if (!proccesingStreming) {
                    proccesingStreming = true;
                    if (isStreamingRecording) {
                        machineHandler.sendEmptyMessage(StateMachineHandler.STREAMING);
                        sendLogStreaming();
                    } else {
                        proccesingStreming = false;
                    }
                }
                break;
            case MessageEvent.STATE_FLASH:
                if (!flashOn) {
                    flashLightOn();
                } else {
                    pauseFlashCounter();
                    flashLightOff();
                }
                flashOn = !flashOn;
                break;
        }
    }

    public void setLocalRecorderStateMachine() {
        Message message = new Message();
        message.what = StateMachineHandler.LOCAL_RECORDER_PRESSED;
        message.arg1 = StateMachineHandler.PLAY_SOUND;
        machineHandler.handleMessage(message);
    }

    public synchronized void initCamera(final boolean localConfig) {
        if (!Utils.isCameraExist(context)) {
            throw new IllegalStateException("There is no device, not possible to start recording");
        }
        deviceId = ConfigHelper.getDeviceName(context);
        if (camera == null) {
            camera = Utils.getCameraInstance(Camera.CameraInfo.CAMERA_FACING_FRONT);
        }
        if (camera != null) {
            try {
                camera.setPreviewTexture(new SurfaceTexture(10));
            } catch (Exception e) {
                e.printStackTrace();
            }
            Camera.Parameters parameters = camera.getParameters();
            if (localConfig) {
                parameters.setPreviewSize(CameraRecorderHelper.getLocalImageWidth(context), CameraRecorderHelper.getLocalImageHeight(context));
                parameters.setPreviewFrameRate(ConfigHelper.getLocalFramerate(context));
            } else {
                parameters.setPreviewSize(CameraRecorderHelper.getStreamingImageWidth(context), CameraRecorderHelper.getStreamingImageHeight(context));
                parameters.setPreviewFrameRate(ConfigHelper.getStreamingFramerate(context));
            }
            parameters.setPreviewFormat(ImageFormat.NV21);
            camera.setParameters(parameters);

            camera.setPreviewCallback(RecorderService.this);

            try {
                camera.startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }
            IrHelper.setIrState(IrHelper.STATE_ON);
        } else {
            Log.d(TAG, "Get camera from service failed");
        }
    }

    public synchronized void finishCamera() {
        try {
            if (camera != null) {
                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.release();
                camera = null;
            }
            IrHelper.setIrState(IrHelper.STATE_OFF);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void configLocalRecorder() {
        if (isStreamingRecording) {
            localYuvImage = new Frame(CameraRecorderHelper.getStreamingImageWidth(context),
                    CameraRecorderHelper.getStreamingImageHeight(context), Frame.DEPTH_UBYTE, 2);
            localRecorder = CameraRecorderHelper.initRecorder(context, CameraRecorderHelper.RECORDER_TYPE_LOCAL, VideoNameHelper.getOutputFile(context, sequence).getAbsolutePath(), CameraRecorderHelper.FORMAT_MP4);
        } else {
            localYuvImage = new Frame(CameraRecorderHelper.getLocalImageWidth(context),
                    CameraRecorderHelper.getLocalImageHeight(context), Frame.DEPTH_UBYTE, 2);
            localRecorder = CameraRecorderHelper.initRecorder(context, CameraRecorderHelper.RECORDER_TYPE_LOCAL, VideoNameHelper.getOutputFile(context, sequence).getAbsolutePath(), CameraRecorderHelper.FORMAT_MP4);
        }

        localAudioRecordRunnable = new LocalAudioRecordRunnable();
        localAudioThread = new Thread(localAudioRecordRunnable);
        localRunAudioThread = true;
    }

    public void configStreamingRecorder() {
        streamingYuvImage = new Frame(CameraRecorderHelper.getStreamingImageWidth(context),
                CameraRecorderHelper.getStreamingImageHeight(context), Frame.DEPTH_UBYTE, 2);
        streamingRecorder = CameraRecorderHelper.initRecorder(context, CameraRecorderHelper.RECORDER_TYPE_STREAMING,
                CameraRecorderHelper.buildStreamEndpoint(context), CameraRecorderHelper.FORMAT_FLV);
        streamingAudioRecordRunnable = new StreamingAudioRecordRunnable();
        streamingAudioThread = new Thread(streamingAudioRecordRunnable);
        streamingRunAudioThread = true;
    }

    public void startLocalRecorder(boolean playSound) {
        if (sosPressed) {
            sosPressed = false;
        }
        startLocalFrameRecorder(playSound);
    }

    public void stopLocalRecorder(boolean playSound) {
        if (localRecorder != null) {
            stopLocalFrameRecorder(playSound);
        }
    }

    private void startLocalFrameRecorder(boolean playSound) {
        if (isLocalRecording) {
            return;
        }
        if (!playSound) {
            sequence = sequence + 1;
        }
        if (!isStreamingRecording) {
            initCamera(true);
        }
        configLocalRecorder();
        try {
            localRecorder.start();
            localStartTime = System.currentTimeMillis();
            isLocalRecording = true;
            localAudioThread.start();
        } catch (FrameRecorder.Exception e) {
            e.printStackTrace();
        }
        if (playSound) {
            if (ConfigHelper.getLocalVibrateAndSound(context)) {
                CameraRecorderHelper.soundStart(context);
            }
            bus.post(new MessageEvent(MessageEvent.START_LOCAL_RECORDING));
        }
    }

    private void stopLocalFrameRecorder(boolean playSound) {
        if (!isStreamingRecording) {
            finishCamera();
        }
        localRunAudioThread = false;
        try {
            if (localAudioThread != null) {
                localAudioThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        localAudioRecordRunnable = null;
        localAudioThread = null;
        if (localRecorder != null && isLocalRecording) {
            isLocalRecording = false;
            try {
                localRecorder.stop();
                localRecorder.release();
            } catch (FrameRecorder.Exception e) {
                e.printStackTrace();
            }
            localRecorder = null;
            localYuvImage = null;
        }
        if (playSound) {
            sendBusStopRecorder();
        }
    }

    public void sendBusStopRecorder() {
        videoDuration = 0;
        sequence = 1;
        if (ConfigHelper.getLocalVibrateAndSound(context)) {
            CameraRecorderHelper.soundStop(context);
        }
        bus.post(new MessageEvent(MessageEvent.STOP_LOCAL_RECORDING));
    }

    public void startStreamingRecorder() {
        if (isStreamingRecording) {
            return;
        }
        initCamera(false);
        configStreamingRecorder();
        try {
            streamingRecorder.start();
            streamingStartTime = System.currentTimeMillis();
            isStreamingRecording = true;
            proccesingStreming = false;
            streamingAudioThread.start();
        } catch (FrameRecorder.Exception e) {
            e.printStackTrace();
            MessageEvent event = new MessageEvent(MessageEvent.STOP_STREAMING);
            bus.post(event);
        }
        if (ConfigHelper.getStreamingVibrateAndSound(context)) {
            CameraRecorderHelper.soundStart(context);
        }
    }

    public void stopStreamingRecorder() {
        finishCamera();
        streamingRunAudioThread = false;
        try {
            if (streamingAudioThread != null) {
                streamingAudioThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        streamingAudioRecordRunnable = null;
        streamingAudioThread = null;
        if (streamingRecorder != null && isStreamingRecording) {
            isStreamingRecording = false;
            proccesingStreming = false;
            try {
                streamingRecorder.stop();
                streamingRecorder.release();
            } catch (FrameRecorder.Exception e) {
                e.printStackTrace();
            }
            streamingRecorder = null;
            streamingYuvImage = null;
        }
        if (ConfigHelper.getStreamingVibrateAndSound(context)) {
            CameraRecorderHelper.soundStop(context);
        }
    }

    @Override
    public void onPreviewFrame(byte[] bytes, Camera mCamera) {
        Camera.Parameters parameters = camera.getParameters();

        Mat mat = new Mat(parameters.getPreviewSize().height, parameters.getPreviewSize().width, CvType.CV_8UC2);
        mat.put(0, 0, bytes);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentDate = sdf.format(new Date());
        CameraRecorderHelper.putWaterMark(mat, currentDate, "TITAN-" + deviceId);

        int bufferSize = (int) (mat.total() * mat.elemSize());
        byte[] b = new byte[bufferSize];

        mat.get(0, 0, b);

        if (isStreamingRecording) {
            streamingRecord(b);
        }

        if (isLocalRecording && localRecorder != null) {
            localRecord(b);
        }
    }

    public void localRecord(byte[] data) {
        if (localAudioRecord == null || localAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            localStartTime = System.currentTimeMillis();
            return;
        }

        if (localYuvImage != null && isLocalRecording) {
            ((ByteBuffer) localYuvImage.image[0].position(0)).put(data);
            try {
                long t = 1000 * (System.currentTimeMillis() - localStartTime);
                if (t > localRecorder.getTimestamp()) {
                    localRecorder.setTimestamp(t);
                }
                localRecorder.record(localYuvImage);
            } catch (FFmpegFrameRecorder.Exception e) {
                Log.v(TAG, e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void streamingRecord(byte[] data) {
        if (streamingAudioRecord == null || streamingAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            streamingStartTime = System.currentTimeMillis();
            return;
        }

        if (streamingRecorder != null && streamingYuvImage != null && isStreamingRecording) {
            ((ByteBuffer) streamingYuvImage.image[0].position(0)).put(data);
            try {
                long t = 1000 * (System.currentTimeMillis() - streamingStartTime);
                if (t > streamingRecorder.getTimestamp()) {
                    streamingRecorder.setTimestamp(t);
                }
                synchronized (this) {
                    streamingRecorder.record(streamingYuvImage);
                }
            } catch (FFmpegFrameRecorder.Exception e) {
                Log.v(TAG, e.getMessage());
                e.printStackTrace();
                MessageEvent event = new MessageEvent(MessageEvent.STOP_STREAMING);
                bus.post(event);
            }
        }
    }

    public void initLocalVideoCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                counterLocalVideo = new CounterLocalVideo(ConfigHelper.getLocalVideoDurationInMill(context), 1000);
            }
        });
    }

    public void sendSos() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                CameraRecorderHelper.sendSignalSOS(context);
            }
        });
    }

    public void pauseLocalVideoCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (counterLocalVideo != null) {
                    counterLocalVideo.cancel();
                }
            }
        });
    }

    public void startLocalVideoCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (counterLocalVideo != null) {
                    counterLocalVideo.start();
                }
            }
        });
    }

    public void initPostVideoCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                counterPostVideo = new CounterPostVideo(ConfigHelper.getLocalPostVideoDurationInMill(context), 1000);
            }
        });
    }

    public void pausePostVideoCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (counterPostVideo != null) {
                    counterPostVideo.cancel();
                }
            }
        });
    }

    public void startPostVideoCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (counterPostVideo != null) {
                    counterPostVideo.start();
                }
            }
        });
    }

    public void initFlashCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                counterFlash = new CounterFlash(15000, 1000);
            }
        });
    }

    public void pauseFlashCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (counterFlash != null) {
                    counterFlash.cancel();
                }
            }
        });
    }

    public void startFlashCounter() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (counterFlash != null) {
                    counterFlash.start();
                }
            }
        });
    }

    public void sendLogStreaming() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                CameraRecorderHelper.sendLogStreaming(context, TrafficStats.getTotalTxBytes() - mStartTX, streamingStarted);
            }
        });
    }

    private class CounterLocalVideo extends CountDownTimer {
        CounterLocalVideo(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }

        @Override
        public void onFinish() {
            Message message = new Message();
            message.what = StateMachineHandler.LOCAL_RECORDER_PRESSED;
            message.arg1 = StateMachineHandler.DO_NOT_PLAY_SOUND;
            machineHandler.handleMessage(message);
            machineHandler.handleMessage(message);
        }

        @Override
        public void onTick(long millisUntilFinished) {
            videoDuration += 1;
            int seconds = (int) millisUntilFinished / 1000;
            if (seconds % 10 == 0) {
                Utils.saveVideoLocation(context, VideoNameHelper.getCurrentNameFile(context));
            }
            showVideoDuration();
        }
    }

    private class CounterPostVideo extends CountDownTimer {
        CounterPostVideo(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }

        @Override
        public void onFinish() {
            Message message = new Message();
            message.what = StateMachineHandler.POST_RECORDING;
            machineHandler.handleMessage(message);
        }

        @Override
        public void onTick(long millisUntilFinished) {

        }
    }

    private class CounterFlash extends CountDownTimer {
        CounterFlash(long millisInFuture, long countDownInterval) {
            super(millisInFuture, countDownInterval);
        }

        @Override
        public void onFinish() {
            flashLightOff();
            flashOn = !flashOn;
        }

        @Override
        public void onTick(long millisUntilFinished) {

        }
    }

    public void showVideoDuration() {
        if (videoDuration % 120 == 0) {
            CameraRecorderHelper.soundStart(context);
        }
        @SuppressLint("DefaultLocale")
        String value = String.format("%02d:%02d", videoDuration / 60, videoDuration % 60);
        MessageEvent event = new MessageEvent(MessageEvent.TIME_ELAPSED, value);
        bus.post(event);
    }

    public void flashLightOn() {
        try {
            if (getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_CAMERA_FLASH)) {
                if (camera == null) {
                    final int cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
                    camera = Utils.getCameraInstance(cameraId);
                }
                Camera.Parameters params = camera.getParameters();
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                camera.setParameters(params);
                initFlashCounter();
                startFlashCounter();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void flashLightOff() {
        try {
            if (getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_CAMERA_FLASH)) {
                Camera.Parameters params = camera.getParameters();
                params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                camera.setParameters(params);
                if (!isLocalRecording && !isStreamingRecording) {
                    camera.stopPreview();
                    camera.release();
                    camera = null;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private BaseLoaderCallback loaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                    Log.i(TAG, "OpenCV loaded successfully");
                    break;
                default:
                    super.onManagerConnected(status);
                    break;
            }
        }
    };

    private class LocalAudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            int bufferSize = AudioRecord.getMinBufferSize(AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            localAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            ShortBuffer shortBuffer = ShortBuffer.allocate(bufferSize);
            localAudioRecord.startRecording();
            while (localRunAudioThread) {
                int bufferResult = localAudioRecord.read(shortBuffer.array(), 0, shortBuffer.capacity());
                shortBuffer.limit(bufferResult);
                if (bufferResult > 0) {
                    if (isLocalRecording) {
                        try {
                            localRecorder.recordSamples(shortBuffer);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }

            }
            Log.v(TAG, "AudioThread Finished, release audioRecord");
            if (localAudioRecord != null) {
                localAudioRecord.stop();
                localAudioRecord.release();
                localAudioRecord = null;
            }
        }
    }

    private class StreamingAudioRecordRunnable implements Runnable {
        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

            int bufferSize = AudioRecord.getMinBufferSize(AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            streamingAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, AUDIO_RATE_IN_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            ShortBuffer shortBuffer = ShortBuffer.allocate(bufferSize);
            streamingAudioRecord.startRecording();
            while (streamingRunAudioThread) {
                int bufferResult = streamingAudioRecord.read(shortBuffer.array(), 0, shortBuffer.capacity());
                shortBuffer.limit(bufferResult);
                if (bufferResult > 0) {
                    if (isStreamingRecording) {
                        try {
                            streamingRecorder.recordSamples(shortBuffer);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }

            }
            Log.v(TAG, "AudioThread Finished, release audioRecord");
            if (streamingAudioRecord != null) {
                streamingAudioRecord.stop();
                streamingAudioRecord.release();
                streamingAudioRecord = null;
            }
        }
    }
}
