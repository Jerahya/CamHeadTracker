package io.github.jerahya.camheadtracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.pow

class FaceResult(
    val matrix: FloatArray?,
    val landmarks: FloatArray?,
    val image: Bitmap?,
    val imageWidth: Int,
    val imageHeight: Int,
    val timestampMs: Long,
)

class FaceTracker(
    context: Context,
    private val onResult: (FaceResult) -> Unit,
) {
    @Volatile var keepImage = false

    private val landmarker: FaceLandmarker = create(context, Delegate.GPU)
        ?: create(context, Delegate.CPU)
        ?: throw IllegalStateException("Cannot create face landmarker")

    private fun create(context: Context, delegate: Delegate): FaceLandmarker? = try {
        val base = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, input ->
                val matrices = result.facialTransformationMatrixes()
                val matrix = if (matrices.isPresent && matrices.get().isNotEmpty()) matrices.get()[0] else null
                val faces = result.faceLandmarks()
                val landmarks = if (faces.isNotEmpty()) {
                    val face = faces[0]
                    FloatArray(face.size * 2).also { xy ->
                        face.forEachIndexed { i, p ->
                            xy[i * 2] = p.x()
                            xy[i * 2 + 1] = p.y()
                        }
                    }
                } else null
                val image = if (keepImage) shownImage else null
                onResult(FaceResult(matrix, landmarks, image, input.width, input.height, result.timestampMs()))
            }
            .setErrorListener { e -> Log.e(TAG, "Landmarker error", e) }
            .build()
        FaceLandmarker.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.w(TAG, "Landmarker with $delegate failed", e)
        null
    }

    // MediaPipe writes into the bitmap it gets, so display a copy
    @Volatile private var shownImage: Bitmap? = null
    @Volatile var boost = 1f
    private var lutBoost = 0f
    private val lut = IntArray(256)
    private var row = ByteArray(0)
    private var pixels = IntArray(0)

    // Luma only. CameraX RGBA output is striped on some devices
    fun analyze(image: ImageProxy) {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        if (row.size != width)
            row = ByteArray(width)
        if (pixels.size != width * height)
            pixels = IntArray(width * height)
        val gain = boost
        if (gain != lutBoost) {
            for (v in 0 until 256) {
                val out = (255.0 * (v / 255.0).pow(1.0 / gain)).toInt().coerceIn(0, 255)
                lut[v] = -0x1000000 or (out * 0x010101)
            }
            lutBoost = gain
        }
        for (y in 0 until height) {
            buffer.position(y * plane.rowStride)
            buffer.get(row, 0, width)
            val offset = y * width
            for (x in 0 until width)
                pixels[offset + x] = lut[row[x].toInt() and 0xFF]
        }
        val rotation = image.imageInfo.rotationDegrees
        image.close()

        var bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (rotation != 0) {
            val turn = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, turn, false)
        }
        shownImage = if (keepImage) bitmap.copy(Bitmap.Config.ARGB_8888, false) else null
        landmarker.detectAsync(BitmapImageBuilder(bitmap).build(), SystemClock.uptimeMillis())
    }

    fun close() {
        landmarker.close()
    }

    private companion object {
        const val TAG = "FaceTracker"
    }
}
