package com.diary.feature.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.diary.core.common.CrashLogger
import com.diary.core.designsystem.DiaryUi
import com.diary.core.fontui.FontPickResult
import com.diary.core.fontui.FontPickerDialog
import com.diary.core.fontui.FontScope
import com.diary.core.fontui.SizePickResult
import com.diary.core.fontui.SizePickerDialog
import com.diary.feature.settings.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页：默认字体字号（L1）、字体管理、回收站与数据说明。
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: SettingsViewModel by viewModels()

    private val createBackup =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri != null) viewModel.exportBackup(uri)
        }

    private val openBackup =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) confirmRestore(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.versionValue.text = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"

        binding.defaultFontRow.setOnClickListener {
            FontPickerDialog.newInstance(
                initialFontId = viewModel.state.value.defaultFontId,
                scope = FontScope.GLOBAL,
                allowedScopes = listOf(FontScope.GLOBAL),
                title = "默认字体",
            ).show(childFragmentManager, "settings_font")
        }
        binding.defaultSizeRow.setOnClickListener {
            val state = viewModel.state.value
            SizePickerDialog.newInstance(
                initialLevel = state.defaultSizeLevel,
                defaultLevel = state.defaultSizeLevel,
                scope = FontScope.GLOBAL,
                allowedScopes = listOf(FontScope.GLOBAL),
                title = "默认字号",
            ).show(childFragmentManager, "settings_size")
        }
        binding.fontManageRow.setOnClickListener {
            findNavController().navigate(Uri.parse("diary://fonts"))
        }
        binding.trashRow.setOnClickListener {
            findNavController().navigate(Uri.parse("diary://trash"))
        }
        binding.tagManageRow.setOnClickListener {
            findNavController().navigate(Uri.parse("diary://tags"))
        }
        binding.backupRow.setOnClickListener {
            if (viewModel.state.value.busy) return@setOnClickListener
            DiaryUi.actionSheet(
                context = requireContext(),
                title = "备份与恢复",
                subtitle = "备份包含全部日记、分类、标签、插图与导入字体",
                items =
                    listOf(
                        DiaryUi.ActionItem("导出备份文件"),
                        DiaryUi.ActionItem("从备份文件恢复"),
                    ),
            ) { index ->
                when (index) {
                    0 -> createBackup.launch(backupFileName())
                    1 -> openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            }
        }
        binding.appearanceRow.setOnClickListener {
            val current = viewModel.state.value.themeMode
            DiaryUi.actionSheet(
                context = requireContext(),
                title = "外观模式",
                subtitle = "深色为深灰底，暖纸为米黄纸感；跟随系统时随系统日夜切换",
                items =
                    com.diary.core.common.DiaryThemeMode.entries.map {
                        DiaryUi.ActionItem(
                            label = if (it == current) "${it.label} · 当前" else it.label,
                        )
                    },
            ) { index ->
                val target = com.diary.core.common.DiaryThemeMode.entries[index]
                if (target != current) {
                    viewModel.setThemeMode(target)
                    requireActivity().recreate()
                }
            }
        }
        binding.privacyRow.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("隐私政策")
                .setMessage(PRIVACY_TEXT)
                .setPositiveButton("知道了", null)
                .show()
        }
        binding.crashLogRow.setOnClickListener {
            val intent = CrashLogger.shareIntent(requireContext())
            if (intent == null) {
                DiaryUi.toast(requireContext(), "暂无崩溃日志")
            } else {
                runCatching { startActivity(intent) }
                    .onFailure { DiaryUi.toast(requireContext(), "没有可用的分享应用") }
            }
        }

        childFragmentManager.setFragmentResultListener(
            FontPickResult.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            viewModel.setDefaultFont(FontPickResult.read(bundle).fontId)
        }
        childFragmentManager.setFragmentResultListener(
            SizePickResult.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            viewModel.setDefaultSize(SizePickResult.read(bundle).level)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        if (state.loading) return@collect
                        binding.defaultFontValue.text =
                            when {
                                state.defaultFontMissing -> "字体已删除 · 系统默认"
                                else -> state.defaultFontName
                            }
                        binding.defaultSizeValue.text = state.defaultSizeName
                        binding.fontManageValue.text = "${state.importedFontCount} 个导入字体"
                        binding.trashValue.text =
                            if (state.trashCount > 0) {
                                "${state.trashCount} 篇"
                            } else {
                                "空"
                            }
                        binding.backupValue.text = if (state.busy) "处理中…" else "本地 ZIP"
                        binding.appearanceValue.text = state.themeMode.label
                    }
                }
                launch {
                    viewModel.events.collect { message ->
                        DiaryUi.toast(requireContext(), message)
                    }
                }
            }
        }
    }

    private fun backupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        return "日记备份-$stamp.zip"
    }

    private fun confirmRestore(uri: Uri) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("从备份恢复？")
            .setMessage(
                "恢复会覆盖当前全部日记、分类、标签、插图与导入字体，且无法撤销。\n\n" +
                    "建议先导出一份当前数据作为备份。",
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ -> viewModel.restoreBackup(uri) }
            .show()
    }

    override fun onResume() {
        super.onResume()
        val count = CrashLogger.files(requireContext()).size
        binding.crashLogValue.text = if (count > 0) "$count 条记录" else "暂无记录"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        const val PRIVACY_TEXT =
            "本应用不申请网络权限，不采集、不上传任何个人数据，也不含统计、广告或第三方 SDK。\n\n" +
                "日记、分类、字体与插图全部保存在本机应用私有目录；卸载应用或清除应用数据会一并删除，且无法找回。\n\n" +
                "导入字体与图片通过系统文件选择器完成，应用只读取你主动选择的文件。\n\n" +
                "导出文件只写入应用缓存目录，分享时由你在系统面板中选择去向。"
    }
}
