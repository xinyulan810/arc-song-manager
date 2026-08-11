package com.arcaea.songpack

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.arcaea.songpack.databinding.ActivityHomeBinding
import com.arcaea.songpack.home.ImportFragment
import com.arcaea.songpack.home.ManagerFragment
import com.arcaea.songpack.home.SettingsFragment

/**
 * 主界面: 底栏三 Tab 平行切换
 *  - Tab1 自制谱导入(ImportFragment, 原 MainActivity 逻辑)
 *  - Tab2 曲包管理(ManagerFragment, 原 ManagerActivity 逻辑)
 *  - Tab3 设置(SettingsFragment: 语言切换 + 关于)
 * 接收外部 zip/rar 分享时自动切到导入 Tab。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private var currentFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_import -> { switchTo(ImportFragment()); true }
                R.id.nav_manager -> { switchTo(ManagerFragment()); true }
                R.id.nav_settings -> { switchTo(SettingsFragment()); true }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            switchTo(ImportFragment())
            binding.bottomNav.selectedItemId = R.id.nav_import
        }
        handleSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    /** 底栏切换直接硬切, 不加转场动画 */
    private fun switchTo(fragment: Fragment) {
        val tag = fragment.javaClass.simpleName
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag(tag)
        val target = existing ?: fragment
        fm.beginTransaction().replace(R.id.fragmentContainer, target, tag).commit()
        currentFragment = target
    }

    /** 应用已保存的语言(默认中文), 必须在 setContentView 之前调用 */
    private fun applySavedLanguage() {
        val lang = getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SettingsFragment.KEY_LANGUAGE, "zh") ?: "zh"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
    }

    /** 处理外部通过分享/打开进来的 zip/rar, 转发给导入 Fragment */
    private fun handleSharedIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            binding.bottomNav.selectedItemId = R.id.nav_import
            (currentFragment as? ImportFragment)?.loadSharedUri(uri)
        }
    }
}
