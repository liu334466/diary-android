package com.diary.core.fontui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.core.fontui.databinding.DialogSizePickerBinding
import com.diary.core.fontui.databinding.ItemSizeCellBinding
import com.diary.core.model.DiaryLimits
import com.diary.core.model.FontSizeLevel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 字号选择器：10 档网格、混合状态提示、实时预览。
 */
class SizePickerDialog : BottomSheetDialogFragment() {
    private var _binding: DialogSizePickerBinding? = null
    private val binding get() = requireNotNull(_binding)

    private var level = DiaryLimits.DEFAULT_FONT_SIZE_LEVEL
    private var defaultLevel = DiaryLimits.DEFAULT_FONT_SIZE_LEVEL
    private var mixed = false
    private var selectionActive = true
    private var scope = FontScope.WHOLE
    private var allowedScopes = listOf(FontScope.SELECTION, FontScope.WHOLE, FontScope.GLOBAL)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogSizePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val args = requireArguments()
        mixed = args.getBoolean(ARG_MIXED, false)
        selectionActive = args.getBoolean(ARG_SELECTION_ACTIVE, true)
        defaultLevel = args.getInt(ARG_DEFAULT_LEVEL, DiaryLimits.DEFAULT_FONT_SIZE_LEVEL)
        allowedScopes = args.getStringArrayList(ARG_SCOPES)
            ?.mapNotNull(FontScope::from)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(FontScope.WHOLE)
        scope = FontScope.from(args.getString(ARG_SCOPE))
            ?.takeIf { it in allowedScopes }
            ?: allowedScopes.first()
        level = args.getInt(ARG_LEVEL, defaultLevel).coerceIn(0, DiaryLimits.FONT_SIZE_LEVEL_COUNT - 1)
        binding.title.text = args.getString(ARG_TITLE) ?: "选择字号"

