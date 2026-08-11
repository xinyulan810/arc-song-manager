package com.arcaea.songpack.cover

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode

/**
 * 曲包封面合成(与离线原型一致):
 *   1) 白底 = 蒙版原形状(白色, 保留 alpha)
 *   2) 艺术层 = 源图按视图矩阵映射到输出尺寸, 用"内缩 BORDER_PX 的蒙版"裁切
 *   3) 艺术层叠到白底上 => 轮廓内侧留出一圈白边, 轮廓外透明
 */
object CoverGenerator {

    /** 白边宽度(蒙版像素) */
    const val BORDER_PX = 4

    fun generate(
        source: Bitmap,
        viewMatrix: Matrix,
        viewWidth: Int,
        viewHeight: Int,
        mask: Bitmap
    ): Bitmap {
        val w = mask.width
        val h = mask.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out)

        // 1) 白底
        outCanvas.drawBitmap(toWhite(mask), 0f, 0f, null)

        // 2) 艺术层: 视图矩阵 -> 输出尺寸, 内缩蒙版裁切
        val art = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val artCanvas = Canvas(art)
        val outMatrix = Matrix(viewMatrix).apply {
            postScale(w / viewWidth.toFloat(), h / viewHeight.toFloat())
        }
        artCanvas.drawBitmap(
            source, outMatrix,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        artCanvas.drawBitmap(
            shrinkMask(mask, BORDER_PX), 0f, 0f,
            Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        )

        // 3) 艺术层叠到白底上
        outCanvas.drawBitmap(art, 0f, 0f, null)
        return out
    }

    /** 蒙版像素 -> 同 alpha 的纯白 */
    private fun toWhite(mask: Bitmap): Bitmap {
        val w = mask.width
        val h = mask.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = (px[i] ushr 24) and 0xFF
            px[i] = (a shl 24) or 0x00FFFFFF
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 蒙版内缩 borderPx(各向异性, 四边均匀内收), 用于裁出"白边内侧"的艺术区 */
    private fun shrinkMask(mask: Bitmap, borderPx: Int): Bitmap {
        val w = mask.width
        val h = mask.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val kx = 1f - borderPx * 2f / w
        val ky = 1f - borderPx * 2f / h
        c.scale(kx, ky, w / 2f, h / 2f)
        c.drawBitmap(mask, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
