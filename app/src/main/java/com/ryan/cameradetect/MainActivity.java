package com.ryan.cameradetect;

import android.animation.Animator;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.serenegiant.common.BaseActivity;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.utils.ViewAnimationHelper;
import com.serenegiant.widget.CameraViewInterface;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfRect;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends BaseActivity implements CameraDialog.CameraDialogParent {
    private static final boolean DEBUG = true;	// TODO set false on release
    private static final String TAG = "MainActivity";

	private final Object mSync = new Object();
	
    /**
     * set true if you want to record movie using MediaSurfaceEncoder
     * (writing frame data into Surface camera from MediaCodec
     *  by almost same way as USBCameratest2)
     * set false if you want to record movie using MediaVideoEncoder
     */
    private static final boolean USE_SURFACE_ENCODER = false;

    /**
     * preview resolution(width)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private static final int PREVIEW_WIDTH = 1280; // 640
    /**
     * preview resolution(height)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private static final int PREVIEW_HEIGHT = 1024; //480
    /**
     * preview mode
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     * 0:YUYV, other:MJPEG
     */
    private static final int PREVIEW_MODE = 0; // YUV

    protected static final int SETTINGS_HIDE_DELAY_MS = 2500;

    /**
     * for accessing USB
     */
    private USBMonitor mUSBMonitor;
    /**
     * Handler to execute camera related methods sequentially on private thread
     */
    private UVCCameraHandler mCameraHandler;
    /**
     * for camera preview display
     */
    private CameraViewInterface mUVCCameraView;
    /**
     * for open&start / stop&close camera preview
     */
    private ToggleButton mCameraButton;
    /**
     * button for start/stop recording
     */
    private ImageButton mCaptureButton;
    private ToggleButton mScalingButton;

    private View mBrightnessButton, mContrastButton;
	private View mResetButton;
	private View mToolsLayout, mValueLayout;
	private SeekBar mSettingSeekbar;

	private Spinner mCaptureSoluionSelect;
    private ImageView mImageView;
    private TextView mInfoText;
    private TextView mWarnText;
    private boolean isScaling = false;
    private boolean isInCapturing = false;

    //private static final int CAPTURE_WIDTH = 640;
    //private static final int CAPTURE_HEIGHT = 480;
    private int[][] capture_solution = {{640,480}, {800,600},{1024,768}, {1280,1024}};
    private int mCaptureWidth = capture_solution[0][0];
    private int mCaptureHeight = capture_solution[0][1];

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(TAG, "OpenCV loaded successfully");
                } break;
                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };


    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(TAG, "onCreate:");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);
        mCameraButton = (ToggleButton)findViewById(R.id.camera_button);
        mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);

        mScalingButton = (ToggleButton)findViewById(R.id.scaling_button);
        mScalingButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
        mImageView = (ImageView)findViewById(R.id.preview_image);
        mCaptureSoluionSelect = (Spinner) findViewById(R.id.spinner_capture_solution);
        mCaptureSoluionSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                //Toast.makeText(MainActivity.this, "select: i="+i+", l="+l+", capture_solution.length="+capture_solution.length, Toast.LENGTH_SHORT).show();
                if (i>=capture_solution.length){
                    Toast.makeText(MainActivity.this, "分辨率选择错误", Toast.LENGTH_SHORT).show();
                    return;
                }
                mCaptureWidth = capture_solution[i][0];
                mCaptureHeight = capture_solution[i][1];
                synchronized (bitmap){
                    bitmap = Bitmap.createBitmap(mCaptureWidth, mCaptureHeight, Bitmap.Config.RGB_565);
                }
                updateItems();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        // 初始化图片
        mCaptureWidth = capture_solution[0][0];
        mCaptureHeight = capture_solution[0][1];
        bitmap = Bitmap.createBitmap(mCaptureWidth, mCaptureHeight, Bitmap.Config.RGB_565);

        mCaptureButton = (ImageButton)findViewById(R.id.capture_button);
        mCaptureButton.setOnClickListener(mOnClickListener);
        mCaptureButton.setVisibility(View.INVISIBLE);
        final View view = findViewById(R.id.camera_view);
