package com.gamepadbridge.myscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

object DocumentAligner {

    /** Luruskan dokumen. Hasil selalu penuh (tanpa pojok putih). Kalau gagal, kembalikan gambar asli. */
    fun straighten(input: Bitmap): Bitmap {
        return try {
            val longest = max(input.width, input.height)
            val src = if (longest > 3000) {
                val s = 3000f / longest
                Bitmap.createScaledBitmap(input, (input.width * s).toInt(), (input.height * s).toInt(), true)
            } else input

            val rgba = Mat()
            Utils.bitmapToMat(src, rgba)

            // 1. crop + perspektif, hanya kalau deteksi kertas meyakinkan
            var current: Mat = warpPaper(rgba) ?: rgba

            // 2. luruskan teks, lalu potong ke area penuh
            val angle = estimateSkew(current)
            if (abs(angle) >= 0.3) current = rotateAndCrop(current, angle)

            val out = Bitmap.createBitmap(current.cols(), current.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(current, out)
            out
        } catch (e: Throwable) {
            e.printStackTrace()
            input
        }
    }

    // ---------- 1. Deteksi kertas (ketat) ----------

    private fun warpPaper(src: Mat): Mat? {
        val ratio = 800.0 / max(src.cols(), src.rows())
        val small = Mat()
        Imgproc.resize(src, small, Size(), ratio, ratio, Imgproc.INTER_AREA)

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(edges, edges, kernel)
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val imgArea = small.cols().toDouble() * small.rows()
        val candidates = contours.sortedByDescending { Imgproc.contourArea(it) }.take(6)

        for (c in candidates) {
            val area = Imgproc.contourArea(c)
            if (area < imgArea * 0.30) continue
            if (area > imgArea * 0.97) continue // hampir seluruh gambar, tidak perlu crop

            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() != 4L) continue
            if (!Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) continue

            val q = order(approx.toArray())
            if (!anglesOk(q)) continue

            val pts = q.map { Point(it.x / ratio, it.y / ratio) }
            val w = max(dist(pts[0], pts[1]), dist(pts[3], pts[2])).toInt()
            val h = max(dist(pts[0], pts[3]), dist(pts[1], pts[2])).toInt()
            if (w < 300 || h < 300) continue
            val aspect = w.toDouble() / h
            if (aspect < 0.5 || aspect > 2.0) continue

            val from = MatOfPoint2f(*pts.toTypedArray())
            val to = MatOfPoint2f(
                Point(0.0, 0.0), Point(w - 1.0, 0.0),
                Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0)
            )
            val m = Imgproc.getPerspectiveTransform(from, to)
            val dst = Mat()
            Imgproc.warpPerspective(src, dst, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
            return dst
        }
        return null
    }

    private fun anglesOk(p: Array<Point>): Boolean {
        for (i in 0 until 4) {
            val a = p[(i + 3) % 4]
            val b = p[i]
            val c = p[(i + 1) % 4]
            val v1x = a.x - b.x; val v1y = a.y - b.y
            val v2x = c.x - b.x; val v2y = c.y - b.y
            val cosA = (v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y))
            val deg = Math.toDegrees(acos(cosA.coerceIn(-1.0, 1.0)))
            if (deg < 65 || deg > 115) return false
        }
        return true
    }

    private fun order(p: Array<Point>): Array<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return arrayOf(tl, tr, br, bl)
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    // ---------- 2. Deskew (sampai ±25°) ----------

    private fun estimateSkew(src: Mat): Double {
        val ratio = 600.0 / src.cols()
        val small = Mat()
        Imgproc.resize(src, small, Size(), ratio, ratio, Imgproc.INTER_AREA)

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        val bin = Mat()
        Imgproc.adaptiveThreshold(
            gray, bin, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 31, 15.0
        )

        val mx = (bin.cols() * 0.08).toInt()
        val my = (bin.rows() * 0.08).toInt()
        val roi = bin.submat(my, bin.rows() - my, mx, bin.cols() - mx)
        val center = Point(roi.cols() / 2.0, roi.rows() / 2.0)

        fun score(angle: Double): Double {
            val m = Imgproc.getRotationMatrix2D(center, angle, 1.0)
            val rotated = Mat()
            Imgproc.warpAffine(roi, rotated, m, roi.size())
            val sums = Mat()
            Core.reduce(rotated, sums, 1, Core.REDUCE_SUM, CvType.CV_32F)
            val mean = MatOfDouble()
            val std = MatOfDouble()
            Core.meanStdDev(sums, mean, std)
            return std.toArray()[0]
        }

        var best = 0.0
        var bestScore = -1.0
        var a = -25.0
        while (a <= 25.0) {
            val s = score(a)
            if (s > bestScore) { bestScore = s; best = a }
            a += 1.0
        }
        val coarse = best
        a = coarse - 1.0
        while (a <= coarse + 1.0) {
            val s = score(a)
            if (s > bestScore) { bestScore = s; best = a }
            a += 0.1
        }
        return best
    }

    // ---------- 3. Putar, lalu potong ke persegi panjang terbesar tanpa pojok kosong ----------

    private fun rotateAndCrop(src: Mat, angle: Double): Mat {
        val w = src.cols()
        val h = src.rows()

        val m = Imgproc.getRotationMatrix2D(Point(w / 2.0, h / 2.0), angle, 1.0)
        val rotated = Mat()
        Imgproc.warpAffine(
            src, rotated, m, Size(w.toDouble(), h.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0, 255.0)
        )

        val rad = Math.toRadians(abs(angle))
        val sinA = sin(rad)
        val cosA = cos(rad)
        val widthLonger = w >= h
        val longSide = if (widthLonger) w.toDouble() else h.toDouble()
        val shortSide = if (widthLonger) h.toDouble() else w.toDouble()

        val wr: Double
        val hr: Double
        if (shortSide <= 2.0 * sinA * cosA * longSide || abs(sinA - cosA) < 1e-10) {
            val x = 0.5 * shortSide
            wr = if (widthLonger) x / sinA else x / cosA
            hr = if (widthLonger) x / cosA else x / sinA
        } else {
            val cos2a = cosA * cosA - sinA * sinA
            wr = (w * cosA - h * sinA) / cos2a
            hr = (h * cosA - w * sinA) / cos2a
        }

        // kecilkan 1% lagi supaya tepi bersih
        val cw = (wr * 0.99).toInt().coerceIn(100, w)
        val ch = (hr * 0.99).toInt().coerceIn(100, h)
        val x = (w - cw) / 2
        val y = (h - ch) / 2
        return Mat(rotated, Rect(x, y, cw, ch)).clone()
    }
}