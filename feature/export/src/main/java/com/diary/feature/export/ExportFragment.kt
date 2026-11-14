package com.diary.feature.export

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.feature.export.databinding.SheetExportBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 导出弹层：格式选择 → 生成进度（可取消）→ 完成分享 / 失败重试。
 */
@AndroidEntryPoint
class ExportFragment : BottomSheetDialogFragment() {
    private var _binding: SheetExportBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: ExportViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = SheetExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.optionPdf.setOnClickListener { viewModel.selectFormat(ExportFormat.PDF) }
        binding.optionPng.setOnClickListener { viewModel.selectFormat(ExportFormat.PNG) }
        binding.primaryButton.setOnClickListener { onPrimary() }
        binding.secondaryButton.setOnClickListener { onSecondary() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: ExportUiState) {
        if (state.missing) {
            DiaryUi.toast(requireContext(), "这篇日记不存在或已删除")
            dismiss()
            return
        }
        binding.chooseGroup.isVisible = state.stage == ExportStage.CHOOSE
        binding.progressGroup.isVisible = state.stage == ExportStage.PROGRESS
        binding.doneGroup.isVisible = state.stage == ExportStage.DONE
        binding.failGroup.isVisible = state.stage == ExportStage.FAIL

        val isPdf = state.format == ExportFormat.PDF
        binding.sheetTitle.text =
            when (state.stage) {
                ExportStage.CHOOSE -> "导出这篇日记"
                ExportStage.PROGRESS -> if (isPdf) "正在生成 PDF" else "正在生成长图"
                ExportStage.DONE -> "导出完成"
                ExportStage.FAIL -> "导出失败"
            }
        binding.sheetSubtitle.isVisible = state.stage == ExportStage.CHOOSE ||
            state.stage == ExportStage.PROGRESS
        binding.sheetSubtitle.text =
            if (state.stage == ExportStage.CHOOSE) {
                "按当前排版渲染，自定义字体会一并嵌入"
            } else {
                "请保持应用在前台"
            }

        renderOptions(state)
        binding.exportNote.text =
            if (state.longContent) {
                "长文将按内容分段绘制；导出使用当前这一篇的字体与字号。"
            } else {
                "导出会使用当前这一篇的字体与字号；自定义字体会一并嵌入。"
            }

        binding.progressIcon.setImageResource(
            if (isPdf) {
                com.diary.core.designsystem.R.drawable.ic_ds_file_text
            } else {
                com.diary.core.designsystem.R.drawable.ic_ds_image
            },
        )
        binding.progressTitle.text = if (isPdf) "正在排版并嵌入字体…" else "正在分段绘制长图…"
        binding.progressBar.setProgressCompat(state.progress, true)
        binding.progressPercent.text = "${state.progress}%"

        binding.resultName.text = state.resultName
        binding.resultDetail.text =
            if (state.segmented) {
                "${state.resultDetail}\n长文已分段导出，分享时会一并发送。"
            } else {
                state.resultDetail
            }
        binding.failMessage.text = state.errorMessage

        when (state.stage) {
            ExportStage.PROGRESS -> {
                binding.primaryButton.isVisible = false
                binding.secondaryButton.text = "取消导出"
            }

            ExportStage.DONE -> {
                binding.primaryButton.isVisible = true
                binding.primaryButton.text = "分享"
                binding.secondaryButton.text = "完成"
            }

            ExportStage.FAIL -> {
                binding.primaryButton.isVisible = true
                binding.primaryButton.text = "重试"
                binding.secondaryButton.text = "取消"
            }

            ExportStage.CHOOSE -> {
                binding.primaryButton.isVisible = true
                binding.primaryButton.text = "开始导出"
                binding.secondaryButton.text = "取消"
            }
        }
    }

    private fun renderOptions(state: ExportUiState) {
        bindOption(
            binding.optionPdf,
            binding.pdfIcon,
            binding.pdfTitle,
            binding.pdfDesc,
            binding.pdfCheck,
            state.format == ExportFormat.PDF,
        )
        bindOption(
            binding.optionPng,
            binding.pngIcon,
            binding.pngTitle,
            binding.pngDesc,
            binding.pngCheck,
            state.format == ExportFormat.PNG,
        )
    }

    private fun bindOption(
        row: View,
        icon: ImageView,
        title: TextView,
        desc: TextView,
        check: TextView,
        selected: Boolean,
    ) {
        row.isSelected = selected
        val palette = DiaryColor.of(requireContext())
        val main = if (selected) palette.bg else palette.ink
        val secondary =
            if (selected) {
                (palette.bg and 0x00FFFFFF) or 0xCC000000.toInt()
            } else {
                palette.muted
            }
        title.setTextColor(main)
        desc.setTextColor(secondary)
        check.text = if (selected) "●" else "○"
        check.setTextColor(main)
        icon.setColorFilter(main)
    }

    private fun onPrimary() {
        when (viewModel.state.value.stage) {
            ExportStage.CHOOSE, ExportStage.FAIL -> viewModel.start()
            ExportStage.DONE -> share()
            ExportStage.PROGRESS -> Unit
        }
    }

    private fun onSecondary() {
        when (viewModel.state.value.stage) {
            ExportStage.PROGRESS -> viewModel.cancel()
            else -> dismiss()
        }
    }

    private fun share() {
        val files = viewModel.filesForShare()
        if (files.isEmpty()) return
        val intent =
            ExportShare.chooser(
                requireContext(),
                files,
                viewModel.state.value.format,
            )
        runCatching { startActivity(intent) }
            .onFailure { DiaryUi.toast(requireContext(), "没有可用的分享应用") }
    }
}
