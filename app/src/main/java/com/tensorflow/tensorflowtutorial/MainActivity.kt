package com.tensorflow.tensorflowtutorial

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Camera
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.log

class MainActivity : AppCompatActivity() , GestureRecognizerListener {
    private lateinit var cameraProviderFuture : ListenableFuture<ProcessCameraProvider>
    private lateinit var  previewView :PreviewView
    private lateinit var gestureRecognizer: GestureRecognizer
    private  lateinit var gestureTextView:TextView
    private lateinit var inferenceTextView: TextView
    val gestureRecognizerListener: GestureRecognizerListener? = null
    private lateinit var displayOpponentImage:ImageView

    @SuppressLint("UnsafeOptInUsageError")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        gestureTextView = findViewById(R.id.gesture_text)
        inferenceTextView = findViewById(R.id.inference_text)
        displayOpponentImage = findViewById(R.id.imageView)
//        Model
        val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath("C:\\Users\\alexm\\AndroidStudioProjects\\TensorflowTutorial\\app\\src\\main\\assets\\gesture_recognizer.task")
        val baseOptions = baseOptionsBuilder.build()

        val minHandDetectionConfidence = 0.2
        val minHandTrackingConfidence = 0.2
        val minHandPresenceConfidence = 0.2

        val optionsBuilder =
            GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(minHandDetectionConfidence.toFloat())
                .setMinTrackingConfidence(minHandTrackingConfidence.toFloat())
                .setMinHandPresenceConfidence(minHandPresenceConfidence.toFloat())
                .setResultListener(this::returnLivestreamResult)
                .setErrorListener(this::returnLivestreamError)
                .setRunningMode(RunningMode.LIVE_STREAM)

        val options = optionsBuilder.build()
        gestureRecognizer =
            GestureRecognizer.createFromOptions(this, options)




//        Camera Code
        previewView = findViewById(R.id.previewView)
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        ProcessCameraProvider.configureInstance(Camera2Config.defaultConfig());
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()

            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))

    }

    private fun returnLivestreamResult(
        result: GestureRecognizerResult, input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
//        Log.d(TAG , result.gestures().toString())
//        inferenceTextView.text = finishTimeMs.toString()
//        gestureTextView.text = result.gestures().get(0).toString()
        onResults(
            ResultBundle(
                listOf(result), inferenceTime, input.height, input.width
            )
        )
    }
    private fun returnLivestreamError(error: RuntimeException) {
        onError(
            error.message ?: "An unknown error has occurred"
        )
    }


    fun bindPreview(cameraProvider : ProcessCameraProvider) {
        var preview : Preview = Preview.Builder()
            .build()

        var cameraSelector : CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()
        var cameraExecutor = Executors.newSingleThreadExecutor()
        preview.setSurfaceProvider(previewView.getSurfaceProvider())

        val imageAnalysis = ImageAnalysis.Builder()
            // enable the following line if RGBA output is needed.
             .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(Size(1280, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis.setAnalyzer( cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            // insert your code here.
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

            // after done, release the ImageProxy object
            val matrix = Matrix().apply {
                // Rotate the frame received from the camera to be in the same direction as it'll be shown
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                // flip image since we only support front camera
                postScale(
                    -1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat()
                )
            }

            // Rotate bitmap to match what our model expects
            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )
            val frameTime = SystemClock.uptimeMillis()
            val mImage = BitmapImageBuilder(rotatedBitmap).build()

            recognizeAsync(mImage , frameTime)

            imageProxy.close()
        })



        var camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector , imageAnalysis , preview)
    }

    fun recognizeAsync(mpImage: MPImage, frameTime: Long) {
        // As we're using running mode LIVE_STREAM, the recognition result will
        // be returned in returnLivestreamResult function
        gestureRecognizer?.recognizeAsync(mpImage, frameTime)
    }

    override fun onResults(resultBundle: ResultBundle) {
        Log.d(TAG , "ResultBundle"+resultBundle.results.first().gestures().toString())
        runOnUiThread {

                // Show result of recognized gesture
                val gestureCategories = resultBundle.results.first().gestures()
                if (gestureCategories.isNotEmpty()) {

                    gestureTextView.text = String.format("Gesture: %s",gestureCategories.get(0).get(0).categoryName())
                    if(gestureCategories.get(0).get(0).categoryName().toString() =="rock"){
                        displayOpponentImage.setImageResource(R.drawable.rock)
                    }
                    if(gestureCategories.get(0).get(0).categoryName().toString() =="scissors"){
                        displayOpponentImage.setImageResource(R.drawable.scissor)
                    }
                    if(gestureCategories.get(0).get(0).categoryName().toString() =="paper"){
                        displayOpponentImage.setImageResource(R.drawable.paper)
                    }

                } else {

                }

                    inferenceTextView.text =
                    String.format("Inference Time : %d ms", resultBundle.inferenceTime)
                // Pass necessary information to OverlayView for drawing on the canvas

            }

    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread {
            Toast.makeText( this, error, Toast.LENGTH_SHORT).show()
        }
    }
}

data class ResultBundle(
    val results: List<GestureRecognizerResult>,
    val inferenceTime: Long,
    val inputImageHeight: Int,
    val inputImageWidth: Int,
)
interface GestureRecognizerListener {
    fun onError(error: String, errorCode: Int = 0)
    fun onResults(resultBundle: ResultBundle)
}
