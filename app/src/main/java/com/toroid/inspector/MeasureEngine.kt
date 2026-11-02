package com.toroid.inspector

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Pure OpenCV measurement engine — same logic as the PC app.
 * No Android dependencies here — fully testable in isolation.
 */
object MeasureEngine {

    // ── Detection mode ────────────────────────────────────────────────────────
    enum class ViewMode { TOP_VIEW, SIDE_VIEW, UNKNOWN }

    data class MeasureResult(
        val mode: ViewMode = ViewMode.UNKNOWN,
        val odMm: Double? = null,          // outer diameter
        val idMm: Double? = null,          // inner diameter (top view only)
        val htMm: Double? = null,          // height (side view only)
        val tiltDeg: Double = 0.0,
        val confidence: Double = 0.0,
        val odPx: Double? = null,
        val idPx: Double? = null,
        val htPx: Double? = null,
        val ellipseOuter: RotatedRect? = null,   // for drawing
        val ellipseInner: RotatedRect? = null,
        val htRect: Rect? = null,                // for side-view rect drawing
        val error: String? = null
    ) {
        val isValid get() = error == null && (odMm != null || htMm != null)
    }

    // ── Settings (set from SettingsActivity) ─────────────────────────────────
    var pxPerMm: Double = 0.0    // calibration factor

    // ══════════════════════════════════════════════════════════════════════════
    //  AUTO-DETECT: top-view (circle/ellipse) vs side-view (rectangle)
    // ══════════════════════════════════════════════════════════════════════════
    fun detectViewMode(gray: Mat): ViewMode {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 2.0)

