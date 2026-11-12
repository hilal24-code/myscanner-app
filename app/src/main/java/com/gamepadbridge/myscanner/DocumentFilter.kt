package com.gamepadbridge.myscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

enum class FilterMode(val label: String) {
    ORIGINAL("Asli"),
    COLOR("Warna"),
    BW("Hitam-Putih")
}

fun applyFilter(source: Bitmap, mode: FilterMode): Bitmap {
    if (mode == FilterMode.ORIGINAL) return source

    val src = Mat()
    Utils.bitmapToMat(source, src)

    val base = Mat()
    if (mode == FilterMode.COLOR) {
        Imgproc.cvtColor(src, base, Imgproc.COLOR_RGBA2RGB)
    } else {
        Imgproc.cvtColor(src, base, Imgproc.COLOR_RGBA2GRAY)
    }

    // perkirakan bayangan/latar, lalu bagi supaya kertas jadi putih rata
    val bg = Mat()
    Imgproc.GaussianBlur(base, bg, Size(0.0, 0.0), 30.0)
    val norm = Mat()
    Core.divide(base, bg, norm, 255.0)

    // sedikit tambah kontras supaya teks lebih tegas
    val out = Mat()
    if (mode == FilterMode.COLOR) {
        norm.convertTo(out, -1, 1.1, -12.0)
    } else {
        norm.convertTo(out, -1, 1.25, -30.0)
    }

    val result = Bitmap.createBitmap(out.cols(), out.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(out, result)

    src.release(); base.release(); bg.release(); norm.release(); out.release()
    return result
}