        buildScopeSelector()
        buildGrid()
        binding.applyButton.setOnClickListener { submit() }
        binding.mixedNote.isVisible = mixed
        updateHint()
        updatePreview()
    }

    override fun onStart() {
        super.onStart()
        val height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun buildScopeSelector() {
        binding.scopeGroup.removeAllViews()
        if (allowedScopes.size <= 1) {
            binding.scopeGroup.isVisible = false
            return
        }
        allowedScopes.forEach { candidate ->
            val chip =
                TextView(requireContext()).apply {
                    text = labelOf(candidate)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, DiaryUi.dp(requireContext(), 34), 1f)
                    setOnClickListener {
                        scope = candidate
                        updateScopeUi()
                        updateHint()
                    }
                }
            binding.scopeGroup.addView(chip)
        }
        updateScopeUi()
    }

    private fun updateScopeUi() {
        val palette = DiaryColor.of(requireContext())
        for (index in 0 until binding.scopeGroup.childCount) {
            val chip = binding.scopeGroup.getChildAt(index) as TextView
            val selected = allowedScopes[index] == scope
            chip.background =
                if (selected) {
                    ContextCompat.getDrawable(
                        requireContext(),
                        com.diary.core.designsystem.R.drawable.ds_bg_ink,
                    )
                } else {
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                }
            chip.setTextColor(if (selected) palette.bg else palette.ink2)
            chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun buildGrid() {
        binding.sizeGrid.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        FontSizeLevel.entries.forEach { entry ->
            val cell = ItemSizeCellBinding.inflate(inflater, binding.sizeGrid, false)
            cell.name.text = entry.label
            cell.name.textSize = (22f - entry.level * 1.2f).coerceAtLeast(10f)
            cell.sizeValue.text = formatPt(entry.sizeSp)
            cell.root.setOnClickListener {
                level = entry.level
                mixed = false
                binding.mixedNote.isVisible = false
                updateGridSelection()
                updateHint()
                updatePreview()
            }
            val params =
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = DiaryUi.dp(requireContext(), 64)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(4, 4, 4, 4)
                }
            binding.sizeGrid.addView(cell.root, params)
        }
        updateGridSelection()
    }

    private fun updateGridSelection() {
        val palette = DiaryColor.of(requireContext())
        for (index in 0 until binding.sizeGrid.childCount) {
            val cellView = binding.sizeGrid.getChildAt(index)
            val cell = ItemSizeCellBinding.bind(cellView)
            val selected = !mixed && index == level
            cell.root.isSelected = selected
            cell.root.background =
                ContextCompat.getDrawable(
                    requireContext(),
                    com.diary.core.designsystem.R.drawable.ds_bg_cell,
                )
            cell.name.setTextColor(if (selected) palette.bg else palette.ink)
            cell.sizeValue.setTextColor(
                if (selected) (palette.bg and 0x00FFFFFF) or 0xB3000000.toInt() else palette.muted,
            )
        }
    }

    private fun updateHint() {
        binding.hint.text =
            when {
                mixed -> "选区包含多种字号，当前为混合状态"
                scope == FontScope.SELECTION && !selectionActive ->
                    "当前未选中文字：选择只影响光标后新输入的内容（L3）"
                scope == FontScope.SELECTION -> "只覆盖选中的文字，优先级最高（L3）"
                scope == FontScope.WHOLE -> "覆盖这一篇的字号，不改动其他日记（L2）"
                else -> "所有未单独设置过的日记都会跟随（L1）"
            }
    }

    private fun updatePreview() {
        val entry = FontSizeLevel.of(if (mixed) defaultLevel else level) ?: FontSizeLevel.Default
        binding.previewText.textSize = (entry.sizeSp * 1.2f).coerceAtMost(34f)
        binding.previewText.text =
            if (mixed) {
                "选区包含多种字号，选择后统一覆盖。"
            } else {
                "雨下了一整天，没出门。"
            }
        binding.previewCap.text =
            if (mixed) {
                "实时预览 · 混合状态"
            } else {
                "实时预览 · ${entry.label}（${formatPt(entry.sizeSp)}）" +
                    if (level == defaultLevel) " · 默认值" else ""
            }
    }

    private fun submit() {
        if (mixed) {
            DiaryUi.toast(requireContext(), "选区为混合状态，请先选择一档字号")
            return
        }
        parentFragmentManager.setFragmentResult(
            SizePickResult.RESULT_KEY,
            bundleOf(
                SizePickResult.ARG_LEVEL to level,
                SizePickResult.ARG_SCOPE to scope.name,
            ),
        )
        dismiss()
    }

    private fun formatPt(sizeSp: Float): String {
        val text = if (sizeSp % 1f == 0f) sizeSp.toInt().toString() else sizeSp.toString()
        return "$text pt"
    }

    private fun labelOf(scope: FontScope): String =
        when (scope) {
            FontScope.SELECTION -> "选区"
            FontScope.WHOLE -> "整篇"
            FontScope.GLOBAL -> "全局"
        }

    companion object {
        private const val ARG_LEVEL = "arg_level"
        private const val ARG_DEFAULT_LEVEL = "arg_default_level"
        private const val ARG_SCOPE = "arg_scope"
        private const val ARG_SCOPES = "arg_scopes"
        private const val ARG_MIXED = "arg_mixed"
        private const val ARG_SELECTION_ACTIVE = "arg_selection_active"
        private const val ARG_TITLE = "arg_title"

        fun newInstance(
            initialLevel: Int,
            defaultLevel: Int,
            scope: FontScope,
            allowedScopes: List<FontScope>,
            mixed: Boolean = false,
            selectionActive: Boolean = true,
            title: String = "选择字号",
        ): SizePickerDialog =
            SizePickerDialog().apply {
                arguments =
                    bundleOf(
                        ARG_LEVEL to initialLevel,
                        ARG_DEFAULT_LEVEL to defaultLevel,
                        ARG_SCOPE to scope.name,
                        ARG_SCOPES to ArrayList(allowedScopes.map { it.name }),
                        ARG_MIXED to mixed,
                        ARG_SELECTION_ACTIVE to selectionActive,
                        ARG_TITLE to title,
                    )
            }
    }
}