        val bw = Mat()
        Imgproc.threshold(blurred, bw, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, k, Point(-1.0,-1.0), 3)
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN,  k, Point(-1.0,-1.0), 2)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(bw, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        blurred.release(); bw.release(); k.release()

        if (contours.isEmpty()) return ViewMode.UNKNOWN

        val best = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return ViewMode.UNKNOWN
        val area = Imgproc.contourArea(best)
        if (area < 2000) return ViewMode.UNKNOWN

        val rect = Imgproc.boundingRect(best)
        val aspectRatio = rect.width.toDouble() / rect.height.toDouble()

        // Side view: toroid standing on edge looks like a wide rectangle
        // aspect ratio > 1.8 and height << width  → side view
        // aspect ratio 0.7–1.4 and roughly round  → top view
        return when {
            aspectRatio > 1.6 && rect.height < gray.rows() * 0.6 -> ViewMode.SIDE_VIEW
            aspectRatio in 0.5..1.8 -> ViewMode.TOP_VIEW
            else -> ViewMode.UNKNOWN
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  TOP-VIEW: measure OD + ID
    // ══════════════════════════════════════════════════════════════════════════
    fun measureTopView(gray: Mat): MeasureResult {
        val W = gray.cols(); val H = gray.rows()

        // ── Outer ellipse ────────────────────────────────────────────────────
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 2.0)

        val bw = Mat()
        Imgproc.threshold(blurred, bw, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(13.0, 13.0))
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, k, Point(-1.0,-1.0), 4)
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN,  k, Point(-1.0,-1.0), 2)

        // Remove border blobs
        val borderMask = Mat.zeros(bw.size(), bw.type())
        Imgproc.rectangle(borderMask, Point(5.0,5.0), Point((W-5).toDouble(),(H-5).toDouble()), Scalar(255.0), -1)
        Core.bitwise_and(bw, borderMask, bw)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(bw, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        blurred.release(); k.release(); borderMask.release()

        if (contours.isEmpty()) {
            bw.release()
            return MeasureResult(mode = ViewMode.TOP_VIEW, error = "No coil detected")
        }

        val best = contours.maxByOrNull { Imgproc.contourArea(it) }!!
        val area = Imgproc.contourArea(best)
        if (area < 2000 || best.toArray().size < 5) {
            bw.release()
            return MeasureResult(mode = ViewMode.TOP_VIEW, error = "Coil too small or unclear")
        }

        val best2f = MatOfPoint2f(*best.toArray())
        val outerEllipse = Imgproc.fitEllipse(best2f)
        best2f.release()

        // Major axis = true diameter (tilt-tolerant)
        val rMajor = max(outerEllipse.size.width, outerEllipse.size.height) / 2.0
        val rMinor = min(outerEllipse.size.width, outerEllipse.size.height) / 2.0
        val aspect = rMinor / rMajor
        val tiltDeg = Math.toDegrees(acos(aspect.coerceIn(0.0, 1.0)))

        // Circularity confidence
        val peri = Imgproc.arcLength(MatOfPoint2f(*best.toArray()), true)
        val circ = if (peri > 0) 4 * PI * area / (peri * peri) else 0.0

        val cx = outerEllipse.center.x
        val cy = outerEllipse.center.y

        // ── Inner hole ───────────────────────────────────────────────────────
        val innerResult = findInnerHole(gray, bw, cx, cy, rMajor, rMinor)
        bw.release()

        // ── Convert to mm ────────────────────────────────────────────────────
        val odPx = rMajor * 2
        val odMm = if (pxPerMm > 0) odPx / pxPerMm else null
        val idPx = innerResult?.let { max(it.size.width, it.size.height) }
        val idMm = if (pxPerMm > 0 && idPx != null) idPx / pxPerMm else null

        return MeasureResult(
            mode       = ViewMode.TOP_VIEW,
            odMm       = odMm,
            idMm       = idMm,
            tiltDeg    = tiltDeg,
            confidence = circ.coerceIn(0.0, 1.0),
            odPx       = odPx,
            idPx       = idPx,
            ellipseOuter = outerEllipse,
            ellipseInner = innerResult
        )
    }

    private fun findInnerHole(gray: Mat, bwOuter: Mat, cx: Double, cy: Double,
                               rMajor: Double, rMinor: Double): RotatedRect? {
        val W = gray.cols(); val H = gray.rows()
        val searchR = rMajor * 0.75

        val x0 = max(0, (cx - searchR).toInt())
        val y0 = max(0, (cy - searchR).toInt())
        val x1 = min(W, (cx + searchR).toInt())
        val y1 = min(H, (cy + searchR).toInt())
        if (x1 - x0 < 10 || y1 - y0 < 10) return null

        val crop = gray.submat(y0, y1, x0, x1)
        val blurC = Mat()
        Imgproc.GaussianBlur(crop, blurC, Size(9.0, 9.0), 2.0)

        // Bright region = hole (background light shining through)
        val medianVal = Core.mean(blurC).`val`[0]
        val brightThresh = (medianVal + 30).coerceAtMost(255.0)

        val bwBright = Mat()
        Imgproc.threshold(blurC, bwBright, brightThresh, 255.0, Imgproc.THRESH_BINARY)
        blurC.release()

        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(bwBright, bwBright, Imgproc.MORPH_CLOSE, k, Point(-1.0,-1.0), 2)
        Imgproc.morphologyEx(bwBright, bwBright, Imgproc.MORPH_OPEN,  k, Point(-1.0,-1.0), 2)
        k.release()

        // Elliptical mask — only look in inner zone
        val mask = Mat.zeros(bwBright.size(), bwBright.type())
        val ax = (searchR * 0.72 * rMinor / rMajor).toInt()
        val ay = (searchR * 0.72).toInt()
        Imgproc.ellipse(mask, Point((x1-x0)/2.0, (y1-y0)/2.0),
            Size(ax.toDouble(), ay.toDouble()), 0.0, 0.0, 360.0, Scalar(255.0), -1)
        Core.bitwise_and(bwBright, mask, bwBright)
        mask.release()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(bwBright, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        bwBright.release()

        val minArea = PI * (searchR * 0.08) * (searchR * 0.08)
        var bestCnt: MatOfPoint? = null
        var bestScore = Double.MAX_VALUE

        for (cnt in contours) {
            val area = Imgproc.contourArea(cnt)
            if (area < minArea) continue
            val M = Imgproc.moments(cnt)
            if (M.m00 == 0.0) continue
            val bx = M.m10 / M.m00 + x0
            val by = M.m01 / M.m00 + y0
            val dist = hypot(bx - cx, by - cy)
            val score = dist / sqrt(area)
            if (score < bestScore) { bestScore = score; bestCnt = cnt }
        }

        if (bestCnt == null || bestCnt.toArray().size < 5) return null

        val cnt2f = MatOfPoint2f(*bestCnt.toArray())
        val ellipse = Imgproc.fitEllipse(cnt2f)
        cnt2f.release()

        val ir = max(ellipse.size.width, ellipse.size.height) / 2.0
        // Sanity: must be 5–85% of outer radius
        if (ir < rMajor * 0.05 || ir > rMajor * 0.85) return null

        // Translate ellipse centre back to full-image coords
        return RotatedRect(
            Point(ellipse.center.x + x0, ellipse.center.y + y0),
            ellipse.size,
            ellipse.angle
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SIDE-VIEW: measure Height
    // ══════════════════════════════════════════════════════════════════════════
    fun measureSideView(gray: Mat): MeasureResult {
        val W = gray.cols(); val H = gray.rows()

        // Pre-process: blur → threshold → morphology → mask borders
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 2.0)

        val bw = Mat()
        Imgproc.threshold(blurred, bw, 0.0, 255.0,
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        blurred.release()

        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(11.0, 11.0))
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, k, Point(-1.0, -1.0), 3)
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN,  k, Point(-1.0, -1.0), 2)
        k.release()

        // Remove border noise
        val borderMask = Mat.zeros(bw.size(), bw.type())
        Imgproc.rectangle(borderMask, Point(5.0, 5.0),
            Point((W - 5).toDouble(), (H - 5).toDouble()), Scalar(255.0), -1)
        Core.bitwise_and(bw, borderMask, bw)
        borderMask.release()

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(bw, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        bw.release()

        if (contours.isEmpty())
            return MeasureResult(mode = ViewMode.SIDE_VIEW, error = "No coil detected (side view)")

        val best = contours.maxByOrNull { Imgproc.contourArea(it) }!!
        val area = Imgproc.contourArea(best)
        if (area < 2000)
            return MeasureResult(mode = ViewMode.SIDE_VIEW, error = "Coil too small")

        // Use bounding rect for height reference
        val rect = Imgproc.boundingRect(best)
        // minAreaRect gives tilt-tolerant size — shorter side = coil height
        val minRect = Imgproc.minAreaRect(MatOfPoint2f(*best.toArray()))
        val htPx = min(minRect.size.width, minRect.size.height).toDouble()

        val htMm = if (pxPerMm > 0) htPx / pxPerMm else null

        // Confidence: closeness of perimeter to a rectangle
        val peri = Imgproc.arcLength(MatOfPoint2f(*best.toArray()), true)
        val rectPerim = 2.0 * (rect.width + rect.height)
        val conf = if (rectPerim > 0) (peri / rectPerim).coerceIn(0.0, 1.0) else 0.0

        return MeasureResult(
            mode       = ViewMode.SIDE_VIEW,
            htMm       = htMm,
            htPx       = htPx,
            confidence = conf,
            htRect     = rect
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  STABILITY CHECKER
    //  Returns true if last N results are consistent (low variance)
    // ══════════════════════════════════════════════════════════════════════════
    private val recentOd  = ArrayDeque<Double>(6)
    private val recentId  = ArrayDeque<Double>(6)
    private val recentHt  = ArrayDeque<Double>(6)

    fun addToStabilityBuffer(res: MeasureResult) {
        res.odMm?.let { if (recentOd.size >= 5) recentOd.removeFirst(); recentOd.addLast(it) }
        res.idMm?.let { if (recentId.size >= 5) recentId.removeFirst(); recentId.addLast(it) }
        res.htMm?.let { if (recentHt.size >= 5) recentHt.removeFirst(); recentHt.addLast(it) }
    }

    fun isStable(mode: ViewMode): Boolean {
        return when (mode) {
            ViewMode.TOP_VIEW  -> recentOd.size >= 4 && variance(recentOd) < 1.0  // <1mm² variance
            ViewMode.SIDE_VIEW -> recentHt.size >= 4 && variance(recentHt) < 0.5
            ViewMode.UNKNOWN   -> false
        }
    }

    fun stableProgress(mode: ViewMode): Float {
        val buf = if (mode == ViewMode.TOP_VIEW) recentOd else recentHt
        if (buf.size < 2) return 0f
        val v = variance(buf)
        val target = if (mode == ViewMode.TOP_VIEW) 1.0 else 0.5
        return (1.0 - (v / (target * 3)).coerceIn(0.0, 1.0)).toFloat()
    }

    fun getStableResult(): Triple<Double?, Double?, Double?> {
        val od = if (recentOd.isNotEmpty()) recentOd.average() else null
        val id = if (recentId.isNotEmpty()) recentId.average() else null
        val ht = if (recentHt.isNotEmpty()) recentHt.average() else null
        return Triple(od, id, ht)
    }

    fun clearBuffers() {
        recentOd.clear(); recentId.clear(); recentHt.clear()
    }

    private fun variance(buf: Collection<Double>): Double {
        if (buf.size < 2) return Double.MAX_VALUE
        val mean = buf.average()
        return buf.sumOf { (it - mean).pow(2) } / buf.size
    }
}
