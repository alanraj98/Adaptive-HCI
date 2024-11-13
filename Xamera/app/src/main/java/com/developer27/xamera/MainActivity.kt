package com.developer27.xamera
import com.developer27.xamera.databinding.ActivityMainBinding

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import kotlinx.coroutines.*

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), VideoProcessorCallback {

    // Binding object to access views
    private lateinit var viewBinding: ActivityMainBinding

    // SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences

    // Camera2 API variables
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraId: String
    private var previewSize: Size? = null
    private var videoSize: Size? = null
    private var cameraCaptureSessions: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null

    // Handler for camera operations
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // MediaRecorder for video recording
    private lateinit var mediaRecorder: MediaRecorder
    private lateinit var videoFile: File
    private var isRecordingVideo = false

    // Zoom variables
    private var zoomLevel = 1.0f
    private val maxZoom = 10.0f
    private var sensorArraySize: Rect? = null

    // Permissions required at startup
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    // Declare the ActivityResultLauncher for permissions
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestStoragePermissionLauncher: ActivityResultLauncher<String>

    private lateinit var textureView: TextureView

    // Initialize the ActivityResultLauncher for video selection
    private lateinit var videoPickerLauncher: ActivityResultLauncher<Intent>

    // Create an instance of VideoProcessor
    private lateinit var videoProcessor: VideoProcessor

    // Flag to track which camera is currently active
    private var isFrontCamera = false

    private var shutterSpeed: Long = 1000000000L / 60 // Default to 1/60s in nanoseconds

    // Handler for camera preview surface texture availability
    private val textureListener = object : TextureView.SurfaceTextureListener {
        @SuppressLint("MissingPermission")
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            // Open the camera here if permissions are granted
            if (allPermissionsGranted()) {
                openCamera()
            } else {
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Handle size changes if needed
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    // CameraDevice state callback
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            // This is called when the camera is open
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
            finish()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflate the layout and bind views
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Initialize SharedPreferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        sharedPreferences.registerOnSharedPreferenceChangeListener { prefs, key ->
            if (key == "shutter_speed") {
                // Get the updated shutter speed setting
                val shutterSpeedSetting = prefs.getString("shutter_speed", "60")?.toInt() ?: 60
                shutterSpeed = 1000000000L / shutterSpeedSetting // Convert to nanoseconds
                updateShutterSpeed() // Apply the new shutter speed
            }
        }

        // Load initial shutter speed from SharedPreferences
        val shutterSpeedSetting = sharedPreferences.getString("shutter_speed", "60")?.toInt() ?: 60
        shutterSpeed = 1000000000L / shutterSpeedSetting // Convert to nanoseconds

        // Initialize CameraManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Initialize TextureView
        textureView = viewBinding.viewFinder

        // Initialize the ActivityResultLauncher for requesting permissions
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: false
            val audioPermissionGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

            if (cameraPermissionGranted && audioPermissionGranted) {
                if (textureView.isAvailable) {
                    openCamera()
                } else {
                    textureView.surfaceTextureListener = textureListener
                }
            } else {
                Toast.makeText(this, "Camera and Audio permissions are required.", Toast.LENGTH_SHORT).show()
                // Optionally disable camera functionality or guide the user
            }
        }

        // Initialize the ActivityResultLauncher for requesting storage permission
        requestStoragePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                openVideoPicker()
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    showPermissionRationale()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        }

        // Initialize storage permission request launcher
        requestStoragePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                openVideoPicker()
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    showPermissionRationale()
                } else {
                    showPermissionDeniedDialog()
                }
            }
        }

        // Check permissions and request if not granted
        if (allPermissionsGranted()) {
            textureView.surfaceTextureListener = textureListener
        } else {
            // Request permissions
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        videoProcessor = VideoProcessor(this, this) // Init VideoProc Class

        // Initialize video picker launcher
        videoPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { videoUri ->
                    videoProcessor.processVideoWithOpenCV(videoUri) // Call the method on the VideoProcessor instance
                } ?: Toast.makeText(this, "No video selected", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up video picker button
        viewBinding.processVideoButton.setOnClickListener {
            checkAndRequestStoragePermission()
        }

        // Make the Start Recording button visible
        viewBinding.startTrackingButton.visibility = View.VISIBLE

        // Set up listener for the Start Recording button
        viewBinding.startTrackingButton.setOnClickListener {
            if (isRecordingVideo) {
                stopRecordingVideo()
            } else {
                startRecordingVideo()
            }
        }

        // Set up listener for the "Switch Camera" button
        viewBinding.switchCameraButton.setOnClickListener {
            switchCamera()
        }

        // Set up zoom controls
        setupZoomControls()

        // Set up listener for about button
        viewBinding.aboutButton.setOnClickListener {
            val intent = Intent(this, AboutXameraActivity::class.java)
            startActivity(intent)
        }

        // Set up settings button to open the settings activity
        viewBinding.settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivityForResult(intent, SETTINGS_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS_REQUEST_CODE && resultCode == RESULT_OK) {
            // Reload the updated shutter speed
            val shutterSpeedSetting = sharedPreferences.getString("shutter_speed", "60")?.toInt() ?: 60
            shutterSpeed = 1000000000L / shutterSpeedSetting // Convert to nanoseconds
            Toast.makeText(this, "Shutter speed updated", Toast.LENGTH_SHORT).show()
            updateShutterSpeed()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            if (allPermissionsGranted()) {
                openCamera()
                updateShutterSpeed()  // Apply the shutter speed when the camera opens
            } else {
                // Request permissions
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        } else {
            textureView.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        if (isRecordingVideo) {
            stopRecordingVideo()
        }
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // Function to check if all required permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // Function to open the camera
    @RequiresPermission(Manifest.permission.CAMERA)
    private fun openCamera() {
        try {
            cameraId = getCameraId()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                Toast.makeText(this, "Cannot get available preview sizes", Toast.LENGTH_SHORT).show()
                return
            }
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java))
            videoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder::class.java))

            // Use the Handler instead of Executor
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    // Function to choose the optimal size for preview
    private fun chooseOptimalSize(choices: Array<Size>): Size {
        // For simplicity, choose the first size available
        return choices[0]
    }

    // Function to get the camera ID based on the selected camera (front/back)
    private fun getCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && !isFrontCamera) {
                return id
            } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && isFrontCamera) {
                return id
            }
        }
        return cameraManager.cameraIdList[0] // Default to first camera
    }

    // Function to switch between front and back camera
    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        closeCamera()
        reopenCamera()
    }

    // Function to reopen the camera after switching
    @SuppressLint("MissingPermission")
    private fun reopenCamera() {
        if (textureView.isAvailable) {
            if (allPermissionsGranted()) {
                openCamera()
            } else {
                // Request permissions
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
        } else {
            textureView.surfaceTextureListener = textureListener
        }
    }

    // Function to create camera preview
    private fun createCameraPreview() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
            val surface = Surface(texture)
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder!!.addTarget(surface)
            // Apply current zoom
            applyZoom()

            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        cameraCaptureSessions = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(this@MainActivity, "Configuration change", Toast.LENGTH_SHORT).show()
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    // Function to update camera preview
    private fun updatePreview() {
        if (cameraDevice == null) return
        try {
            // Do not recreate captureRequestBuilder; just update it
            captureRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            updateShutterSpeed()
            applyFlashIfEnabled()
            applyLightingMode()
            cameraCaptureSessions?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    // Function to start background thread
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    // Function to stop background thread
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    // Function to close the camera
    private fun closeCamera() {
        cameraCaptureSessions?.close()
        cameraCaptureSessions = null
        cameraDevice?.close()
        cameraDevice = null
        if (this::mediaRecorder.isInitialized) {
            mediaRecorder.release()
        }
    }

    // Function to start video recording
    private fun startRecordingVideo() {
            if (cameraDevice == null || !textureView.isAvailable || previewSize == null) {
                return
            }
            try {
                closePreviewSession()
                setUpMediaRecorder()
                val texture = textureView.surfaceTexture!!
                texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
                val previewSurface = Surface(texture)
                val recorderSurface = mediaRecorder.surface
                val surfaces = listOf(previewSurface, recorderSurface)

                captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                captureRequestBuilder!!.addTarget(previewSurface)
                captureRequestBuilder!!.addTarget(recorderSurface)

                // Apply rolling shutter based on SharedPreferences setting
                applyRollingShutter()

                applyFlashIfEnabled()
                applyLightingMode()
                applyZoom()

                cameraDevice!!.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            cameraCaptureSessions = session
                            try {
                                cameraCaptureSessions?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
                                runOnUiThread {
                                    viewBinding.startTrackingButton.text = "Stop Tracking"
                                    viewBinding.startTrackingButton.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.red))
                                    isRecordingVideo = true
                                    mediaRecorder.start()
                                }
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Toast.makeText(this@MainActivity, "Failed to start camera session", Toast.LENGTH_SHORT).show()
                        }
                    },
                    backgroundHandler
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
    }

        private fun applyRollingShutter() {
        // Retrieve the selected shutter speed setting from SharedPreferences
        val shutterSpeedSetting = sharedPreferences.getString("shutter_speed", "15")?.toInt() ?: 15
        val shutterSpeedValue = if (shutterSpeedSetting >= 5) 1000000000L / shutterSpeedSetting else 0L

        captureRequestBuilder?.apply {
            if (shutterSpeedValue > 0) {
                // Apply the calculated shutter speed based on user setting
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeedValue)
                Toast.makeText(this@MainActivity, "Rolling shutter speed applied: $shutterSpeedSetting Hz", Toast.LENGTH_SHORT).show()
            } else {
                // If "Off" is selected, use default automatic mode
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                Toast.makeText(this@MainActivity, "Rolling shutter off", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun updateShutterSpeed() {
        // Retrieve the selected shutter speed from SharedPreferences
        val shutterSpeedSetting = sharedPreferences.getString("shutter_speed", "15")?.toInt() ?: 15
        val shutterSpeedValue = 1000000000L / shutterSpeedSetting // Convert to nanoseconds

        captureRequestBuilder?.apply {
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF) // Manual control

            if (shutterSpeedSetting >= 5) {
                // Apply the calculated shutter speed for selected Hz values
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterSpeedValue)
            } else {
                // Default to auto control if shutter speed is not set (shouldn't be lower than 5 Hz)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }

            // Retrieve the camera's exposure compensation range
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val exposureRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

            // Apply exposure compensation based on selected shutter speed value
            var exposureCompensation = 0
            if (exposureRange != null && shutterSpeedSetting >= 5) {
                exposureCompensation = when (shutterSpeedSetting) {
                    5 -> exposureRange.lower // Lowest compensation for 5 Hz
                    10 -> exposureRange.lower / 2 // Reduced compensation for 10 Hz
                    15 -> exposureRange.lower // Minimal compensation for 15 Hz
                    50 -> exposureRange.lower
                    100 -> (exposureRange.lower + exposureRange.upper) / 4
                    200 -> exposureRange.upper / 2
                    250 -> (3 * exposureRange.upper) / 4
                    500 -> exposureRange.upper
                    else -> 0
                }
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
            }

            try {
                // Apply the capture request with updated settings
                cameraCaptureSessions?.setRepeatingRequest(build(), null, backgroundHandler)

                // Log and Toast messages to verify the correct values
                val shutterSpeedText = "1/$shutterSpeedSetting Hz"
                Log.d("RollingShutterUpdate", "Set shutter speed to $shutterSpeedText with exposure compensation: $exposureCompensation")
                Toast.makeText(
                    this@MainActivity,
                    "Shutter speed set to $shutterSpeedText with exposure compensation: $exposureCompensation",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
    }

    // Function to stop video recording
    private fun stopRecordingVideo() {
        // Stop recording
        isRecordingVideo = false
        try {
            cameraCaptureSessions?.stopRepeating()
            cameraCaptureSessions?.abortCaptures()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        mediaRecorder.stop()
        mediaRecorder.reset()
        // Save the video to the gallery
        saveVideoToGallery(videoFile)
        runOnUiThread {
            // Update UI
            viewBinding.startTrackingButton.text = "Start Tracking"
            viewBinding.startTrackingButton.backgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.blue))
            // Show where the video was saved
            Toast.makeText(this, "Video saved to gallery", Toast.LENGTH_SHORT).show()
        }
        // Restart the camera preview
        createCameraPreview()
    }

    // Function to set up MediaRecorder
    private fun setUpMediaRecorder() {
        mediaRecorder = MediaRecorder()

        // Set the audio and video sources
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        // Use CamcorderProfile to set output format and encoders
        val profile = if (CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_HIGH)) {
            CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH)
        } else {
            CamcorderProfile.get(CamcorderProfile.QUALITY_LOW)
        }
        mediaRecorder.setProfile(profile)

        // Set the output file
        videoFile = createVideoFile()
        mediaRecorder.setOutputFile(videoFile.absolutePath)

        // Set the orientation hint
        val rotation = windowManager.defaultDisplay.rotation
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        val deviceRotation = ORIENTATIONS.get(rotation)
        val totalRotation = (sensorOrientation + deviceRotation + 360) % 360
        mediaRecorder.setOrientationHint(totalRotation)

        mediaRecorder.prepare()
    }

    // Function to create video file
    private fun createVideoFile(): File {
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return File.createTempFile("VID_$timestamp", ".mp4", storageDir)
    }

    // Function to save video to gallery
    private fun saveVideoToGallery(videoFile: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, videoFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Xamera-Videos")
            } else {
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val file = File(moviesDir, videoFile.name)
                videoFile.copyTo(file, overwrite = true)
                videoFile.delete()
                return
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            contentResolver.openOutputStream(uri).use { outputStream ->
                videoFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream!!)
                }
            }
            // Delete the temp file
            videoFile.delete()
        }
    }

    // Function to close the preview session
    private fun closePreviewSession() {
        cameraCaptureSessions?.close()
        cameraCaptureSessions = null
    }

    // Function to apply flash if enabled
    private fun applyFlashIfEnabled() {
        val isFlashEnabled = sharedPreferences.getBoolean("enable_flash", false)
        captureRequestBuilder?.set(
            CaptureRequest.FLASH_MODE,
            if (isFlashEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
        )
    }

    // Function to apply lighting mode
    private fun applyLightingMode() {
        val lightingMode = sharedPreferences.getString("lighting_mode", "normal")
        val compensationRange = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val exposureCompensation = when (lightingMode) {
            "low_light" -> compensationRange?.lower ?: 0
            "high_light" -> compensationRange?.upper ?: 0
            "normal" -> 0
            else -> 0
        }
        captureRequestBuilder?.set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            exposureCompensation
        )
    }

    // Function to set up zoom controls with continuous zoom
    private fun setupZoomControls() {
        val zoomHandler = Handler(Looper.getMainLooper())
        var zoomInRunnable: Runnable? = null
        var zoomOutRunnable: Runnable? = null

        // Continuous zoom in on long press
        viewBinding.zoomInButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomInRunnable = object : Runnable {
                        override fun run() {
                            zoomIn()
                            zoomHandler.postDelayed(this, 50) // Adjust delay as needed
                        }
                    }
                    zoomHandler.post(zoomInRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomHandler.removeCallbacks(zoomInRunnable!!)
                    true
                }
                else -> false
            }
        }

        // Continuous zoom out on long press
        viewBinding.zoomOutButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomOutRunnable = object : Runnable {
                        override fun run() {
                            zoomOut()
                            zoomHandler.postDelayed(this, 50) // Adjust delay as needed
                        }
                    }
                    zoomHandler.post(zoomOutRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomHandler.removeCallbacks(zoomOutRunnable!!)
                    true
                }
                else -> false
            }
        }
    }

    // Function to zoom in
    private fun zoomIn() {
        if (zoomLevel < maxZoom) {
            zoomLevel += 0.1f
            applyZoom()
        }
    }

    // Function to zoom out
    private fun zoomOut() {
        if (zoomLevel > 1.0f) {
            zoomLevel -= 0.1f
            applyZoom()
        }
    }

    // Function to apply zoom
    private fun applyZoom() {
        if (sensorArraySize == null || captureRequestBuilder == null) return
        val ratio = 1 / zoomLevel
        val croppedWidth = sensorArraySize!!.width() * ratio
        val croppedHeight = sensorArraySize!!.height() * ratio
        val zoomRect = Rect(
            ((sensorArraySize!!.width() - croppedWidth) / 2).toInt(),
            ((sensorArraySize!!.height() - croppedHeight) / 2).toInt(),
            ((sensorArraySize!!.width() + croppedWidth) / 2).toInt(),
            ((sensorArraySize!!.height() + croppedHeight) / 2).toInt()
        )
        captureRequestBuilder!!.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
        cameraCaptureSessions?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
    }

    // Function to check and request storage permission, specifically for Motorola devices
    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Use AppOpsManager to check permission more directly
            val appOpsManager = getSystemService(AppOpsManager::class.java)
            val mode = appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE,
                applicationInfo.uid,
                packageName
            )

            if (mode != AppOpsManager.MODE_ALLOWED && !allPermissionsGranted()) {
                requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            } else {
                openVideoPicker()
            }
        } else {
            openVideoPicker()
        }
    }

    // Function to open video picker
    private fun openVideoPicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        videoPickerLauncher.launch(intent)
    }

    // Callback method from VideoProcessorCallback
    override fun onVideoProcessed(videoUri: Uri) {
        playVideo(videoUri) // Play the processed video
    }

    // Function to play the video
    private fun playVideo(videoUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(videoUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    // Function to show permission rationale
    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage("This app needs storage access to select videos.")
            .setPositiveButton("OK") { _, _ ->
                requestStoragePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    // Function to show permission denied dialog
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Please enable storage permission from settings.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    companion object {
        private const val SETTINGS_REQUEST_CODE = 1

        // Tag for logging
        private const val TAG = "Xamera"

        // File name format for videos
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"

        // Orientation constants
        private val ORIENTATIONS = SparseIntArray()
        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }
    }
}
