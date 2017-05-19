package com.github.teocci.ffmpeg.recorder;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.github.teocci.ffmpeg.R;
import com.github.teocci.ffmpeg.recorder.data.FrameToRecord;
import com.github.teocci.ffmpeg.recorder.data.ChunkRecord;
import com.github.teocci.ffmpeg.recorder.util.CameraHelper;

import org.bytedeco.javacpp.avcodec;
import org.bytedeco.javacpp.avutil;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameFilter;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Thread.State.WAITING;

/**
 * Created by teocci.
 *
 * @author teocci@yandex.com on 2017-May-19
 */
public class RecordActivity extends AppCompatActivity implements
        TextureView.SurfaceTextureListener, View.OnClickListener
{
    private static final String LOG_TAG = RecordActivity.class.getSimpleName();

    private static final int REQUEST_PERMISSIONS = 1;

    private static final int PREFERRED_PREVIEW_WIDTH = 640;
    private static final int PREFERRED_PREVIEW_HEIGHT = 480;

    // both in milliseconds
    private static final long MIN_VIDEO_LENGTH = 1 * 1000;
    private static final long MAX_VIDEO_LENGTH = 90 * 1000;

    private FixedRatioCroppedTextureView croppedTextureView;
    private Button btnResumeOrPause;
    private Button btnDone;
    private Button btnSwitchCamera;
    private Button btnReset;

    private int cameraId;
    private Camera camera;
    private FFmpegFrameRecorder frameRecorder;
    private VideoRecordThread videoRecordThread;
    private AudioRecordRunnable audioRecordRunnable;
    private volatile boolean isRecording = false;
    private File videoFile;
    private LinkedBlockingQueue<FrameToRecord> frameToRecordQueue;
    private LinkedBlockingQueue<FrameToRecord> recycledFrameQueue;
    private int frameToRecordCount;
    private int frameRecordedCount;
    private long totalProcessFrameTime;
    private Stack<ChunkRecord> chunkRecords;

    private int sampleAudioRateInHz = 44100;
    /* The sides of width and height are based on camera orientation.
    That is, the preview size is the size before it is rotated. */
    private int previewWidth = PREFERRED_PREVIEW_WIDTH;
    private int previewHeight = PREFERRED_PREVIEW_HEIGHT;
    // Output video size
    private int videoWidth = 720;
    private int videoHeight = 480;
    private int frameRate = 30;
    private int frameDepth = Frame.DEPTH_UBYTE;
    private int frameChannels = 2;

    // Workaround for https://code.google.com/p/android/issues/detail?id=190966
    private Runnable doAfterAllPermissionsGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ffmpeg_record);
        croppedTextureView = (FixedRatioCroppedTextureView) findViewById(R.id.camera_preview);
        btnResumeOrPause = (Button) findViewById(R.id.btn_resume_or_pause);
        btnDone = (Button) findViewById(R.id.btn_done);
        btnSwitchCamera = (Button) findViewById(R.id.btn_switch_camera);
        btnReset = (Button) findViewById(R.id.btn_reset);

