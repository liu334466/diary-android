package com.diary.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.diary.core.common.DiaryFormats
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.core.fontui.FontPickResult
import com.diary.core.fontui.FontPickerDialog
import com.diary.core.fontui.FontScope
import com.diary.core.fontui.SizePickResult
import com.diary.core.fontui.SizePickerDialog
import com.diary.core.model.DiaryLimits
import com.diary.core.richtext.RichTextSpans
import com.diary.feature.reader.databinding.FragmentReaderBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 阅读页：按全局（L1）与单篇（L2）设置渲染正文，L3 行内格式优先。
 */
@AndroidEntryPoint
class ReaderFragment : Fragment() {
    private var _binding: FragmentReaderBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: ReaderViewModel by viewModels()
    private var initialized = false
    private var renderedUpdatedAt: Long = -1L
    private var resumedOnce = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.editButton.setOnClickListener {
            val id = viewModel.state.value.diary?.id ?: return@setOnClickListener
            findNavController().navigate(Uri.parse("diary://editor/$id"))
        }
        binding.fontButton.setOnClickListener { showFontActions() }
        binding.shareButton.setOnClickListener {
            val id = viewModel.state.value.diary?.id ?: return@setOnClickListener
            findNavController().navigate(Uri.parse("diary://export/$id"))
        }
        binding.moreButton.setOnClickListener { showMoreActions() }

        childFragmentManager.setFragmentResultListener(
            FontPickResult.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val result = FontPickResult.read(bundle)
            viewModel.applyWholeFont(result.fontId)
        }
        childFragmentManager.setFragmentResultListener(
            SizePickResult.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val result = SizePickResult.read(bundle)
            viewModel.applyWholeSize(result.level)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    if (state.missing) {
                        DiaryUi.toast(requireContext(), "这篇日记不存在或已删除")
                        findNavController().navigateUp()
                    } else if (!state.loading) {
                        if (!initialized || state.diary?.updatedAt != renderedUpdatedAt) {
                            initialized = true
                            renderContent(state)
                        } else {
                            applyChrome(state)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (resumedOnce) viewModel.load()
        resumedOnce = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun renderContent(state: ReaderUiState) {
        val diary = state.diary ?: return
        renderedUpdatedAt = diary.updatedAt
        val hasTitle = !diary.title.isNullOrBlank()
        binding.titleText.text = if (hasTitle) diary.title else DiaryLimits.UNTITLED_DIARY_NAME
        binding.titleText.setTextColor(
            if (hasTitle) {
                DiaryColor.of(requireContext()).ink
            } else {
                DiaryColor.of(requireContext()).muted
            },
        )

        val spannable =
            RichTextSpans.toSpannable(
                document = state.document,
                baseSizeSp = state.baseSizeSp,
                listGapPx = DiaryUi.dp(requireContext(), 24),
                fontResolver = { fontId -> state.fonts[fontId] },
                imageResolver = { path -> viewModel.imageDrawable(path) },
                imageMaxWidthPx = viewModel.state.value.imageWidthPx,
                // 正文里显式设置的“黑色”跟随主题正文色，深色模式下不会变成黑底黑字。
                colorMapper = { color ->
                    if (color == CONTENT_BLACK) DiaryColor.of(requireContext()).ink else color
                },
            )
        if (spannable.isEmpty()) {
            binding.bodyText.text = "（暂无正文）"
            binding.bodyText.setTextColor(DiaryColor.of(requireContext()).muted)
        } else {
            binding.bodyText.setText(spannable)
            binding.bodyText.setTextColor(DiaryColor.of(requireContext()).ink)
        }
        applyChrome(state)
    }

    private fun applyChrome(state: ReaderUiState) {
        binding.bodyText.typeface = state.baseTypeface
        binding.bodyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, state.baseSizeSp)
        binding.titleText.setTypeface(state.baseTypeface, android.graphics.Typeface.BOLD)
        binding.titleText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            maxOf(22f, state.baseSizeSp * 1.5f),
        )
        val diary = state.diary ?: return
        binding.metaText.text =
            listOf(
                DiaryFormats.date(diary.createdAt),
                state.categoryName,
                "${DiaryFormats.charCount(diary.charCount)} 字",
            ).joinToString(" · ")
        binding.tagText.isVisible = state.tags.isNotEmpty()
        binding.tagText.text = state.tags.joinToString("  ") { "#$it" }
    }

    private fun showFontActions() {
        val state = viewModel.state.value
        DiaryUi.actionSheet(
            context = requireContext(),
            title = "字体与字号",
            subtitle = "仅作用于这一篇，正文倒档优先使用行内格式",
            items =
                listOf(
                    DiaryUi.ActionItem("字体 · ${state.fontDisplayName}"),
                    DiaryUi.ActionItem("字号 · ${state.sizeDisplayName}"),
                    DiaryUi.ActionItem("恢复跟随全局设置"),
                ),
        ) { index ->
            when (index) {
                0 ->
                    FontPickerDialog.newInstance(
                        initialFontId = state.baseFontId,
                        scope = FontScope.WHOLE,
                        allowedScopes = listOf(FontScope.WHOLE),
                        title = "这一篇的字体",
                    ).show(childFragmentManager, "reader_font")
                1 ->
                    SizePickerDialog.newInstance(
                        initialLevel = state.baseSizeLevel,
                        defaultLevel = state.defaultSizeLevel,
                        scope = FontScope.WHOLE,
                        allowedScopes = listOf(FontScope.WHOLE),
                        title = "这一篇的字号",
                    ).show(childFragmentManager, "reader_size")
                else -> {
                    viewModel.resetToGlobal()
                    DiaryUi.toast(requireContext(), "已恢复跟随全局设置")
                }
            }
        }
    }

    private fun showMoreActions() {
        val hasText = viewModel.state.value.document.blocks.any { it.text.isNotBlank() }
        DiaryUi.actionSheet(
            context = requireContext(),
            title = "更多",
            items =
                listOf(
                    DiaryUi.ActionItem("复制全文", enabled = hasText),
                    DiaryUi.ActionItem("删除日记", danger = true),
                ),
        ) { index ->
            when (index) {
                0 -> copyContent()
                1 -> confirmDelete()
            }
        }
    }

    private fun copyContent() {
        val state = viewModel.state.value
        val text =
            state.document.blocks.joinToString("\n") { it.text }
                .replace(com.diary.core.richtext.RichTextCodec.IMAGE_PLACEHOLDER.toString(), "")
        if (text.isBlank()) {
            DiaryUi.toast(requireContext(), "正文为空，没有可复制的内容")
            return
        }
        val clipboard =
            requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("日记", text))
        DiaryUi.toast(requireContext(), "已复制全文")
    }

    private fun confirmDelete() {
        DiaryUi.confirm(
            context = requireContext(),
            title = "删除这篇日记？",
            message = "日记会移入回收站，30 天内可以恢复。",
            confirmText = "删除",
            onConfirm = {
                viewModel.delete {
                    if (isAdded) findNavController().navigateUp()
                }
            },
        )
    }

    private companion object {
        /** 与编辑器调色板中的“黑色”一致，渲染时替换为当前主题正文色。 */
        const val CONTENT_BLACK = 0xFF111111.toInt()
    }
}
