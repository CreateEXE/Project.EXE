package com.android.exe.render

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import java.nio.ByteBuffer

class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "AvatarView"
    }

    private var renderer: FilamentRenderer? = null

    init {
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (renderer == null && width > 0 && height > 0) {
                renderer = FilamentRenderer(context, this)
            }
        }
    }

    fun loadAvatar(assetPath: String, onLoaded: (Boolean) -> Unit) {
        renderer?.loadAvatar(assetPath, onLoaded) ?: onLoaded(false)
    }

    fun updateAnimation(boneMatrices: ByteBuffer?, blendshapes: FloatArray?) {
        renderer?.let {
            boneMatrices?.let { matrices -> it.setBoneMatrices(matrices) }
            blendshapes?.let { weights -> it.updateBlendshapes(weights) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer?.release()
        renderer = null
    }
}
