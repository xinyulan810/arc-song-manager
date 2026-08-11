package com.arcaea.songpack.cover

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arcaea.songpack.R
import com.arcaea.songpack.databinding.ActivityPackCoverEditorBinding
import com.arcaea.songpack.manager.GameRepository
import com.arcaea.songpack.util.UriUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 曲包封面快速编辑器。
 *
 * 流程: ManagerFragment 选图 -> 本页加载蒙版与图片 -> 缩放/拖动定位
 * (预览所见即所得) -> 保存: 按蒙版尺寸合成 PNG, 写入 songs/pack/ 两个文件名。
 */
class PackCoverEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACK_ID = "pack_id"
        const val EXTRA_IMAGE_URI = "image_uri"
        private const val MAX_DECODE = 2048
    }

    private lateinit var binding: ActivityPackCoverEditorBinding
    private var packId: String = ""
    private var mask: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackCoverEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packId = intent.getStringExtra(EXTRA_PACK_ID) ?: ""
        val imageUri = intentUri(EXTRA_IMAGE_URI)

        binding.toolbar.title = getString(R.string.cover_editor_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnReset.setOnClickListener { binding.coverEdit.reset() }
        binding.btnSave.setOnClickListener { save() }

        if (packId.isBlank() || imageUri == null) {
            toast(getString(R.string.cover_load_failed))
            finish()
            return
        }

        mask = loadMask()
        if (mask == null) {
            toast(getString(R.string.cover_load_failed))
            finish()
            return
        }
        binding.coverEdit.setMask(mask!!)
        binding.hintText.visibility = View.VISIBLE
        loadImage(imageUri)
    }

    private fun intentUri(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
        }

    private fun loadMask(): Bitmap? = try {
        assets.open("masks/pack_cover_mask.png").use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    private fun loadImage(uri: Uri) {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val f = UriUtil.copyToCache(this@PackCoverEditorActivity, uri, "cover_src_${System.currentTimeMillis()}.img")
                    decodeWithExif(f)
                } catch (e: Exception) {
                    null
                }
            }
            binding.progress.visibility = View.GONE
            if (bmp == null) {
                toast(getString(R.string.cover_load_failed))
                finish()
            } else {
                binding.coverEdit.setSource(bmp)
            }
        }
    }

    /** 降采样到 MAX_DECODE 内 + 按 EXIF 方向旋转(相册图片常带旋转信息) */
    private fun decodeWithExif(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE || bounds.outHeight / sample > MAX_DECODE) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        var rotation = 0f
        var mirror = false
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270f
            ExifInterface.ORIENTATION_TRANSPOSE -> { rotation = 90f; mirror = true }
            ExifInterface.ORIENTATION_TRANSVERSE -> { rotation = 270f; mirror = true }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> mirror = true
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { rotation = 180f; mirror = true }
        }
        if (rotation == 0f && !mirror) return bmp

        val m = Matrix().apply {
            if (mirror) preScale(-1f, 1f)
            preRotate(rotation)
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun save() {
        val src = binding.coverEdit.sourceBitmap() ?: return
        val m = mask ?: return
        if (binding.coverEdit.width <= 0 || binding.coverEdit.height <= 0) return

        binding.btnSave.isEnabled = false
        lifecycleScope.launch {
            val err = withContext(Dispatchers.Default) {
                try {
                    val out = CoverGenerator.generate(
                        src,
                        binding.coverEdit.currentMatrix(),
                        binding.coverEdit.width,
                        binding.coverEdit.height,
                        m
                    )
                    val f = File(cacheDir, "cover_$packId.png")
                    FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    out.recycle()
                    GameRepository.savePackCover(applicationContext, packId, f)
                } catch (e: Exception) {
                    "生成失败: ${e.message}"
                }
            }
            binding.btnSave.isEnabled = true
            if (err == null) {
                toast(getString(R.string.cover_saved))
                finish()
            } else {
                toast(err)
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
