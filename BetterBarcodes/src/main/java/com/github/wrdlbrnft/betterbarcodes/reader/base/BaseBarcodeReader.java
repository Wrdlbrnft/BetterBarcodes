package com.github.wrdlbrnft.betterbarcodes.reader.base;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;

import com.github.wrdlbrnft.betterbarcodes.reader.BarcodeReader;
import com.github.wrdlbrnft.betterbarcodes.reader.base.wrapper.BarcodeImageDecoder;
import com.github.wrdlbrnft.betterbarcodes.reader.base.wrapper.BarcodeResult;
import com.github.wrdlbrnft.betterbarcodes.reader.permissions.PermissionHandler;
import com.github.wrdlbrnft.betterbarcodes.reader.permissions.PermissionRequest;
import com.github.wrdlbrnft.betterbarcodes.utils.handlers.ThreadAwareHandler;

/**
 * Created with Android Studio<br>
 * User: kapeller<br>
 * Date: 25/01/16
 */
public abstract class BaseBarcodeReader implements BarcodeReader {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String TAG = "BaseBarcodeReader";

    private static final PermissionHandler DUMMY_PERMISSION_HANDLER = new PermissionHandler.Adapter() {
        @Override
        public void onNewPermissionRequest(PermissionRequest request) {
            throw new IllegalStateException("You need to set the PermissionHandler to handle runtime " +
                    "permission on devices running Android 6.0 (Marshmallow) or newer. " +
                    "You can also always just request the permission yourself before using the barcode reader."
            );
        }
    };

    private static final Callback DUMMY_READER_CALLBACK = token -> {
    };

    public static final int STATE_PERMISSION_MISSING = 0x00;
    public static final int STATE_PERMISSION_REQUIRED = 0x01;
    public static final int STATE_STOPPED = 0x02;
    public static final int STATE_PREVIEWING = 0x04;
    public static final int STATE_SCANNING = 0x08;

    @IntDef({STATE_PERMISSION_MISSING, STATE_PERMISSION_REQUIRED, STATE_STOPPED, STATE_PREVIEWING, STATE_SCANNING})
    public @interface State {
    }

    private final Context mContext;
    private PermissionHandler mPermissionHandler = DUMMY_PERMISSION_HANDLER;
    private Callback mCallback = DUMMY_READER_CALLBACK;
    private final ThreadAwareHandler mCameraHandler = new ThreadAwareHandler("BarcodeReaderCameraThread");
    private final ThreadAwareHandler mProcessingHandler = new ThreadAwareHandler("BarcodeReaderProcessingThread");
    private BarcodeImageDecoder mReader;

    @State
    private volatile int mState = STATE_STOPPED;

    protected BaseBarcodeReader(Context context) {
        mContext = context;
    }

    @Override
    public final void startPreview() {
        if (mState != STATE_STOPPED) {
            return;
        }

        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            mState = STATE_PERMISSION_REQUIRED;
            requestPermission();
            return;
        }

        startBackgroundThread();
        onStartPreview();
        mState = STATE_PREVIEWING;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private void requestPermission() {
        mPermissionHandler.onNewPermissionRequest(new PermissionRequestImpl(Manifest.permission.CAMERA, mPermissionHandler));
    }

    @Override
    public final void stopPreview() {
        if (mState > STATE_PREVIEWING) {
            stopScanning();
        }

        if (mState > STATE_STOPPED) {
            onStopPreview();
            mState = STATE_STOPPED;
            stopBackgroundThread();
        }
    }

    @Override
    public final void startScanning() {

        if (mState < STATE_STOPPED) {
            return;
        }

        if (mState < STATE_PREVIEWING) {
            startPreview();
        }

        if (mState < STATE_SCANNING) {
            onStartScanning();
            mState = STATE_SCANNING;
        }
    }

    @Override
    public final void stopScanning() {
        if (mState < STATE_SCANNING) {
            return;
        }

        onStopScanning();
        mState = STATE_PREVIEWING;
    }

