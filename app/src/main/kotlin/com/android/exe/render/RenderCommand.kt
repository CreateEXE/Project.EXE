package com.android.exe.render

import java.nio.ByteBuffer

seal class RenderCommand {
    data class LoadAvatar(val assetPath: String, val onComplete: (Boolean) -> Unit) : RenderCommand()
    data class UpdateBoneMatrices(val matrices: ByteBuffer) : RenderCommand()
    data class UpdateBlendshapes(val weights: FloatArray) : RenderCommand()
}
