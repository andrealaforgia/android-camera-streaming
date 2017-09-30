/* Copyright 2013 Foxdog Studios Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.foxdogstudios.peepers;

import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;

import com.foxdogstudios.peepers.lib.Optional;
import com.foxdogstudios.peepers.lib.Pair;

import java.io.IOException;
import java.util.List;

class CameraStreamer implements MessageListener {
    private static final String TAG = CameraStreamer.class.getSimpleName();

    private static final int MSG_TRY_START_STREAMING = 0;
    private static final int MSG_SEND_PREVIEW_FRAME = 1;

    private static final long OPEN_CAMERA_POLL_INTERVAL_MS = 1000L;

    private final MovingAverage averageSpf = new MovingAverage(50 /* numValues */);

    private final int cameraIndex;
    private final boolean useFlashLight;
    private final int port;
    private final int previewSizeIndex;
    private final int jpegQuality;
    private final SurfaceHolder previewDisplay;

    private boolean mRunning = false;
    private Camera mCamera = null;
    private MemoryOutputStream jpegOutputStream = null;
    private MJpegHttpStreamer mJpegHttpStreamer = null;

    private long numFrames = 0L;
    private long lastTimestamp = Long.MIN_VALUE;
    private Messenger messenger;

    private final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, final Camera camera) {
            final Long timestamp = SystemClock.elapsedRealtime();
            messenger.sendMessage(MSG_SEND_PREVIEW_FRAME, new Object[]{data, camera, timestamp});
        }
    };

    private Preview preview;

    CameraStreamer(final int cameraIndex,
                   final boolean useFlashLight,
                   final int port,
                   final int previewSizeIndex,
                   final int jpegQuality,
                   final SurfaceHolder previewDisplay) {
        super();

        if (previewDisplay == null) {
            throw new IllegalArgumentException("previewDisplay must not be null");
        }

        this.cameraIndex = cameraIndex;
        this.useFlashLight = useFlashLight;
        this.port = port;
        this.previewSizeIndex = previewSizeIndex;
        this.jpegQuality = jpegQuality;
        this.previewDisplay = previewDisplay;
    }

    @Override
    public void onMessage(Message message) {
        switch (message.what) {
            case MSG_TRY_START_STREAMING:
                tryStartStreaming();
                break;
            case MSG_SEND_PREVIEW_FRAME:
                final Object[] args = (Object[]) message.obj;
                sendPreviewFrame((byte[]) args[0], (Camera) args[1], (Long) args[2]);
                break;
            default:
                throw new IllegalArgumentException("cannot handle message");
        }
    }

    void start() {
        synchronized (this) {
            if (mRunning) {
                throw new IllegalStateException("CameraStreamer is already running");
            }
            mRunning = true;
        }

        messenger = new Messenger(TAG, this);
        messenger.sendMessage(MSG_TRY_START_STREAMING);
    }

    /**
     * Stop the image streamer. The camera will be released during the
     * execution of stop() or shortly after it returns. stop() should
     * be called on the main thread.
     */
    void stop() {
        synchronized (this) {
            if (!mRunning) {
                throw new IllegalStateException("CameraStreamer is already stopped");
            }

            mRunning = false;
            if (mJpegHttpStreamer != null) {
                mJpegHttpStreamer.stop();
            }
            if (mCamera != null) {
                mCamera.release();
                mCamera = null;
            }
        }
        messenger.close();
    }

    private void tryStartStreaming() {
        try {
            while (true) {
                try {
                    startStreamingIfRunning();
                } catch (RuntimeException e) {
                    Log.d(TAG, "Open camera failed, retying in " + OPEN_CAMERA_POLL_INTERVAL_MS
                            + "ms", e);
                    Thread.sleep(OPEN_CAMERA_POLL_INTERVAL_MS);
                    continue;
                }
                break;
            }
        } catch (Exception e) {
            // Captures the IOException from startStreamingIfRunning and
            // the InterruptException from Thread.sleep.
            Log.w(TAG, "Failed to start camera preview", e);
        }
    }

    private void startStreamingIfRunning() throws IOException {
        // Throws RuntimeException if the camera is currently opened
        // by another application.
        final Camera camera = Camera.open(cameraIndex);
        final Camera.Parameters cameraParams = camera.getParameters();

        if (useFlashLight) {
            cameraParams.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        }

        Pair<Integer, Integer> previewSize = findPreviewSize(cameraParams);
        cameraParams.setPreviewSize(previewSize.getA(), previewSize.getB());

        Optional<Pair<Integer, Integer>> fpsRange = findPreviewFpsRange(cameraParams);
        if (fpsRange.isPresent()) {
            cameraParams.setPreviewFpsRange(fpsRange.get().getA(), fpsRange.get().getB());
            camera.setParameters(cameraParams);
        }

        preview = new Preview(camera.getParameters());
        camera.addCallbackBuffer(new byte[preview.getBufferSize()]);

        camera.setParameters(cameraParams);
        camera.setPreviewCallbackWithBuffer(previewCallback);

        // We assumed that the compressed image will be no bigger than
        // the uncompressed image.
        jpegOutputStream = new MemoryOutputStream(preview.getBufferSize());

        final MJpegHttpStreamer streamer = new MJpegHttpStreamer(port, preview.getBufferSize());
        streamer.start();

        synchronized (this) {
            if (!mRunning) {
                streamer.stop();
                camera.release();
                return;
            }

            try {
                camera.setPreviewDisplay(previewDisplay);
            } catch (IOException e) {
                streamer.stop();
                camera.release();
                throw e;
            }

            mJpegHttpStreamer = streamer;
            camera.startPreview();
            mCamera = camera;
        }
    }

    private Optional<Pair<Integer, Integer>> findPreviewFpsRange(Camera.Parameters cameraParams) {
        // Set Preview FPS range. The range with the greatest maximum
        // is returned first.
        final List<int[]> supportedPreviewFpsRanges = cameraParams.getSupportedPreviewFpsRange();
        // However sometimes it returns null. This is a known bug
        // https://code.google.com/p/android/issues/detail?id=6271
        // In which case, we just don't set it.
        if (supportedPreviewFpsRanges != null) {
            final int[] range = supportedPreviewFpsRanges.get(0);
            return Optional.of(
                    new Pair<Integer, Integer>(range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                            range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]));
        }
        return Optional.empty();
    }

    private Pair<Integer, Integer> findPreviewSize(Camera.Parameters cameraParams) {
        final List<Camera.Size> supportedPreviewSizes = cameraParams.getSupportedPreviewSizes();
        final Camera.Size selectedPreviewSize = supportedPreviewSizes.get(previewSizeIndex);
        return new Pair<Integer, Integer>(selectedPreviewSize.width, selectedPreviewSize.height);
    }

    private void sendPreviewFrame(final byte[] data, final Camera camera, final long timestamp) {
        // Calculate the timestamp
        final long MILLI_PER_SECOND = 1000L;
        final long timestampSeconds = timestamp / MILLI_PER_SECOND;

        // Update and log the frame rate
        final long LOGS_PER_FRAME = 10L;
        numFrames++;
        if (lastTimestamp != Long.MIN_VALUE) {
            averageSpf.update(timestampSeconds - lastTimestamp);
            if (numFrames % LOGS_PER_FRAME == LOGS_PER_FRAME - 1) {
                Log.d(TAG, "FPS: " + 1.0 / averageSpf.getAverage());
            }
        }

        lastTimestamp = timestampSeconds;

        // Create JPEG
        final YuvImage image = new YuvImage(data, preview.getFormat(),
                preview.getWidth(), preview.getHeight(), null);

        image.compressToJpeg(preview.getRect(), jpegQuality, jpegOutputStream);

        mJpegHttpStreamer.streamJpeg(jpegOutputStream.getBuffer(), jpegOutputStream.getLength(),
                timestamp);

        // Clean up
        jpegOutputStream.seek(0);
        // I believe that this is thread-safe because we're not
        // calling methods in other threads. I might be wrong, the
        // documentation is not clear.
        camera.addCallbackBuffer(data);
    }
}

