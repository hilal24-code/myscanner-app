package com.gamepadbridge.myscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

object DocumentEnhancer {

    /** Hilangkan bayangan, netralkan warna kertas, haluskan. Kalau gagal, kembalikan gambar asli. */
    fun enhance(input: Bitmap): Bitmap {
        return try {
            run(input) ?: input
        } catch (e: Throwable) {
            e.printStackTrace()
            input
        }
    }

    private fun run(input: Bitmap): Bitmap? {
        val longest = max(input.width, input.height)
        val src = if (longest > 2200) {
            val s = 2200f / longest
            Bitmap.createScaledBitmap(input, (input.width * s).toInt(), (input.height * s).toInt(), true)
        } else input

        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        // ---- versi kecil untuk menghitung peta cahaya ----
        val r = 900.0 / max(rgb.cols(), rgb.rows())
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), r, r, Imgproc.INTER_AREA)

        val hsv = Mat()
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        val sat = ch[1]
        val v = ch[2]

        // hapus teks gelap dari kecerahan (closing), sisakan kecerahan kertas + bayangan
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        val vc = Mat()
        Imgproc.morphologyEx(v, vc, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.GaussianBlur(vc, vc, Size(5.0, 5.0), 0.0)

        // piksel "kertas": tidak berwarna dan tidak terlalu gelap
        val lowSat = Mat()
        Imgproc.threshold(sat, lowSat, 70.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val meanV = Core.mean(vc, lowSat).`val`[0]
        val bright = Mat()
        Imgproc.threshold(vc, bright, meanV * 0.4, 255.0, Imgproc.THRESH_BINARY)
        val mask = Mat()
        Core.bitwise_and(lowSat, bright, mask)
        Imgproc.erode(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))

        val cover = Core.countNonZero(mask).toDouble() / (mask.rows() * mask.cols())
        if (cover < 0.05) return null // kertas tidak terdeteksi, jangan ubah apa pun

        // ---- isi area non-kertas dengan rata-rata tertimbang dari kertas di sekitarnya ----
        val vf = Mat()
        vc.convertTo(vf, CvType.CV_32F)
        val mf = Mat()
        mask.convertTo(mf, CvType.CV_32F, 1.0 / 255.0)
        val vm = Mat()
        Core.multiply(vf, mf, vm)

        fun estimate(sigma: Double): Pair<Mat, Mat> {
            val num = Mat()
            val den = Mat()
            Imgproc.GaussianBlur(vm, num, Size(0.0, 0.0), sigma)
            Imgproc.GaussianBlur(mf, den, Size(0.0, 0.0), sigma)
            val d = Mat()
            Core.add(den, Scalar(1e-6), d)
            val bg = Mat()
            Core.divide(num, d, bg)
            return Pair(bg, den)
        }

        val (bgFine, denFine) = estimate(18.0)   // detail bayangan
        val (bgCoarse, _) = estimate(80.0)       // untuk area jauh dari kertas (sidebar, foto)

        val conf = Mat()
        denFine.convertTo(conf, CvType.CV_32F, 5.0)
        Imgproc.threshold(conf, conf, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        val diff = Mat()
        Core.subtract(bgFine, bgCoarse, diff)
        Core.multiply(diff, conf, diff)
        val bg = Mat()
        Core.add(bgCoarse, diff, bg)
        Imgproc.GaussianBlur(bg, bg, Size(0.0, 0.0), 6.0)

        // ---- peta gain ukuran penuh ----
        val bgFull = Mat()
        Imgproc.resize(bg, bgFull, Size(rgb.cols().toDouble(), rgb.rows().toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.max(bgFull, Scalar(20.0), bgFull)
        val gain = Mat()
        Core.divide(250.0, bgFull, gain)
        Core.min(gain, Scalar(5.0), gain)
        Core.max(gain, Scalar(0.7), gain)

        // ---- white balance: buat kertas netral (tidak kebiruan/kekuningan) ----
        val m = Core.mean(small, mask).`val`
        val mx = maxOf(m[0], m[1], m[2])
        val sr = (mx / max(m[0], 1.0)).coerceIn(1.0, 1.5)
        val sg = (mx / max(m[1], 1.0)).coerceIn(1.0, 1.5)
        val sb = (mx / max(m[2], 1.0)).coerceIn(1.0, 1.5)
        val gR = Mat(); val gG = Mat(); val gB = Mat()
        Core.multiply(gain, Scalar(sr), gR)
        Core.multiply(gain, Scalar(sg), gG)
        Core.multiply(gain, Scalar(sb), gB)
        val gain3 = Mat()
        Core.merge(listOf(gR, gG, gB), gain3)

        // ---- terapkan ----
        val rgbF = Mat()
        rgb.convertTo(rgbF, CvType.CV_32FC3)
        rgb.release()
        Core.multiply(rgbF, gain3, rgbF)
        gain3.release()

        // levels: kertas jadi putih bersih, hitam tetap hitam
        val alpha = 255.0 / (238.0 - 8.0)
        val out8 = Mat()
        rgbF.convertTo(out8, CvType.CV_8UC3, alpha, -8.0 * alpha)
        rgbF.release()

        // haluskan bintik (bilateral menjaga tepi huruf), lalu pertajam sedikit
        val smooth = Mat()
        Imgproc.bilateralFilter(out8, smooth, 5, 25.0, 5.0)
        val blur = Mat()
        Imgproc.GaussianBlur(smooth, blur, Size(0.0, 0.0), 1.2)
        val sharp = Mat()
        Core.addWeighted(smooth, 1.5, blur, -0.5, 0.0, sharp)

        val result = Bitmap.createBitmap(sharp.cols(), sharp.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(sharp, result)
        return result
    }
}