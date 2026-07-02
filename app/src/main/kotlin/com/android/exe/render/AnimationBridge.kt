package com.android.exe.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AnimationBridge {
    companion object {
        private const val MAX_BONES = 256
        private const val MAX_BLENDSHAPES = 128
        private const val MATRIX_SIZE = 16
    }

    private val boneMatricesBuffer: ByteBuffer = ByteBuffer.allocateDirect(
        MAX_BONES * MATRIX_SIZE * 4
    ).apply { order(ByteOrder.nativeOrder()) }

    private val blendshapeWeightsBuffer: ByteBuffer = ByteBuffer.allocateDirect(
        MAX_BLENDSHAPES * 4
    ).apply { order(ByteOrder.nativeOrder()) }

    fun getBoneMatricesBuffer(): ByteBuffer = boneMatricesBuffer.asReadOnlyBuffer()

    fun setBoneMatrices(numBones: Int, matrices: FloatArray) {
        if (numBones > MAX_BONES || matrices.size != numBones * MATRIX_SIZE) return
        boneMatricesBuffer.rewind()
        boneMatricesBuffer.asFloatBuffer().put(matrices)
        boneMatricesBuffer.rewind()
    }

    fun setBlendshapeWeights(weights: FloatArray) {
        if (weights.size > MAX_BLENDSHAPES) return
        blendshapeWeightsBuffer.rewind()
        blendshapeWeightsBuffer.asFloatBuffer().put(weights)
        blendshapeWeightsBuffer.rewind()
    }

    fun getBlendshapeWeights(): FloatArray {
        blendshapeWeightsBuffer.rewind()
        val weights = FloatArray(MAX_BLENDSHAPES)
        blendshapeWeightsBuffer.asFloatBuffer().get(weights)
        return weights
    }
}