//        cameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        // Switch width and height
        croppedTextureView.setPreviewSize(videoWidth, videoHeight);
        croppedTextureView.setCroppedSizeWeight(videoWidth, videoHeight);
        croppedTextureView.setSurfaceTextureListener(this);
        btnResumeOrPause.setOnClickListener(this);
        btnDone.setOnClickListener(this);
        btnSwitchCamera.setOnClickListener(this);
        btnReset.setOnClickListener(this);

        // At most buffer 10 Frame
        frameToRecordQueue = new LinkedBlockingQueue<>(10);
        // At most recycle 2 Frame
        recycledFrameQueue = new LinkedBlockingQueue<>(2);
        chunkRecords = new Stack<>();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        stopRecorder();
        releaseRecorder(true);
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (doAfterAllPermissionsGranted != null) {
            doAfterAllPermissionsGranted.run();
            doAfterAllPermissionsGranted = null;
        } else {
            String[] neededPermissions = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
            };
            List<String> deniedPermissions = new ArrayList<>();
            for (String permission : neededPermissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permission);
                }
            }
            if (deniedPermissions.isEmpty()) {
                // All permissions are granted
                doAfterAllPermissionsGranted();
            } else {
                String[] array = new String[deniedPermissions.size()];
                array = deniedPermissions.toArray(array);
                ActivityCompat.requestPermissions(this, array, REQUEST_PERMISSIONS);
            }
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        pauseRecording();
        stopRecording();
        releaseAudioRecorder();
        stopPreview();
        releaseCamera();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean permissionsAllGranted = true;
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    permissionsAllGranted = false;
                    break;
                }
            }
            if (permissionsAllGranted) {
                doAfterAllPermissionsGranted = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        doAfterAllPermissionsGranted();
                    }
                };
            } else {
                doAfterAllPermissionsGranted = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        Toast.makeText(RecordActivity.this, R.string.permissions_denied_exit, Toast.LENGTH_SHORT).show();
                        finish();
                    }
                };
            }
        }
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, int width, int height)
    {
        startPreview(surface);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height)
    {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface)
    {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface)
    {
    }

    @Override
    public void onClick(View v)
    {
        int i = v.getId();
        if (i == R.id.btn_resume_or_pause) {
            if (isRecording) {
                pauseRecording();
            } else {
                resumeRecording();
            }

        } else if (i == R.id.btn_done) {
            pauseRecording();
            // check video length
            if (calculateTotalRecordedTime(chunkRecords) < MIN_VIDEO_LENGTH) {
                Toast.makeText(this, R.string.video_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            new FinishRecordingTask().execute();

        } else if (i == R.id.btn_switch_camera) {
            final SurfaceTexture surfaceTexture = croppedTextureView.getSurfaceTexture();
            new ProgressDialogTask<Void, Integer, Void>(R.string.please_wait)
            {

                @Override
                protected Void doInBackground(Void... params)
                {
                    stopRecording();
                    stopPreview();
                    releaseCamera();

                    cameraId = (cameraId + 1) % 2;

                    acquireCamera();
                    startPreview(surfaceTexture);
                    startRecording();
                    return null;
                }
            }.execute();

        } else if (i == R.id.btn_reset) {
            pauseRecording();
            new ProgressDialogTask<Void, Integer, Void>(R.string.please_wait)
            {

                @Override
                protected Void doInBackground(Void... params)
                {
                    stopRecording();
                    stopRecorder();

                    startRecorder();
                    startRecording();
                    return null;
                }
            }.execute();
        }
    }

    private void doAfterAllPermissionsGranted()
    {
        acquireCamera();
        SurfaceTexture surfaceTexture = croppedTextureView.getSurfaceTexture();
        if (surfaceTexture != null) {
            // SurfaceTexture already created
            startPreview(surfaceTexture);
        }
        new ProgressDialogTask<Void, Integer, Void>(R.string.initiating)
        {

            @Override
            protected Void doInBackground(Void... params)
            {
                if (frameRecorder == null) {
                    initRecorder();
                    startRecorder();
                }
                initAudioRecorder();
                startRecording();
                return null;
            }
        }.execute();
    }

    private void startPreview(SurfaceTexture surfaceTexture)
    {
        if (camera == null) {
            return;
        }

        Camera.Parameters parameters = camera.getParameters();
        List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
        Camera.Size previewSize = CameraHelper.getOptimalSize(previewSizes,
                PREFERRED_PREVIEW_WIDTH, PREFERRED_PREVIEW_HEIGHT);
        // if changed, reassign values and request layout
        if (previewWidth != previewSize.width || previewHeight != previewSize.height) {
            previewWidth = previewSize.width;
            previewHeight = previewSize.height;
            // Switch width and height
            croppedTextureView.setPreviewSize(previewHeight, previewWidth);
            croppedTextureView.requestLayout();
        }
        parameters.setPreviewSize(previewWidth, previewHeight);
//        parameters.setPreviewFormat(ImageFormat.NV21);
        camera.setParameters(parameters);

        camera.setDisplayOrientation(CameraHelper.getCameraDisplayOrientation(
                this, cameraId));

        // YCbCr_420_SP (NV21) format
        byte[] bufferByte = new byte[previewWidth * previewHeight * 3 / 2];
        camera.addCallbackBuffer(bufferByte);
        camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback()
        {

            private long lastPreviewFrameTime;

            @Override
            public void onPreviewFrame(byte[] data, Camera camera)
            {
                long thisPreviewFrameTime = System.currentTimeMillis();
                if (lastPreviewFrameTime > 0) {
                    Log.d(LOG_TAG, "Preview frame interval: " + (thisPreviewFrameTime - lastPreviewFrameTime) + "ms");
                }
                lastPreviewFrameTime = thisPreviewFrameTime;

                // get video data
                if (isRecording) {
                    if (audioRecordRunnable == null || !audioRecordRunnable.isRunning()) {
                        // wait for AudioRecord to init and start
                        chunkRecords.peek().setStartTimestamp(System.currentTimeMillis());
                    } else {
                        // pop the current record fragment when calculate total recorded time
                        ChunkRecord curFragment = chunkRecords.pop();
                        long recordedTime = calculateTotalRecordedTime(chunkRecords);
                        // push it back after calculation
                        chunkRecords.push(curFragment);
                        long curRecordedTime = System.currentTimeMillis()
                                - curFragment.getStartTimestamp() + recordedTime;
                        // check if exceeds time limit
                        if (curRecordedTime > MAX_VIDEO_LENGTH) {
                            pauseRecording();
                            new FinishRecordingTask().execute();
                            return;
                        }

                        long timestamp = 1000 * curRecordedTime;
                        Frame frame;
                        FrameToRecord frameToRecord = recycledFrameQueue.poll();
                        if (frameToRecord != null) {
                            frame = frameToRecord.getFrame();
                            frameToRecord.setTimestamp(timestamp);
                        } else {
                            frame = new Frame(previewWidth, previewHeight, frameDepth, frameChannels);
                            frameToRecord = new FrameToRecord(timestamp, frame);
                        }
                        ((ByteBuffer) frame.image[0].position(0)).put(data);

                        if (frameToRecordQueue.offer(frameToRecord)) {
                            frameToRecordCount++;
                        }
                    }
                }
                RecordActivity.this.camera.addCallbackBuffer(data);
            }
        });

        try {
            camera.setPreviewTexture(surfaceTexture);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        camera.startPreview();
    }

    private void stopPreview()
    {
        if (camera != null) {
            camera.stopPreview();
            camera.setPreviewCallbackWithBuffer(null);
        }
    }

    private void acquireCamera()
    {
        try {
            camera = Camera.open(cameraId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void releaseCamera()
    {
        if (camera != null) {
            camera.release();        // release the camera for other applications
            camera = null;
        }
    }

    private void initRecorder()
    {
        Log.i(LOG_TAG, "init frameRecorder");

        String recordedTime = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        videoFile = CameraHelper.getOutputMediaFile(recordedTime, CameraHelper.MEDIA_TYPE_VIDEO);
        Log.i(LOG_TAG, "Output Video: " + videoFile);

        frameRecorder = new FFmpegFrameRecorder(videoFile, videoWidth, videoHeight, 1);
        frameRecorder.setFormat("mp4");
        frameRecorder.setSampleRate(sampleAudioRateInHz);
        frameRecorder.setFrameRate(frameRate);
        // Use H264
        frameRecorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);

        // See: https://trac.ffmpeg.org/wiki/Encode/H.264#crf
        /*
         * The range of the quantizer scale is 0-51: where 0 is lossless, 23 is default, and 51 is worst possible. A lower value is a higher quality and a subjectively sane range is 18-28. Consider 18 to be visually lossless or nearly so: it should look the same or nearly the same as the input but it isn't technically lossless.
         * The range is exponential, so increasing the CRF value +6 is roughly half the bitrate while -6 is roughly twice the bitrate. General usage is to choose the highest CRF value that still provides an acceptable quality. If the output looks good, then try a higher value and if it looks bad then choose a lower value.
         */
        frameRecorder.setVideoOption("crf", "28");
        frameRecorder.setVideoOption("preset", "superfast");
        frameRecorder.setVideoOption("tune", "zerolatency");

        Log.i(LOG_TAG, "frameRecorder initialize success");
    }

    private void releaseRecorder(boolean deleteFile)
    {
        if (frameRecorder != null) {
            try {
                frameRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
            frameRecorder = null;

            if (deleteFile) {
                videoFile.delete();
            }
        }
    }

    private void startRecorder()
    {
        try {
            frameRecorder.start();
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }

    private void stopRecorder()
    {
        if (frameRecorder != null) {
            try {
                frameRecorder.stop();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            }
        }

        chunkRecords.clear();

        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                btnReset.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void initAudioRecorder()
    {
        audioRecordRunnable = new AudioRecordRunnable();
    }

    private void releaseAudioRecorder()
    {
        if (audioRecordRunnable != null) {
            audioRecordRunnable.release();
            audioRecordRunnable = null;
        }
    }

    private void startRecording()
    {
        videoRecordThread = new VideoRecordThread();
        videoRecordThread.start();
    }

    private void stopRecording()
    {
        if (videoRecordThread != null && videoRecordThread.isRunning()) {
            videoRecordThread.stopRunning();
            try {
                videoRecordThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            videoRecordThread = null;
        }

        frameToRecordQueue.clear();
        recycledFrameQueue.clear();
    }

    private void resumeRecording()
    {
        if (!isRecording) {
            ChunkRecord recordFragment = new ChunkRecord();
            recordFragment.setStartTimestamp(System.currentTimeMillis());
            chunkRecords.push(recordFragment);
            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    btnReset.setVisibility(View.VISIBLE);
                    btnSwitchCamera.setVisibility(View.INVISIBLE);
                    btnResumeOrPause.setText(R.string.pause);
                }
            });
            isRecording = true;
            new Thread(audioRecordRunnable).start();
        }
    }

    private void pauseRecording()
    {
        if (isRecording) {
            chunkRecords.peek().setEndTimestamp(System.currentTimeMillis());
            runOnUiThread(new Runnable()
            {
                @Override
                public void run()
                {
                    btnSwitchCamera.setVisibility(View.VISIBLE);
                    btnResumeOrPause.setText(R.string.resume);
                }
            });
            isRecording = false;
            audioRecordRunnable.stop();
        }
    }

    private long calculateTotalRecordedTime(Stack<ChunkRecord> recordFragments)
    {
        long recordedTime = 0;
        for (ChunkRecord recordFragment : recordFragments) {
            recordedTime += recordFragment.getDuration();
        }
        return recordedTime;
    }

    class AudioRecordRunnable implements Runnable
    {

        private boolean isRunning;
        private AudioRecord mAudioRecord;
        private ShortBuffer audioData;
        private CountDownLatch latch;

        public AudioRecordRunnable()
        {
            int bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            audioData = ShortBuffer.allocate(bufferSize);
        }

        @Override
        public void run()
        {
            latch = new CountDownLatch(1);
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            Log.d(LOG_TAG, "mAudioRecord startRecording");
            mAudioRecord.startRecording();

            isRunning = true;
            /* ffmpeg_audio encoding loop */
            while (isRunning) {
                if (isRecording && frameRecorder != null) {
                    int bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                    audioData.limit(bufferReadResult);
                    if (bufferReadResult > 0) {
                        Log.v(LOG_TAG, "bufferReadResult: " + bufferReadResult);
                        try {
                            frameRecorder.recordSamples(audioData);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.v(LOG_TAG, e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
            Log.d(LOG_TAG, "mAudioRecord stopRecording");
            mAudioRecord.stop();
            latch.countDown();
        }

        public boolean isRunning()
        {
            return isRunning;
        }

        public void stop()
        {
            this.isRunning = false;
        }

        public void release()
        {
            if (latch == null) {
                return;
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (mAudioRecord != null) {
                mAudioRecord.release();
                mAudioRecord = null;
                Log.d(LOG_TAG, "mAudioRecord released");
            }
        }
    }

    class VideoRecordThread extends Thread
    {

        private boolean isRunning;

        @Override
        public void run()
        {
            List<String> filters = new ArrayList<>();
            // Transpose
            String transpose = null;
            android.hardware.Camera.CameraInfo info =
                    new android.hardware.Camera.CameraInfo();
            android.hardware.Camera.getCameraInfo(cameraId, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                switch (info.orientation) {
                    case 270:
//                        transpose = "transpose=clock_flip"; // Same as preview display
                        transpose = "transpose=cclock"; // Mirrored horizontally as preview display
                        break;
                    case 90:
//                        transpose = "transpose=cclock_flip"; // Same as preview display
                        transpose = "transpose=clock"; // Mirrored horizontally as preview display
                        break;
                }
            } else {
                switch (info.orientation) {
                    case 270:
                        transpose = "transpose=cclock";
                        break;
                    case 90:
                        transpose = "transpose=clock";
                        break;
                }
            }
            if (transpose != null) {
                filters.add(transpose);
            }
            // Crop (only vertically)
            int width = previewHeight;
            int height = width * videoHeight / videoWidth;
            String crop = String.format("crop=%d:%d:%d:%d",
                    width, height,
                    (previewHeight - width) / 2, (previewWidth - height) / 2);
            filters.add(crop);
            // Scale (to designated size)
            String scale = String.format("scale=%d:%d", videoHeight, videoWidth);
            filters.add(scale);

            FFmpegFrameFilter frameFilter = new FFmpegFrameFilter(TextUtils.join(",", filters),
                    previewWidth, previewHeight);
            frameFilter.setPixelFormat(avutil.AV_PIX_FMT_NV21);
            frameFilter.setFrameRate(frameRate);
            try {
                frameFilter.start();
            } catch (FrameFilter.Exception e) {
                e.printStackTrace();
            }

            isRunning = true;
            FrameToRecord recordedFrame;

            while (isRunning || !frameToRecordQueue.isEmpty()) {
                try {
                    recordedFrame = frameToRecordQueue.take();
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                    try {
                        frameFilter.stop();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    break;
                }

                if (frameRecorder != null) {
                    long timestamp = recordedFrame.getTimestamp();
                    if (timestamp > frameRecorder.getTimestamp()) {
                        frameRecorder.setTimestamp(timestamp);
                    }
                    long startTime = System.currentTimeMillis();
//                    Frame filteredFrame = recordedFrame.getFrame();
                    Frame filteredFrame = null;
                    try {
                        frameFilter.push(recordedFrame.getFrame());
                        filteredFrame = frameFilter.pull();
                    } catch (FrameFilter.Exception e) {
                        e.printStackTrace();
                    }
                    try {
                        frameRecorder.record(filteredFrame);
                    } catch (FFmpegFrameRecorder.Exception e) {
                        e.printStackTrace();
                    }
                    long endTime = System.currentTimeMillis();
                    long processTime = endTime - startTime;
                    totalProcessFrameTime += processTime;
                    Log.d(LOG_TAG, "This frame process time: " + processTime + "ms");
                    long totalAvg = totalProcessFrameTime / ++frameRecordedCount;
                    Log.d(LOG_TAG, "Avg frame process time: " + totalAvg + "ms");
                }
                Log.d(LOG_TAG, frameRecordedCount + " / " + frameToRecordCount);
                recycledFrameQueue.offer(recordedFrame);
            }
        }

        public void stopRunning()
        {
            this.isRunning = false;
            if (getState() == WAITING) {
                interrupt();
            }
        }

        public boolean isRunning()
        {
            return isRunning;
        }
    }

    abstract class ProgressDialogTask<Params, Progress, Result> extends AsyncTask<Params, Progress, Result>
    {

        private int promptRes;
        private ProgressDialog mProgressDialog;

        public ProgressDialogTask(int promptRes)
        {
            this.promptRes = promptRes;
        }

        @Override
        protected void onPreExecute()
        {
            super.onPreExecute();
            mProgressDialog = ProgressDialog.show(RecordActivity.this,
                    null, getString(promptRes), true);
        }

        @Override
        protected void onProgressUpdate(Progress... values)
        {
            super.onProgressUpdate(values);
//            mProgressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(Result result)
        {
            super.onPostExecute(result);
            mProgressDialog.dismiss();
        }
    }

    class FinishRecordingTask extends ProgressDialogTask<Void, Integer, Void>
    {

        public FinishRecordingTask()
        {
            super(R.string.processing);
        }

        @Override
        protected Void doInBackground(Void... params)
        {
            stopRecording();
            stopRecorder();
            releaseRecorder(false);
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid)
        {
            super.onPostExecute(aVoid);

            Intent intent = new Intent(RecordActivity.this, PlaybackActivity.class);
            intent.putExtra(PlaybackActivity.INTENT_NAME_VIDEO_PATH, videoFile.getPath());
            startActivity(intent);
        }
    }
}
