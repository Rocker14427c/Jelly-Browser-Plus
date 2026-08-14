/*
 * SPDX-FileCopyrightText: 2026 Browser+
 * SPDX-License-Identifier: Apache-2.0
 *
 * Chrome-style edge-swipe navigation: swiping inward from the left edge of
 * the page goes back, from the right edge goes forward. The gesture only
 * starts inside a configurable edge zone (so page scrolling, taps and
 * content gestures are unaffected) and shows a dim scrim + directional
 * chevron while dragging, turning accent-colored once the release will
 * commit the navigation.
 */
package org.lineageos.jelly.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import org.lineageos.jelly.webview.WebViewExt

/**
 * Transparent feedback layer drawn above the WebView. It never consumes
 * touches (not clickable), so the WebView underneath receives everything;
 * it only renders the scrim/chevron while a swipe gesture is in progress.
 */
class EdgeSwipeOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var progress = 0f
    private var direction = 0
    private val density = resources.displayMetrics.density
    private val scrimPaint = Paint().apply { color = Color.BLACK }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var resetAnimator: ValueAnimator? = null

    fun showProgress(side: Int, value: Float) {
        resetAnimator?.cancel()
        direction = side
        progress = value.coerceIn(0f, 1.15f)
        invalidate()
    }

    fun reset() {
        resetAnimator?.cancel()
        progress = 0f
        invalidate()
    }

    /** Slides the overlay back out when the gesture was released too early. */
    fun animateReset() {
        resetAnimator?.cancel()
        resetAnimator = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = 160
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()

        // dim scrim
        scrimPaint.alpha = (progress.coerceIn(0f, 1f) * 70).toInt()
        canvas.drawRect(0f, 0f, w, h, scrimPaint)

        // chevron following the finger; accent color once past the threshold
        val p = progress.coerceIn(0f, 1f)
        chevronPaint.color = if (progress >= 1f) {
            Color.rgb(138, 180, 248)
        } else {
            Color.argb((60 + p * 190).toInt(), 255, 255, 255)
        }
        val travel = density * 56f * p
        val cx = if (direction < 0) density * 22f + travel else w - density * 22f - travel
        val cy = h / 2f
        val len = density * 10f
        val gap = density * 6f
        if (direction < 0) {
            // '<' at the left edge (back)
            canvas.drawLine(cx + gap, cy - len, cx - gap, cy, chevronPaint)
            canvas.drawLine(cx - gap, cy, cx + gap, cy + len, chevronPaint)
        } else {
            // '>' at the right edge (forward)
            canvas.drawLine(cx - gap, cy - len, cx + gap, cy, chevronPaint)
            canvas.drawLine(cx + gap, cy, cx - gap, cy + len, chevronPaint)
        }
    }
}

/**
 * Detects and drives the edge-swipe gesture on a WebView.
 *
 * Lifecycle of one gesture:
 *  - DOWN inside the left/right edge zone (and only if the WebView can
 *    navigate that way) arms the gesture; the event is never consumed.
 *  - Once the finger moves horizontally past touch-slop (and clearly more
 *    horizontally than vertically, so page scrolling keeps working), the
 *    gesture claims the events and updates the overlay.
 *  - UP past the threshold navigates; UP before it animates back.
 *  - A vertical move cancels and hands everything back to the page.
 */
class EdgeSwipeGesture(
    private val webView: WebViewExt,
    private val overlay: EdgeSwipeOverlay,
    private val edgeWidthPx: Float
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(webView.context).scaledTouchSlop
    private val density = webView.context.resources.displayMetrics.density

    private var side = 0          // -1 = left/back, +1 = right/forward, 0 = none
    private var claimed = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var threshold = 0f

    fun detach() {
        webView.setOnTouchListener(null)
        overlay.reset()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            if (claimed) overlay.animateReset()
            side = 0
            claimed = false
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                side = 0
                val w = v.width.toFloat()
                if (w <= 0f) return false
                val x = event.x
                side = when {
                    x <= edgeWidthPx && webView.canGoBack() -> -1
                    x >= w - edgeWidthPx && webView.canGoForward() -> 1
                    else -> 0
                }
                if (side != 0) {
                    startX = x
                    startY = event.y
                    lastX = x
                    threshold = (w * 0.22f).coerceIn(density * 96f, density * 240f)
                }
                false // never consume the down event
            }

            MotionEvent.ACTION_MOVE -> {
                if (side == 0) return false
                val dx = event.x - startX
                val dy = event.y - startY
                if (!claimed) {
                    val ax = abs(dx)
                    val ay = abs(dy)
                    if (ax > slop && ax > ay * 1.5f) {
                        claimed = (side < 0 && dx > 0) || (side > 0 && dx < 0)
                        if (!claimed) side = 0
                    } else if (ay > slop && ay > ax) {
                        side = 0 // vertical scroll — the page keeps the gesture
                    }
                    if (!claimed) return false
                }
                lastX = event.x
                val raw = if (side < 0) dx else -dx
                overlay.showProgress(side, raw / threshold)
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!claimed) {
                    side = 0
                    return false
                }
                val s = side
                val dx = lastX - startX
                val raw = if (s < 0) dx else -dx
                val commit = raw / threshold >= 1f
                side = 0
                claimed = false
                overlay.reset()
                if (commit) {
                    if (s < 0) webView.goBack() else webView.goForward()
                } else {
                    overlay.animateReset()
                }
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (claimed) overlay.animateReset()
                side = 0
                claimed = false
                false
            }

            else -> false
        }
    }
}
