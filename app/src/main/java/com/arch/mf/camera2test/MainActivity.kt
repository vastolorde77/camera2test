package com.arch.mf.camera2test

import android.Manifest
import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.support.v4.app.ActivityCompat
import android.support.v4.app.NotificationCompat
import android.support.v4.content.FileProvider
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*

class MainActivity : AppCompatActivity() {
    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {

        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean = false
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openCamera()
        }

    }
    private var cameraDevice: CameraDevice? = null
    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice!!.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    private var captureRequestBuilder: CaptureRequest.Builder? = null

    private var cameraCaptureSessions: CameraCaptureSession? = null

    private var mBackgroundHandler: Handler? = null
    private var mBackgroundThread: HandlerThread? = null
    private var imageDimension: Size? = null
    private var imageReader: ImageReader? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textureView.surfaceTextureListener = textureListener
        button.setOnClickListener {
            takePicture()
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        stopBackgroundThread()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 200) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this@MainActivity, "Nigga give me the damn permission already", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    protected fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("Camera Background")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    protected fun stopBackgroundThread() {
        mBackgroundThread!!.quitSafely()
        try {
            mBackgroundThread!!.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun takePicture() {
        if (cameraDevice == null) {
            Log.e("OHNO", "No Camera Device")
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraDevice!!.id)
            var jpegSizes: Array<Size>? = null
            if (characteristics != null) {
                jpegSizes = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG)
            }
            var width = 640
            var height = 480
            if (jpegSizes != null && jpegSizes.isNotEmpty()) {
                width = jpegSizes[0].width
                height = jpegSizes[0].height
            }
            val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            val outputSurfaces = ArrayList<Surface>(2)
            outputSurfaces.add(reader.surface)
            outputSurfaces.add(Surface(textureView.surfaceTexture))
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // SET ROTATION
            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATION[rotation])
            // CREATE FILE
            val dir = File("${Environment.getExternalStorageDirectory()}/camera2test/temp")
            dir.mkdirs()
            val file = File("${dir.absolutePath}/pic.jpg")
            val readerListener = ImageReader.OnImageAvailableListener {
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    val buffer = image!!.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    var output: OutputStream? = null
                    try {
                        output = FileOutputStream(file)
                        output.write(bytes)
                    } finally {
                        output?.close()
                    }
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    image?.close()
                }
            }
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler)
            val captureListener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    Toast.makeText(this@MainActivity, "Saved Image ${file.absolutePath}", Toast.LENGTH_SHORT).show()
                    createNotification(file)
                    createCameraPreview()
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    Toast.makeText(this@MainActivity, "Failed Image", Toast.LENGTH_SHORT).show()
                    super.onCaptureFailed(session, request, failure)
                }
            }
            cameraDevice!!.createCaptureSession(outputSurfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {

                }

                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        session.capture(captureBuilder.build(), captureListener, mBackgroundHandler)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace()
                    }
                }
            }, mBackgroundHandler)
        } catch (e: java.lang.Exception) {
            Toast.makeText(this, "Wop wop", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        val name = "camera2test"
        val descriptionText = "the saved file"
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("24", name, importance)
        channel.description = descriptionText
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun createNotification(file: File) {
        createChannel()
        val notifyIntent = Intent(Intent.ACTION_VIEW,FileProvider.getUriForFile(this,
            "${applicationContext.packageName}.provider",file))
        notifyIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pendingIntent = PendingIntent.getActivity(applicationContext,0,notifyIntent,PendingIntent.FLAG_ONE_SHOT)
        val builder = NotificationCompat.Builder(this,"24").apply {
            setSmallIcon(R.drawable.ic_launcher_foreground)
            setContentTitle("Image saved")
            setContentText(file.absolutePath)
            setContentIntent(pendingIntent)
            setStyle(
                NotificationCompat.BigPictureStyle().bigPicture(
                    BitmapFactory.decodeFile(file.absolutePath)
                )
            )
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(0,builder.build())
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(imageDimension!!.width, imageDimension!!.height)
            val surface = Surface(texture)
            captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            captureRequestBuilder!!.addTarget(surface)
            cameraDevice!!.createCaptureSession(mutableListOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "ConfigurationFailed", Toast.LENGTH_SHORT).show()
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    cameraCaptureSessions = session
                    updatePreview()
                }
            }, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updatePreview() {
        if (null == cameraDevice) {
            Log.e("OHNO", "updatePreview error, return")
        }
        captureRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            cameraCaptureSessions!!.setRepeatingRequest(captureRequestBuilder!!.build(), null, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private var cameraId: String = ""

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cameraId = manager.cameraIdList[0]
            val charateristics = manager.getCameraCharacteristics(cameraId)
            val map = charateristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            imageDimension = map!!.getOutputSizes(SurfaceTexture::class.java)[0]
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    200
                )
                return
            }
            manager.openCamera(cameraId, stateCallback, null)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    private fun closeCamera() {
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    companion object {
        private val ORIENTATION = SparseIntArray().apply {
            append(Surface.ROTATION_0, 90)
            append(Surface.ROTATION_90, 0)
            append(Surface.ROTATION_180, 270)
            append(Surface.ROTATION_270, 180)
        }
    }
}
