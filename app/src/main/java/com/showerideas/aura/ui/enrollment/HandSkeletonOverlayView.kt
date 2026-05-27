package com.showerideas.aura.ui.enrollment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.showerideas.aura.R

/**
 * Task 71 — Ghost skeleton overlay for the WaitingForPalm anchor state.
 *
 * Draws a translucent open-palm skeleton on top of the camera preview to guide
 * the user toward the correct hand shape. Shows the wrist (landmark 0), 5 MCP
 * joints (5,9,13,17,21-area), and 5 fingertip joints (4,8,12,16,20) as the key
 * 11 reference points from the MediaPipe 21-point hand model.
 *
 * Bones connect each MCP to the corresponding fingertip — 5 finger rays + wrist
 * anchor — giving a recognizable open-palm ghost shape.
 *
 * Uses [android.graphics.Canvas] only. No ARCore dependency.
 */
class HandSkeletonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        alpha = 100   // translucent ghost
        color = 0xFF00E5FF.toInt()  // cyan
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 140
        color = 0xFF00E5FF.toInt()
    }

    /** When false the overlay is invisible (used in Capturing/Done states). */
    var showSkeleton: Boolean = true
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (!showSkeleton) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Normalised open-palm landmark positions for a centred hand
        // Approx positions in [0,1] x [0,1] relative to the view
        val landmarks = arrayOf(
            floatArrayOf(0.50f, 0.85f),  // 0 wrist
            floatArrayOf(0.35f, 0.70f),  // 5 index MCP
            floatArrayOf(0.45f, 0.68f),  // 9 middle MCP
            floatArrayOf(0.55f, 0.68f),  // 13 ring MCP
            floatArrayOf(0.64f, 0.70f),  // 17 pinky MCP
            floatArrayOf(0.28f, 0.30f),  // 8 index tip
            floatArrayOf(0.43f, 0.25f),  // 12 middle tip
            floatArrayOf(0.55f, 0.26f),  // 16 ring tip
            floatArrayOf(0.65f, 0.32f),  // 20 pinky tip
            floatArrayOf(0.22f, 0.60f)   // 4 thumb tip
        )

        // Draw finger bones: wrist→MCP→tip for each finger
        val fingerBones = arrayOf(
            intArrayOf(0, 1, 5),  // index
            intArrayOf(0, 2, 6),  // middle
            intArrayOf(0, 3, 7),  // ring
            intArrayOf(0, 4, 8),  // pinky
            intArrayOf(0, 9)      // thumb (simplified)
        )

        for (bone in fingerBones) {
            for (i in 0 until bone.size - 1) {
                val a = landmarks[bone[i]]
                val b = landmarks[bone[i + 1]]
                canvas.drawLine(a[0] * w, a[1] * h, b[0] * w, b[1] * h, bonePaint)
            }
        }

        // Draw joints
        for (lm in landmarks) {
            canvas.drawCircle(lm[0] * w, lm[1] * h, 6f, jointPaint)
        }
    }
}