//		view.setOnLongClickListener(mOnLongClickListener); //zhuohf--
        mUVCCameraView = (CameraViewInterface)view;
        mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (float)PREVIEW_HEIGHT);

        mInfoText = (TextView) findViewById(R.id.info_text);
        mWarnText = (TextView)findViewById(R.id.warn_text);

        mBrightnessButton = findViewById(R.id.brightness_button);
		mBrightnessButton.setOnClickListener(mOnClickListener);
		mContrastButton = findViewById(R.id.contrast_button);
		mContrastButton.setOnClickListener(mOnClickListener);
		mResetButton = findViewById(R.id.reset_button);
		mResetButton.setOnClickListener(mOnClickListener);
		mSettingSeekbar = (SeekBar)findViewById(R.id.setting_seekbar);
		mSettingSeekbar.setOnSeekBarChangeListener(mOnSeekBarChangeListener);

		mToolsLayout = findViewById(R.id.tools_layout);
		mToolsLayout.setVisibility(View.INVISIBLE);
		mValueLayout = findViewById(R.id.value_layout);
		mValueLayout.setVisibility(View.INVISIBLE);

		synchronized (mSync) {
	        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
	        mCameraHandler = UVCCameraHandler.createHandler(this, mUVCCameraView,
	                USE_SURFACE_ENCODER ? 0 : 1, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE);
		}
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.v(TAG, "onStart:");
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, this, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

		synchronized (mSync) {
        	mUSBMonitor.register();
		}
		if (mUVCCameraView != null) {
  			mUVCCameraView.onResume();
		}	
    }

    @Override
    protected void onStop() {
        Log.v(TAG, "onStop:");
        synchronized (mSync) {
//			mCameraHandler.stopRecording();
//			mCameraHandler.stopPreview();
    		mCameraHandler.close();	// #close include #stopRecording and #stopPreview
			mUSBMonitor.unregister();
        }
		 if (mUVCCameraView != null)
			mUVCCameraView.onPause();
		
        setCameraButton(false);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        Log.v(TAG, "onDestroy:");
        synchronized (mSync) {
            if (mCameraHandler != null) {
                mCameraHandler.setPreviewCallback(null); //zhf
                mCameraHandler.release();
                mCameraHandler = null;
            }
            if (mUSBMonitor != null) {
                mUSBMonitor.destroy();
                mUSBMonitor = null;
            }
        }
        super.onDestroy();
    }

    /**
     * event handler when click camera / capture button
     */
    private final OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(final View view) {
            switch (view.getId()) {
                case R.id.capture_button:
					synchronized (mSync) {
	                    if (mCameraHandler.isOpened() && !isInCapturing) {
	                        if (checkPermissionWriteExternalStorage() /*&& checkPermissionAudio()*/) {
	                            synchronized (bitmap){
	                                isInCapturing = true;
	                                // 传入图片，并保存
	                                // path如果不指定，就默认保存在/sdcard/DCIM/USBCameraTest目录下
	                                mCameraHandler.captureStill(bitmap, "");
	                                isInCapturing = false;
	                            }
	                        }
	                    }
					}
                    break;
                case R.id.brightness_button:
                    showSettings(UVCCamera.PU_BRIGHTNESS);
                    break;
                case R.id.contrast_button:
                    showSettings(UVCCamera.PU_CONTRAST);
                    break;
                case R.id.reset_button:
                    resetSettings();
                    break;
            }
        }
    };

    private final CompoundButton.OnCheckedChangeListener mOnCheckedChangeListener
            = new CompoundButton.OnCheckedChangeListener() {
        @Override
        public void onCheckedChanged(final CompoundButton compoundButton, final boolean isChecked) {
            switch (compoundButton.getId()) {
                case R.id.camera_button:
                    synchronized (mSync) {
					if (isChecked && (mCameraHandler != null) && !mCameraHandler.isOpened()) {
                            CameraDialog.showDialog(MainActivity.this);
                        } else {
                            mCameraHandler.close();
                            setCameraButton(false);
                        }
                    }
                    break;
                case R.id.scaling_button:
                    if (isChecked) {
                        // 打开了放大
                        isScaling = true;
                        //Toast.makeText(MainActivity.this, "当前处于放大模式", Toast.LENGTH_SHORT).show();
                    } else {
                        // 关闭放大
                        isScaling = false;
                        //Toast.makeText(MainActivity.this, "当前处于普通模式", Toast.LENGTH_SHORT).show();
                    }
                    updateItems();
                    break;
            }
        }
    };

    private void setCameraButton(final boolean isOn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mCameraButton != null) {
                    try {
                        mCameraButton.setOnCheckedChangeListener(null);
                        mCameraButton.setChecked(isOn);
                    } finally {
                        mCameraButton.setOnCheckedChangeListener(mOnCheckedChangeListener);
                    }
                }
                if (!isOn && (mCaptureButton != null)) {
                    mCaptureButton.setVisibility(View.INVISIBLE);
                }
            }
        }, 0);
        updateItems();
    }

    private void startPreview() {
		synchronized (mSync) {
			if (mCameraHandler != null) {
                final SurfaceTexture st = mUVCCameraView.getSurfaceTexture();
				mCameraHandler.setPreviewCallback(mIFrameCallback);
                mCameraHandler.startPreview(new Surface(st));
			}
		}
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCaptureButton.setVisibility(View.VISIBLE);
            }
        });
        updateItems();
    }

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Toast.makeText(MainActivity.this, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            if (DEBUG) Log.v(TAG, "onConnect:");
            synchronized (mSync) {
                if (mCameraHandler != null) {
	                mCameraHandler.open(ctrlBlock);
	                startPreview();
	                updateItems();
				}
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.v(TAG, "onDisconnect:");
            synchronized (mSync) {
                if (mCameraHandler != null) {
                    queueEvent(new Runnable() {
                        @Override
                        public void run() {
                            try{
                                // maybe throw java.lang.IllegalStateException: already released
                                mCameraHandler.setPreviewCallback(null); //zhf
                            }
                            catch(Exception e){
                                e.printStackTrace();
                            }
                            mCameraHandler.close();
                        }
                    }, 0);
				}
            }
			setCameraButton(false);
        }
        @Override
        public void onDettach(final UsbDevice device) {
            Toast.makeText(MainActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {
            setCameraButton(false);
        }
    };

    /**
     * to access from CameraDialog
     * @return
     */
    @Override
    public USBMonitor getUSBMonitor() {
		synchronized (mSync) {
			return mUSBMonitor;
		}
	}

    @Override
    public void onDialogResult(boolean canceled) {
        if (DEBUG) Log.v(TAG, "onDialogResult:canceled=" + canceled);
        if (canceled) {
            setCameraButton(false);
        }
    }

    //================================================================================
    private boolean isActive() {
        return mCameraHandler != null && mCameraHandler.isOpened();
    }

    private boolean checkSupportFlag(final int flag) {
        return mCameraHandler != null && mCameraHandler.checkSupportFlag(flag);
    }

    private int getValue(final int flag) {
        return mCameraHandler != null ? mCameraHandler.getValue(flag) : 0;
    }

    private int setValue(final int flag, final int value) {
        return mCameraHandler != null ? mCameraHandler.setValue(flag, value) : 0;
    }

    private int resetValue(final int flag) {
        return mCameraHandler != null ? mCameraHandler.resetValue(flag) : 0;
    }

    private void updateItems() {
        runOnUiThread(mUpdateItemsOnUITask, 100);
    }

    private final Runnable mUpdateItemsOnUITask = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            final int visible_active = isActive() ? View.VISIBLE : View.INVISIBLE;
            mToolsLayout.setVisibility(visible_active);
            mBrightnessButton.setVisibility(
                    checkSupportFlag(UVCCamera.PU_BRIGHTNESS)
                            ? visible_active : View.INVISIBLE);
            mContrastButton.setVisibility(
                    checkSupportFlag(UVCCamera.PU_CONTRAST)
                            ? visible_active : View.INVISIBLE);

            mScalingButton.setVisibility(visible_active);
            mInfoText.setVisibility(visible_active);
            mWarnText.setVisibility(visible_active);
            mImageView.setVisibility(visible_active);
            if(isActive()){
                mInfoText.setText("当前拍照:"+mCaptureWidth+"x"+mCaptureHeight+",   "
                        +(isScaling?"放大":"普通"));
            }
            else{
                mInfoText.setText("");
            }
        }
    };


    private int mSettingMode = -1;
    /**
     * 設定画面を表示
     * @param mode
     */
    private final void showSettings(final int mode) {
        if (DEBUG) Log.v(TAG, String.format("showSettings:%08x", mode));
        hideSetting(false);
        if (isActive()) {
            switch (mode) {
                case UVCCamera.PU_BRIGHTNESS:
                case UVCCamera.PU_CONTRAST:
                    mSettingMode = mode;
                    mSettingSeekbar.setProgress(getValue(mode));
                    ViewAnimationHelper.fadeIn(mValueLayout, -1, 0, mViewAnimationListener);
                    break;
            }
        }
    }

    private void resetSettings() {
        if (isActive()) {
            switch (mSettingMode) {
                case UVCCamera.PU_BRIGHTNESS:
                case UVCCamera.PU_CONTRAST:
                    mSettingSeekbar.setProgress(resetValue(mSettingMode));
                    break;
            }
        }
        mSettingMode = -1;
        ViewAnimationHelper.fadeOut(mValueLayout, -1, 0, mViewAnimationListener);
    }

    /**
     * 設定画面を非表示にする
     * @param fadeOut trueならばフェードアウトさせる, falseなら即座に非表示にする
     */
    protected final void hideSetting(final boolean fadeOut) {
        removeFromUiThread(mSettingHideTask);
        if (fadeOut) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ViewAnimationHelper.fadeOut(mValueLayout, -1, 0, mViewAnimationListener);
                }
            }, 0);
        } else {
            try {
                mValueLayout.setVisibility(View.GONE);
            } catch (final Exception e) {
                // ignore
            }
            mSettingMode = -1;
        }
    }

    protected final Runnable mSettingHideTask = new Runnable() {
        @Override
        public void run() {
            hideSetting(true);
        }
    };

    /**
     * 設定値変更用のシークバーのコールバックリスナー
     */
    private final SeekBar.OnSeekBarChangeListener mOnSeekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
        @Override
        public void onProgressChanged(final SeekBar seekBar, final int progress, final boolean fromUser) {
            // 設定が変更された時はシークバーの非表示までの時間を延長する
            if (fromUser) {
                runOnUiThread(mSettingHideTask, SETTINGS_HIDE_DELAY_MS);
            }
        }

        @Override
        public void onStartTrackingTouch(final SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(final SeekBar seekBar) {
            // シークバーにタッチして値を変更した時はonProgressChangedへ
            // 行かないみたいなのでここでも非表示までの時間を延長する
            runOnUiThread(mSettingHideTask, SETTINGS_HIDE_DELAY_MS);
            if (isActive() && checkSupportFlag(mSettingMode)) {
                switch (mSettingMode) {
                    case UVCCamera.PU_BRIGHTNESS:
                    case UVCCamera.PU_CONTRAST:
                        setValue(mSettingMode, seekBar.getProgress());
                        break;
                }
            }	// if (active)
        }
    };

    private final ViewAnimationHelper.ViewAnimationListener
            mViewAnimationListener = new ViewAnimationHelper.ViewAnimationListener() {
        @Override
        public void onAnimationStart(@NonNull final Animator animator, @NonNull final View target, final int animationType) {
//			if (DEBUG) Log.v(TAG, "onAnimationStart:");
        }

        @Override
        public void onAnimationEnd(@NonNull final Animator animator, @NonNull final View target, final int animationType) {
            final int id = target.getId();
            switch (animationType) {
                case ViewAnimationHelper.ANIMATION_FADE_IN:
                case ViewAnimationHelper.ANIMATION_FADE_OUT:
                {
                    final boolean fadeIn = animationType == ViewAnimationHelper.ANIMATION_FADE_IN;
                    if (id == R.id.value_layout) {
                        if (fadeIn) {
                            runOnUiThread(mSettingHideTask, SETTINGS_HIDE_DELAY_MS);
                        } else {
                            mValueLayout.setVisibility(View.GONE);
                            mSettingMode = -1;
                        }
                    } else if (!fadeIn) {
//					target.setVisibility(View.GONE);
                    }
                    break;
                }
            }
        }

        @Override
        public void onAnimationCancel(@NonNull final Animator animator, @NonNull final View target, final int animationType) {
//			if (DEBUG) Log.v(TAG, "onAnimationStart:");
        }
    };





    //================================================================================
    // 根据给定的区域裁剪
    private Bitmap cropBitmap(Bitmap bitmap, Rect rect) {
        int x = rect.x;
        int y = rect.y;
        int cropWidth = rect.width;
        int cropHeight = rect.height;
        return Bitmap.createBitmap(bitmap, x, y, cropWidth, cropHeight, null, false);
    }

    // 根据给定的宽和高进行拉伸
    private Bitmap scaleBitmap(Bitmap origin, int newWidth, int newHeight) {
        if (origin == null) {
            return null;
        }
        int height = origin.getHeight();
        int width = origin.getWidth();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);// 使用后乘
        Bitmap newBM = Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false);
        if (!origin.isRecycled()) {
            origin.recycle();
        }
        return newBM;
    }

    // 提供（相对）精确的除法运算。当发生除不尽的情况时，由scale参数指
    public double div(double v1, double v2, int scale) {
        if (scale < 0) {
            throw new IllegalArgumentException(
                    "The scale must be a positive integer or zero");
        }
        BigDecimal b1 = new BigDecimal(Double.toString(v1));
        BigDecimal b2 = new BigDecimal(Double.toString(v2));
        return b1.divide(b2, scale, BigDecimal.ROUND_HALF_UP).doubleValue();
    }

    // if you need frame data as byte array on Java side, you can use this callback method with UVCCamera#setFrameCallback
    // if you need to create Bitmap in IFrameCallback, please refer following snippet.
    private Bitmap bitmap = null;//Bitmap.createBitmap(640, 480, Bitmap.Config.RGB_565);
    private final Bitmap srcBitmap = Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.RGB_565);
    private String WarnText;

    private final IFrameCallback mIFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            frame.clear();
            if(!isActive() || isInCapturing){
                return;
            }
            if(bitmap == null){
                Toast.makeText(MainActivity.this, "错误：Bitmap为空", Toast.LENGTH_SHORT).show();
                return;
            }
            synchronized (bitmap) {
                srcBitmap.copyPixelsFromBuffer(frame);
                WarnText = "";

                if(bitmap.getWidth() != mCaptureWidth || bitmap.getHeight() != mCaptureHeight){
                    bitmap = Bitmap.createBitmap(mCaptureWidth, mCaptureHeight, Bitmap.Config.RGB_565);
                }

                if (!isScaling){
                    if(mCaptureWidth == PREVIEW_WIDTH && mCaptureHeight == PREVIEW_HEIGHT){
                        bitmap = srcBitmap;
                    }
                    else{
                        Rect cutRect = null;
                        double pre_rate = div(PREVIEW_WIDTH, PREVIEW_HEIGHT, 2); // 1.25
                        double cap_rate = div(mCaptureWidth, mCaptureHeight, 2); // 1.333
                        if (pre_rate < cap_rate) {
                            int cutHeight = PREVIEW_WIDTH * mCaptureHeight / mCaptureWidth;
                            cutRect = new Rect(0, (PREVIEW_HEIGHT-cutHeight)/2, PREVIEW_WIDTH, cutHeight);
                        }
                        else{
                            int cutWeight = PREVIEW_HEIGHT * mCaptureWidth / mCaptureHeight;
                            cutRect = new Rect((PREVIEW_WIDTH-  cutWeight)/2, 0, cutWeight, PREVIEW_HEIGHT);
                        }
                        Bitmap cropBitmap = cropBitmap(srcBitmap,cutRect);
                        bitmap = scaleBitmap(cropBitmap, mCaptureWidth, mCaptureHeight);
                    }
                }
                else{
                    Mat src = new Mat();
                    Utils.bitmapToMat(srcBitmap,src);

                    int maxR = Math.min(PREVIEW_WIDTH, PREVIEW_HEIGHT);
                    // 1280x1024 --- 245*245
                    // 640x480 - 120 x 120
                    int minArea = (int) Math.pow(maxR/8, 2);
                    int maxArea = (int) Math.pow(maxR/2, 2);
                    Mat gray=new Mat();
                    Mat threshold=new Mat();
                    Imgproc.cvtColor(src,gray,Imgproc.COLOR_BGR2GRAY);
                    Imgproc.medianBlur(gray, threshold,15);
                    Imgproc.adaptiveThreshold(threshold,threshold,255,Imgproc.ADAPTIVE_THRESH_MEAN_C , Imgproc.THRESH_BINARY,255,2 );
                    Imgproc.medianBlur(threshold, threshold,5);

                    List<MatOfPoint> contours=new ArrayList<MatOfPoint>();
                    Imgproc.findContours(threshold, contours, new Mat(),Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE);

                    boolean isDetectCircle = false;
                    Rect detectCircle = null;

                    for(int i=0;i<contours.size();i++){
                        MatOfPoint contour = contours.get(i);
                        double area = Imgproc.contourArea(contour);
                        //Log.d("zhf_Hough","i: "+ i+", area="+area);
                        if(area > minArea && area < maxArea) {
                            Rect rect = Imgproc.boundingRect(contour);
                            int x = rect.x;
                            int y = rect.y;
                            int width = rect.width;
                            int height = rect.height;
                            //Log.d("zhf_Hough", "x=" + x + ", y=" + y + ", w=" + width + ", h=" + height);
                            if(Math.abs(width - height) < 20){
                            	int circle_x = x+width/2;
    							int circle_y = y+height/2;
    							int start_x = circle_x - mCaptureWidth/2 > 0 ? circle_x - mCaptureWidth/2 : 0;
    							int start_y = circle_y - mCaptureHeight/2 > 0 ? circle_y - mCaptureHeight/2 : 0;
                                isDetectCircle = true;
                                detectCircle = new Rect(start_x, start_y, mCaptureWidth, mCaptureHeight);
                                break;
                            }
                            else{
                                // 如果找到的区域是长方形，则在用更精确的方式来查找
                                Mat copyImg = gray.submat(rect);
                                int minCopyImgR = Math.min(copyImg.width(), copyImg.height());
                                // 中值滤波
                                Mat copyImgThreshold =new Mat();
                                Imgproc.medianBlur(copyImg, copyImgThreshold,9);
                                MatOfRect circles = new MatOfRect();
                                Imgproc.HoughCircles(copyImgThreshold, circles, Imgproc.HOUGH_GRADIENT,
                                        1.7, //1.5
                                        minCopyImgR, //100
                                        100, //130
                                        52, //38
                                        minCopyImgR/4, //0
                                        minCopyImgR); //0
                                //Log.d("zhf_Hough","circles.cols()="+circles.cols());

                                if(circles.cols() != 1){
                                    break;
                                }
                                for(int j=0; j<circles.cols();j++){
                                    for(int k =0;k<circles.rows();k++){
                                        double[] c=circles.get(j,k);
                                        //Log.d("TAG","c: "+ Arrays.toString(c));
                                        int x_c = (int)c[0];
                                        int y_c = (int)c[1];
                                        int r = (int) c[2];
                                        //Log.d("zhf_Hough_Circle","x_c="+x_c +", y_c="+y_c+", r="+r);
                                        isDetectCircle = true;
                                        detectCircle = new Rect(x_c+x-mCaptureWidth/2, y_c+y-mCaptureHeight/2, mCaptureWidth, mCaptureHeight);
                                    }
                                }
                            }
                        }
                    }


                    if(!isDetectCircle) {
                        WarnText = "未检测到圆，请试着转动下镜头";
                        if(mCaptureWidth == PREVIEW_WIDTH && mCaptureHeight == PREVIEW_HEIGHT){
                            bitmap = srcBitmap;
                        }
                        else{
                            Rect cutRect = new Rect((PREVIEW_WIDTH-mCaptureWidth)/2,
                                    (PREVIEW_HEIGHT-mCaptureHeight)/2,
                                    mCaptureWidth, mCaptureHeight);
                            bitmap =  cropBitmap(srcBitmap,cutRect);
                        }
                    }
                    else{
                        WarnText = "检测到圆, 圆心坐标：("+(detectCircle.x + detectCircle.width/2)+
                                ", "+ (detectCircle.y + detectCircle.height/2)+")";
                        if(mCaptureWidth == PREVIEW_WIDTH && mCaptureHeight == PREVIEW_HEIGHT){
                            bitmap = srcBitmap;
                        }
                        else{
                            bitmap =  cropBitmap(srcBitmap,detectCircle);
                        }
                    }
                }
                //Utils.matToBitmap(src,bitmap);
                //=======================================
            }
            mImageView.post(mUpdateImageTask);
        }
    };

    private final Runnable mUpdateImageTask = new Runnable() {
        @Override
        public void run() {
            synchronized (bitmap) {
                mImageView.setImageBitmap(bitmap);
                mWarnText.setText(WarnText);
            }
        }
    };









}