    @Override
    public void setBarcodeImageDecoder(BarcodeImageDecoder decoder) {
        mReader = decoder;
    }

    protected abstract void onStartPreview();
    protected abstract void onStartScanning();
    protected abstract void onStopScanning();
    protected abstract void onStopPreview();

    private void startBackgroundThread() {
        mCameraHandler.startThread();
        mProcessingHandler.startThread();
    }

    private void stopBackgroundThread() {
        mCameraHandler.stopThread();
        mProcessingHandler.stopThread();
    }

    @State
    public int getState() {
        return mState;
    }

    protected void notifyResult(BarcodeResult result) {
        postOnMainThread(() -> mCallback.onResult(result));
        stopScanning();
        mCameraHandler.clearCallbacks(null);
    }

    protected Handler getCameraHandler() {
        return mCameraHandler;
    }

    protected Handler getProcessingHandler() {
        return mProcessingHandler;
    }

    protected void postOnMainThread(Runnable runnable) {
        MAIN_HANDLER.post(runnable);
    }

    protected void postOnMainThread(long delay, Runnable runnable) {
        MAIN_HANDLER.postDelayed(runnable, delay);
    }

    protected void postOnCameraThread(Runnable runnable) {
        mCameraHandler.post(runnable);
    }

    protected void postOnCameraThread(long delay, Runnable runnable) {
        mCameraHandler.postDelayed(runnable, delay);
    }

    protected void postOnProcessingThread(Runnable runnable) {
        if (getState() == STATE_SCANNING) {
            mProcessingHandler.post(runnable);
        }
    }

    protected void postOnProcessingThread(long delay, Runnable runnable) {
        mProcessingHandler.postDelayed(runnable, delay);
    }

    @Override
    public void setCameraPermissionHandler(PermissionHandler permissionHandler) {
        mPermissionHandler = permissionHandler;
    }

    @Override
    public void setCallback(Callback callback) {
        mCallback = callback != null ? callback : DUMMY_READER_CALLBACK;
    }

    public BarcodeImageDecoder getReader() {
        return mReader;
    }

    private class PermissionRequestImpl implements PermissionRequest {

        private final String mPermission;
        private final int mRequestCode;
        private final PermissionHandler mHandler;

        public PermissionRequestImpl(String permission, PermissionHandler handler) {
            mPermission = permission;
            mHandler = handler;
            mRequestCode = permission.hashCode() & 0xFF;
        }

        @Override
        public void start(Activity activity) {
            if (isPermissionGranted(activity)) {
                notifyGranted();
                return;
            }

            if (ActivityCompat.shouldShowRequestPermissionRationale(activity, mPermission) && mHandler.onShowRationale()) {
                return;
            }

            continueAfterRationale(activity);
        }

        @Override
        public void continueAfterRationale(Activity activity) {
            ActivityCompat.requestPermissions(activity, new String[]{mPermission}, mRequestCode);
        }

        @Override
        public void start(Fragment fragment) {
            if (isPermissionGranted(fragment.getContext())) {
                notifyGranted();
                return;
            }

            if (fragment.shouldShowRequestPermissionRationale(mPermission) && mHandler.onShowRationale()) {
                return;
            }

            continueAfterRationale(fragment);
        }

        @Override
        public void continueAfterRationale(Fragment fragment) {
            fragment.requestPermissions(new String[]{mPermission}, mRequestCode);
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            if (requestCode == mRequestCode) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mState = STATE_STOPPED;
                    startPreview();
                    notifyGranted();
                } else {
                    mState = STATE_PERMISSION_MISSING;
                    notifyDenied();
                }
            }
        }

        private boolean isPermissionGranted(Context context) {
            return ContextCompat.checkSelfPermission(context, mPermission) == PackageManager.PERMISSION_GRANTED;
        }

        private void notifyGranted() {
            mHandler.onPermissionGranted();
        }

        private void notifyDenied() {
            mHandler.onPermissionDenied();
        }
    }
}
