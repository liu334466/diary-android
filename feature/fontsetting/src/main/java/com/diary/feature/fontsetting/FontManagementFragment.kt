package com.diary.feature.fontsetting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.core.model.DiaryLimits
import com.diary.core.model.FontAsset
import com.diary.feature.fontsetting.databinding.DialogFontPreviewBinding
import com.diary.feature.fontsetting.databinding.FragmentFontManagementBinding
import com.diary.feature.fontsetting.databinding.ItemFontManagementBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 字体管理：导入、预览、重命名、设为默认、删除。
 */
@AndroidEntryPoint
class FontManagementFragment : Fragment() {
    private var _binding: FragmentFontManagementBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: FontManagementViewModel by viewModels()

    private val importLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) viewModel.importFont(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFontManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.importButton.setOnClickListener {
            if (viewModel.state.value.importedCount >= DiaryLimits.MAX_FONTS) {
                DiaryUi.toast(requireContext(), "最多导入 10 个字体，请先删除一个")
            } else {
                importLauncher.launch(arrayOf("*/*"))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messageFlow.collect { DiaryUi.toast(requireContext(), it) }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: FontUiState) {
        if (state.loading) return
        binding.fontList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        state.items.forEach { item ->
            val row = ItemFontManagementBinding.inflate(inflater, binding.fontList, false)
            row.preview.typeface = item.typeface
            row.name.text = item.name
            row.meta.text = item.meta
            row.root.alpha = if (item.invalid) 0.6f else 1f
            row.badge.isVisible = item.isDefault || item.invalid
            row.badge.text =
                when {
                    item.invalid -> "文件缺失"
                    else -> "默认"
                }
            row.badge.setTextColor(
                if (item.invalid) {
                    DiaryColor.of(requireContext()).danger
                } else {
                    DiaryColor.of(requireContext()).muted
                },
            )
            row.root.setOnClickListener { showActions(item) }
            row.moreButton.setOnClickListener { showActions(item) }
            binding.fontList.addView(row.root)
        }
        binding.usageHint.text = "已导入 ${state.importedCount} / ${DiaryLimits.MAX_FONTS} 个字体"
        binding.importButton.alpha = if (state.importedCount >= DiaryLimits.MAX_FONTS) 0.4f else 1f
    }

    private fun showActions(item: FontRowItem) {
        val font = item.font
        if (font == null) {
            DiaryUi.actionSheet(
                context = requireContext(),
                title = "系统默认",
                subtitle = "使用手机系统字体，不占用导入名额",
                items = listOf(DiaryUi.ActionItem("设为默认字体", enabled = !item.isDefault)),
            ) { viewModel.setDefault(null) }
            return
        }
        val items =
            mutableListOf(
                DiaryUi.ActionItem("预览效果"),
                DiaryUi.ActionItem("重命名为「${font.displayName}」"),
                DiaryUi.ActionItem("设为默认字体", enabled = !item.isDefault),
                DiaryUi.ActionItem("删除字体", danger = true, enabled = font.source == FontAsset.SOURCE_IMPORTED),
            )
        DiaryUi.actionSheet(
            context = requireContext(),
            title = item.name,
            subtitle = item.meta,
            items = items,
        ) { index ->
            when (index) {
                0 -> showPreview(item)
                1 -> promptRename(font)
                2 -> viewModel.setDefault(font.id)
                3 -> confirmDelete(item)
            }
        }
    }

    private fun showPreview(item: FontRowItem) {
        val preview = DialogFontPreviewBinding.inflate(layoutInflater)
        preview.previewName.text =
            if (item.invalid) {
                "${item.name} · 文件缺失，已回退系统字体"
            } else {
                "${item.name} · 实时预览"
            }
        preview.previewText.typeface = item.typeface
        MaterialAlertDialogBuilder(requireContext())
            .setView(preview.root)
            .setPositiveButton("关闭", null)
            .create()
            .show()
    }

    private fun promptRename(font: FontAsset) {
        DiaryUi.inputDialog(
            context = requireContext(),
            title = "重命名字体",
            subtitle = "仅修改显示名称，不影响字体文件",
            initialValue = font.displayName,
            maxLength = 20,
            confirmText = "保存",
        ) { name ->
            viewModel.rename(font, name)
        }
    }

    private fun confirmDelete(item: FontRowItem) {
        val font = item.font ?: return
        val message =
            if (item.refCount > 0) {
                "有 ${item.refCount} 篇日记正在使用这个字体。删除后这些日记会回退到系统默认字体，正文内容与格式不受影响。"
            } else {
                "删除后字体文件会从本机移除，无法恢复。"
            }
        DiaryUi.confirm(
            context = requireContext(),
            title = "删除「${font.displayName}」？",
            message = message,
            confirmText = "删除字体",
            onConfirm = { viewModel.delete(font) },
        )
    }
}
