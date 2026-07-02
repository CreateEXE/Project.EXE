/**
 * FilamentRenderer.kt: High-performance 3D avatar renderer using Google Filament
 */
package com.android.exe.render

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.android.filament.Filament
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.Camera
import com.google.android.filament.ColorSpace
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.FilamentAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class FilamentRenderer(
    private val context: Context,
    private val surfaceView: SurfaceView
) : SurfaceHolder.Callback {

    companion object {
        private const val TAG = "FilamentRenderer"
        private const val TARGET_FPS = 60
        private const val MAX_BONES = 256
        private const val MAX_BLENDSHAPES = 128
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var currentAsset: FilamentAsset? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var boneMatrices: ByteBuffer? = null
    private var blendshapeWeights = FloatArray(MAX_BLENDSHAPES)
    private val isRunning = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private val frameTimeNanos = (1_000_000_000L / TARGET_FPS).toLong()
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        startRenderThread(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: $width x $height")
        scope.launch {
            withContext(Dispatchers.Default) {
                camera?.setProjection(
                    fovInDegrees = 45.0,
                    aspect = width.toFloat() / height.toFloat(),
                    near = 0.1f,
                    far = 100.0f
                )
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        stopRenderThread()
    }

    private fun startRenderThread(surface: Surface) {
        if (isRunning.getAndSet(true)) {
            return
        }

        renderThread = Thread {
            try {
                initializeFilament()
                renderLoop(surface)
            } catch (e: Exception) {
                Log.e(TAG, "Render thread exception", e)
            } finally {
                cleanupFilament()
                isRunning.set(false)
            }
        }.apply {
            name = "Filament-RenderThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun initializeFilament() {
        Log.d(TAG, "Initializing Filament engine")
        Filament.init()
        engine = Engine.create()
        renderer = engine?.createRenderer()
        scene = engine?.createScene()
        view = engine?.createView().apply {
            scene = this@FilamentRenderer.scene
            camera = engine?.createCamera().apply {
                setExposure(16.0f, 16.0f, 1.0f / 125.0f)
                lookAt(doubleArrayOf(0.0, 0.0, 2.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0))
            }
        }
        camera = view?.camera
        assetLoader = AssetLoader(engine!!, ColorSpace.LINEAR, null)
        resourceLoader = ResourceLoader(engine!!)
        renderer?.setClearOptions(floatArrayOf(0f, 0f, 0f, 1f), true, true)
        Log.i(TAG, "Filament initialization complete")
    }

    private fun renderLoop(surface: Surface) {
        Log.d(TAG, "Starting render loop")
        val swapChain = engine?.createSwapChain(surface) ?: return
        var frameCount = 0L
        var lastFrameTimeNanos = System.nanoTime()

        while (isRunning.get()) {
            val frameStartNanos = System.nanoTime()
            try {
                if (boneMatrices != null) updateSkeletalAnimation()
                if (blendshapeWeights.any { it != 0f }) updateBlendshapesGPU()
                if (renderer?.beginFrame(swapChain) == true) {
                    renderer?.render(view)
                    renderer?.endFrame()
                }
                val frameElapsedNanos = System.nanoTime() - frameStartNanos
                val sleepNanos = frameTimeNanos - frameElapsedNanos
                if (sleepNanos > 0) Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                frameCount++
            } catch (e: Exception) {
                Log.e(TAG, "Frame rendering error", e)
                Thread.sleep(16)
            }
        }
        engine?.destroySwapChain(swapChain)
    }

    private fun updateSkeletalAnimation() {
        boneMatrices?.rewind()
        Log.v(TAG, "Updated skeleton")
    }

    private fun updateBlendshapesGPU() {
        Log.v(TAG, "Updated blendshapes")
    }

    fun loadAvatar(assetPath: String, onLoaded: (success: Boolean) -> Unit) {
        scope.launch {
            try {
                Log.i(TAG, "Loading avatar: $assetPath")
                val assetBuffer = withContext(Dispatchers.IO) { loadAssetFromAssets(assetPath) }
                if (assetBuffer == null) {
                    onLoaded(false)
                    return@launch
                }
                withContext(Dispatchers.Default) {
                    currentAsset = assetLoader?.createAsset(assetBuffer)
                    resourceLoader?.asyncBeginLoad(currentAsset)
                    resourceLoader?.waitForCompletion()
                    scene?.addEntity(currentAsset!!.root)
                    Log.i(TAG, "Avatar loaded: $assetPath")
                    onLoaded(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading avatar", e)
                onLoaded(false)
            }
        }
    }

    private fun loadAssetFromAssets(assetPath: String): ByteBuffer? {
        return try {
            val bytes = context.assets.open(assetPath).readBytes()
            ByteBuffer.wrap(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading asset", e)
            null
        }
    }

    fun setBoneMatrices(matrices: ByteBuffer) {
        this.boneMatrices = matrices
    }

    fun updateBlendshapes(weights: FloatArray) {
        System.arraycopy(weights, 0, blendshapeWeights, 0, minOf(weights.size, MAX_BLENDSHAPES))
    }

    private fun stopRenderThread() {
        isRunning.set(false)
        renderThread?.join(5000)
    }

    private fun cleanupFilament() {
        try {
            currentAsset?.let { assetLoader?.destroyAsset(it) }
            view?.let { engine?.destroyView(it) }
            camera?.let { engine?.destroyCamera(it) }
            scene?.let { engine?.destroyScene(it) }
            renderer?.let { engine?.destroyRenderer(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    fun release() {
        stopRenderThread()
        scope.launch { cleanupFilament() }
    }
}
