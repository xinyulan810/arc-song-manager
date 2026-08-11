package com.arcaea.songpack.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.arcaea.songpack.BuildConfig
import com.arcaea.songpack.R
import com.arcaea.songpack.databinding.FragmentSettingsBinding

/**
 * 设置页: 语言切换 + 关于(版本/作者/GitHub)。
 * 语言选择通过 AppCompatDelegate.setApplicationLocales 全局生效并持久化。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    /** 语言持久化键: "zh" 默认 / "en" */
    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_LANGUAGE = "app_language"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.versionText.text = getString(R.string.version) + " " + BuildConfig.VERSION_NAME

        // 按当前语言勾选
        val cur = currentLanguage()
        binding.radioZh.isChecked = cur != "en"
        binding.radioEn.isChecked = cur == "en"

        binding.languageGroup.setOnCheckedChangeListener { _, checkedId ->
            val lang = if (checkedId == binding.radioEn.id) "en" else "zh"
            if (lang != currentLanguage()) {
                applyLanguage(requireContext(), lang)
            }
        }

        binding.btnGitHub.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xinyulan810/arc-song-manager")))
            } catch (_: Exception) {
            }
        }
    }

    private fun currentLanguage(): String =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "zh") ?: "zh"

    private fun applyLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, lang).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
