/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.otaliastudios.cameraview;

import android.graphics.SurfaceTexture;
import android.opengl.EGLContext;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.RequiresApi;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;


/**
 * -- from grafika --
 *
 * Encode a movie from frames rendered from an external texture image.
 * <p>
 * The object wraps an encoder running on a dedicated thread.  The various control messages
 * may be sent from arbitrary threads (typically the app UI thread).  The encoder thread
 * manages both sides of the encoder (feeding and draining); the only external input is
 * the GL texture.
 * <p>
 * The design is complicated slightly by the need to create an EGL context that shares state
 * with a view that gets restarted if (say) the device orientation changes.  When the view
 * in question is a GLSurfaceView, we don't have full control over the EGL context creation
 * on that side, so we have to bend a bit backwards here.
 * <p>
 * To use:
 * <ul>
 * <li>create TextureMovieEncoder object
 * <li>create an Config
 * <li>call TextureMovieEncoder#startRecording() with the config
 * <li>call TextureMovieEncoder#setTextureId() with the texture object that receives frames
 * <li>for each frame, after latching it with SurfaceTexture#updateTexImage(),
 *     call TextureMovieEncoder#frameAvailable().
 * </ul>
 *
 * TODO: tweak the API (esp. textureId) so it's less awkward for simple use cases.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class OldMediaEncoder implements Runnable {
    private static final String TAG = OldMediaEncoder.class.getSimpleName();

    private static final int MSG_START_RECORDING = 0;
    private static final int MSG_STOP_RECORDING = 1;
    private static final int MSG_FRAME_AVAILABLE = 2;
    private static final int MSG_SET_TEXTURE_ID = 3;
    private static final int MSG_QUIT = 4;

    // ----- accessed exclusively by encoder thread -----
    private EglWindowSurface mInputWindowSurface;
    private EglCore mEglCore;
    private EglViewport mFullScreen;
    private int mTextureId;
    private int mFrameNum = -1; // Important
    private OldMediaEncoderCore mVideoEncoder;
    private float mTransformationScaleX = 1F;
    private float mTransformationScaleY = 1F;
    private int mTransformationRotation = 0;

    // ----- accessed by multiple threads -----
    private volatile EncoderHandler mHandler;

    private final Object mLooperReadyLock = new Object(); // guards ready/running
    private boolean mLooperReady;
    private boolean mRunning;

    /**
     * Encoder configuration.
     * <p>
     * Object is immutable, which means we can safely pass it between threads without
     * explicit synchronization (and don't need to worry about it getting tweaked out from
     * under us).
     * <p>
     */
    static class Config {
        final File mOutputFile;
        final int mWidth;
        final int mHeight;
        final int mBitRate;
        final int mFrameRate;
        final int mRotation;
        final float mScaleX;
        final float mScaleY;
        final EGLContext mEglContext;
        final String mMimeType;

        Config(File outputFile, int width, int height,
              int bitRate, int frameRate,
              int rotation,
              float scaleX, float scaleY,
              String mimeType,
              EGLContext sharedEglContext) {
            mOutputFile = outputFile;
            mWidth = width;
            mHeight = height;
            mBitRate = bitRate;
            mFrameRate = frameRate;
            mEglContext = sharedEglContext;
            mScaleX = scaleX;
            mScaleY = scaleY;
            mRotation = rotation;
            mMimeType = mimeType;
        }

        @Override
        public String toString() {
            return "Config: " + mWidth + "x" + mHeight + " @" + mBitRate +
                    " to '" + mOutputFile.toString() + "' ctxt=" + mEglContext;
        }
    }

    private void prepareEncoder(Config config) {
        OldMediaEncoderCore.VideoConfig videoConfig = new OldMediaEncoderCore.VideoConfig(
                config.mWidth, config.mHeight, config.mBitRate, config.mFrameRate,
                0, // The video encoder rotation does not work, so we apply it here using Matrix.rotateM().
                config.mMimeType);
        try {
            mVideoEncoder = new OldMediaEncoderCore(videoConfig, config.mOutputFile);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mEglCore = new EglCore(config.mEglContext, EglCore.FLAG_RECORDABLE);
        mInputWindowSurface = new EglWindowSurface(mEglCore, mVideoEncoder.getInputSurface(), true);
        mInputWindowSurface.makeCurrent(); // drawing will happen on the InputWindowSurface, which
                                           // is backed by mVideoEncoder.getInputSurface()
        mFullScreen = new EglViewport();
        mTransformationScaleX = config.mScaleX;
        mTransformationScaleY = config.mScaleY;
        mTransformationRotation = config.mRotation;
    }

    private void releaseEncoder() {
        mVideoEncoder.release();
        if (mInputWindowSurface != null) {
            mInputWindowSurface.release();
            mInputWindowSurface = null;
        }
        if (mFullScreen != null) {
            mFullScreen.release(true);
            mFullScreen = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
    }

    /**
     * Tells the video recorder to start recording.  (Call from non-encoder thread.)
     * <p>
     * Creates a new thread, which will create an encoder using the provided configuration.
     * <p>
     * Returns after the recorder thread has started and is ready to accept Messages.  The
     * encoder may not yet be fully configured.
     */
    public void startRecording(Config config) {
        Log.d(TAG, "Encoder: startRecording()");
        synchronized (mLooperReadyLock) {
            if (mRunning) {
                Log.w(TAG, "Encoder thread already running");
                return;
            }
            mRunning = true;
            new Thread(this, "TextureMovieEncoder").start();
            while (!mLooperReady) {
                try {
                    mLooperReadyLock.wait();
                } catch (InterruptedException ie) {
                    // ignore
                }
            }
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_START_RECORDING, config));
    }

    /**
     * Tells the video recorder to stop recording.  (Call from non-encoder thread.)
     * <p>
     * Returns immediately; the encoder/muxer may not yet be finished creating the movie.
     * <p>
     */
    public void stopRecording(Runnable onStop) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_RECORDING, onStop));
        mHandler.sendMessage(mHandler.obtainMessage(MSG_QUIT));
        // We don't know when these will actually finish (or even start).  We don't want to
        // delay the UI thread though, so we return immediately.
    }

    /**
     * Returns true if recording has been started.
     */
    public boolean isRecording() {
        synchronized (mLooperReadyLock) {
            return mRunning;
        }
    }

    /**
     * Tells the video recorder that a new frame is available.  (Call from non-encoder thread.)
     * <p>
     * This function sends a message and returns immediately.  This isn't sufficient -- we
     * don't want the caller to latch a new frame until we're done with this one -- but we
     * can get away with it so long as the input frame rate is reasonable and the encoder
     * thread doesn't stall.
     * <p>
     * TODO: either block here until the texture has been rendered onto the encoder surface,
     * or have a separate "block if still busy" method that the caller can execute immediately
     * before it calls updateTexImage().  The latter is preferred because we don't want to
     * stall the caller while this thread does work.
     */
    public void frameAvailable(SurfaceTexture st) {
        synchronized (mLooperReadyLock) {
            if (!mLooperReady) {
                return;
            }
        }

        float[] transform = new float[16]; // TODO - avoid alloc every frame. Not easy, need a pool
        st.getTransformMatrix(transform);
        long timestamp = st.getTimestamp();
        if (timestamp == 0) {
            // Seeing this after device is toggled off/on with power button.  The
            // first frame back has a zero timestamp.
            // MPEG4Writer thinks this is cause to abort() in native code, so it's very
            // important that we just ignore the frame.
            Log.w(TAG, "HEY: got SurfaceTexture with timestamp of zero");
            return;
        }

        mHandler.sendMessage(mHandler.obtainMessage(MSG_FRAME_AVAILABLE,
                (int) (timestamp >> 32), (int) timestamp, transform));
    }

    /**
     * Tells the video recorder what texture name to use.  This is the external texture that
     * we're receiving camera previews in.  (Call from non-encoder thread.)
     * <p>
     * TODO: do something less clumsy
     */
    public void setTextureId(int id) {
        synchronized (mLooperReadyLock) {
            if (!mLooperReady) return;
        }
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_TEXTURE_ID, id, 0, null));
    }

    /**
     * Encoder thread entry point.  Establishes Looper/Handler and waits for messages.
     * <p>
     * @see java.lang.Thread#run()
     */
    @Override
    public void run() {
        // Establish a Looper for this thread, and define a Handler for it.
        Looper.prepare();
        synchronized (mLooperReadyLock) {
            mHandler = new EncoderHandler(this);
            mLooperReady = true;
            mLooperReadyLock.notify();
        }
        Looper.loop();
        Log.d(TAG, "Encoder thread exiting");
        synchronized (mLooperReadyLock) {
            mLooperReady = mRunning = false;
            mHandler = null;
        }
    }

    /**
     * Handles encoder state change requests.  The handler is created on the encoder thread.
     */
    private static class EncoderHandler extends Handler {
        private WeakReference<OldMediaEncoder> mWeakEncoder;

        public EncoderHandler(OldMediaEncoder encoder) {
            mWeakEncoder = new WeakReference<>(encoder);
        }

        @Override  // runs on encoder thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Object obj = inputMessage.obj;

            OldMediaEncoder encoder = mWeakEncoder.get();
            if (encoder == null) {
                Log.w(TAG, "EncoderHandler.handleMessage: encoder is null");
                return;
            }

            switch (what) {
                case MSG_START_RECORDING:
                    encoder.mFrameNum = 0;
                    Config config = (Config) obj;
                    encoder.prepareEncoder(config);
                    break;
                case MSG_STOP_RECORDING:
                    encoder.mFrameNum = -1;
                    encoder.mVideoEncoder.drainEncoder(true);
                    encoder.releaseEncoder();
                    ((Runnable) obj).run();
                    break;
                case MSG_FRAME_AVAILABLE:
                    if (encoder.mFrameNum < 0) break;
                    encoder.mFrameNum++;
                    long timestamp = (((long) inputMessage.arg1) << 32) | (((long) inputMessage.arg2) & 0xffffffffL);
                    float[] transform = (float[]) obj;

                    // We must scale this matrix like GlCameraPreview does, because it might have some cropping.
                    // Scaling takes place with respect to the (0, 0, 0) point, so we must apply a Translation to compensate.

                    float scaleX = encoder.mTransformationScaleX;
                    float scaleY = encoder.mTransformationScaleY;
                    float scaleTranslX = (1F - scaleX) / 2F;
                    float scaleTranslY = (1F - scaleY) / 2F;
                    Matrix.translateM(transform, 0, scaleTranslX, scaleTranslY, 0);
                    Matrix.scaleM(transform, 0, scaleX, scaleY, 1);

                    // We also must rotate this matrix. In GlCameraPreview it is not needed because it is a live
                    // stream, but the output video, must be correctly rotated based on the device rotation at the moment.
                    // Rotation also takes place with respect to the origin (the Z axis), so we must
                    // translate to origin, rotate, then back to where we were.

                    Matrix.translateM(transform, 0, 0.5F, 0.5F, 0);
                    Matrix.rotateM(transform, 0, encoder.mTransformationRotation, 0, 0, 1);
                    Matrix.translateM(transform, 0, -0.5F, -0.5F, 0);

                    encoder.mVideoEncoder.drainEncoder(false);
                    encoder.mFullScreen.drawFrame(encoder.mTextureId, transform);
                    encoder.mInputWindowSurface.setPresentationTime(timestamp);
                    encoder.mInputWindowSurface.swapBuffers();
                    break;
                case MSG_SET_TEXTURE_ID:
                    encoder.mTextureId = inputMessage.arg1;
                    break;
                case MSG_QUIT:
                    Looper.myLooper().quit();
                    break;
                default:
                    throw new RuntimeException("Unhandled msg what=" + what);
            }
        }
    }

}