/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 */
package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
    private var results: PoseLandmarkerResult? = null
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var imageWidth = 1
    private var imageHeight = 1
    private var postureZone = PostureZone.SAFE

    init {
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeCap = Paint.Cap.ROUND
        pointPaint.style = Paint.Style.FILL
        applyZoneColor()
    }

    fun clear() {
        results = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val landmarks = results?.landmarks()?.firstOrNull() ?: return
        applyZoneColor()

        // MediaPipe Pose indices: face 0..10, shoulders 11..12, hips 23..24.
        (0..12).forEach { index ->
            landmarks.getOrNull(index)?.let { point ->
                pointPaint.color = landmarkColor(index)
                canvas.drawCircle(mapX(point.x()), mapY(point.y()), POINT_RADIUS, pointPaint)
            }
        }
        listOf(23, 24).forEach { index ->
            landmarks.getOrNull(index)?.let { point ->
                pointPaint.color = landmarkColor(index)
                canvas.drawCircle(mapX(point.x()), mapY(point.y()), POINT_RADIUS, pointPaint)
            }
        }

        drawConnection(canvas, landmarks, 11, 12)
        drawConnection(canvas, landmarks, 11, 23)
        drawConnection(canvas, landmarks, 12, 24)
        drawConnection(canvas, landmarks, 23, 24)
        drawConnection(canvas, landmarks, 7, 3)
        drawConnection(canvas, landmarks, 6, 8)
        drawConnection(canvas, landmarks, 9, 10)

        val facePathIndices = listOf(3, 2, 1, 0, 4, 5, 6)
        val facePath = Path()
        facePathIndices.forEachIndexed { pathIndex, landmarkIndex ->
            landmarks.getOrNull(landmarkIndex)?.let { point ->
                if (pathIndex == 0) facePath.moveTo(mapX(point.x()), mapY(point.y()))
                else facePath.lineTo(mapX(point.x()), mapY(point.y()))
            }
        }
        canvas.drawPath(facePath, linePaint)
    }

    private fun drawConnection(
        canvas: Canvas,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        startIndex: Int,
        endIndex: Int,
    ) {
        val start = landmarks.getOrNull(startIndex) ?: return
        val end = landmarks.getOrNull(endIndex) ?: return
        canvas.drawLine(mapX(start.x()), mapY(start.y()), mapX(end.x()), mapY(end.y()), linePaint)
    }

    private fun applyZoneColor() {
        val color = when (postureZone) {
            PostureZone.SAFE -> ContextCompat.getColor(context, R.color.headup_safe)
            PostureZone.WARNING -> ContextCompat.getColor(context, R.color.headup_warning)
            PostureZone.DANGER -> ContextCompat.getColor(context, R.color.headup_danger)
        }
        linePaint.color = color
        pointPaint.color = color
    }

    private fun landmarkColor(index: Int): Int = when (index) {
        1, 2, 3, 4, 5, 6 -> ContextCompat.getColor(context, R.color.headup_primary)
        7, 8 -> ContextCompat.getColor(context, R.color.headup_purple)
        9, 10 -> ContextCompat.getColor(context, R.color.headup_orange)
        else -> linePaint.color
    }

    private fun mapX(normalizedX: Float): Float = normalizedX * imageWidth * scaleFactor + offsetX

    private fun mapY(normalizedY: Float): Float = normalizedY * imageHeight * scaleFactor + offsetY

    fun setResults(
        poseLandmarkerResults: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE,
        zone: PostureZone = PostureZone.SAFE,
    ) {
        results = poseLandmarkerResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth
        postureZone = zone

        // PreviewView uses FIT_CENTER, so the overlay must use the same letterbox transform.
        scaleFactor = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        offsetX = (width - imageWidth * scaleFactor) / 2f
        offsetY = (height - imageHeight * scaleFactor) / 2f
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 7f
        private const val POINT_RADIUS = 7f
    }
}
