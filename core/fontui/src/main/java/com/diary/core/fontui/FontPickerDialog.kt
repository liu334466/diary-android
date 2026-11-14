package com.diary.core.fontui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.diary.core.database.repository.FontRepository
import com.diary.core.database.settings.SettingsRepository
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.core.font.FontCache
import com.diary.core.fontui.databinding.DialogFontPickerBinding
import com.diary.core.fontui.databinding.ItemFontPickBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 字体选择器。支持选区（L3）、整篇（L2）、全局（L1）三种作用域，
 * 结果通过 Fragment Result 返回，由宿主决定落库位置。
 */
@AndroidEntryPoint
class FontPickerDialog : BottomSheetDialogFragment() {
    private var _binding: DialogFontPickerBinding? = null
    private val binding get() = requireNotNull(_binding)

    @Inject lateinit var fontRepository: FontRepository

    @Inject lateinit var fontCache: FontCache

    @Inject lateinit var settingsRepository: SettingsRepository

    private val options = ArrayList<FontOption>()
    private var chosenId: Long? = null
    private var mixed = false
    private var selectionActive = true
    private var scope = FontScope.WHOLE
    private var allowedScopes = listOf(FontScope.SELECTION, FontScope.WHOLE, FontScope.GLOBAL)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogFontPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val args = requireArguments()
        mixed = args.getBoolean(ARG_MIXED, false)
        selectionActive = args.getBoolean(ARG_SELECTION_ACTIVE, true)
        allowedScopes = args.getStringArrayList(ARG_SCOPES)
            ?.mapNotNull(FontScope::from)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(FontScope.WHOLE)
        scope = FontScope.from(args.getString(ARG_SCOPE))
            ?.takeIf { it in allowedScopes }
            ?: allowedScopes.first()
        chosenId = args.getLong(ARG_FONT_ID, 0L).takeIf { it > 0L }
        binding.title.text = args.getString(ARG_TITLE) ?: "选择字体"

        buildScopeSelector()
        binding.cancelButton.setOnClickListener { dismiss() }
        binding.applyButton.setOnClickListener { submit() }
        updateHint()
        updatePreview()

        viewLifecycleOwner.lifecycleScope.launch {
            val fonts = fontRepository.getAll()
            options.clear()
            options += FontOption(null, "系统默认", Typeface.DEFAULT, false, "跟随系统字体")
            fonts.forEach { font ->
                val result = if (font.isUsable()) fontCache.load(font) else null
                options +=
                    FontOption(
                        id = font.id,
                        name = font.displayName,
                        typeface = result?.typeface ?: Typeface.DEFAULT,
                        invalid =
                            font.status != com.diary.core.model.FontAsset.STATUS_OK ||
                                result?.usedFallback == true,
                        sourceLabel =
                            if (font.source == com.diary.core.model.FontAsset.SOURCE_BUILT_IN) {
                                "内置"
                            } else {
                                "导入"
                            },
                    )
            }
            renderOptions()
            updatePreview()
        }
    }

    override fun onStart() {
        super.onStart()
        val height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
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

    private fun updateHint() {
        binding.hint.text =
            when {
                mixed -> "选区包含多种字体，当前为混合状态，选择后统一覆盖"
                scope == FontScope.SELECTION && !selectionActive ->
                    "当前未选中文字：选择只影响光标后新输入的内容（L3）"
                scope == FontScope.SELECTION -> "只覆盖选中的文字，优先级最高（L3）"
                scope == FontScope.WHOLE -> "覆盖这一篇的字体，不改动其他日记（L2）"
                else -> "所有未单独设置过的日记都会跟随（L1）"
            }
    }

    private fun updatePreview() {
        val option = options.firstOrNull { it.id == chosenId }
        binding.previewText.typeface = option?.typeface ?: Typeface.DEFAULT
        binding.previewCap.text = "实时预览 · " + (option?.name ?: "系统默认")
    }

    private fun renderOptions() {
        binding.fontList.removeAllViews()
        val palette = DiaryColor.of(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        options.forEach { option ->
            val row = ItemFontPickBinding.inflate(inflater, binding.fontList, false)
            row.preview.typeface = option.typeface
            row.name.text = if (option.invalid) "文件已丢失" else option.name
            row.name.setTextColor(if (option.invalid) palette.danger else palette.ink)
            row.source.text = if (option.invalid) "已自动回退系统字体" else option.sourceLabel
            val selected = !mixed && option.id == chosenId
            row.check.isVisible = selected
            row.root.setBackgroundResource(
                if (selected) {
                    com.diary.core.designsystem.R.drawable.ds_bg_soft
                } else {
                    com.diary.core.designsystem.R.drawable.ds_bg_ripple
                },
            )
            row.root.isEnabled = !option.invalid
            row.root.alpha = if (option.invalid) 0.55f else 1f
            row.root.setOnClickListener {
                if (option.invalid) return@setOnClickListener
                chosenId = option.id
                mixed = false
                renderOptions()
                updatePreview()
                updateHint()
            }
            binding.fontList.addView(row.root)
        }
    }

    private fun submit() {
        if (mixed) {
            DiaryUi.toast(requireContext(), "选区为混合状态，请先选择一种字体")
            return
        }
        parentFragmentManager.setFragmentResult(
            FontPickResult.RESULT_KEY,
            bundleOf(
                FontPickResult.ARG_FONT_ID to (chosenId ?: 0L),
                FontPickResult.ARG_SCOPE to scope.name,
            ),
        )
        dismiss()
    }

    private fun labelOf(scope: FontScope): String =
        when (scope) {
            FontScope.SELECTION -> "选区"
            FontScope.WHOLE -> "整篇"
            FontScope.GLOBAL -> "全局"
        }

    companion object {
        private const val ARG_FONT_ID = "arg_font_id"
        private const val ARG_SCOPE = "arg_scope"
        private const val ARG_SCOPES = "arg_scopes"
        private const val ARG_MIXED = "arg_mixed"
        private const val ARG_SELECTION_ACTIVE = "arg_selection_active"
        private const val ARG_TITLE = "arg_title"

        fun newInstance(
            initialFontId: Long?,
            scope: FontScope,
            allowedScopes: List<FontScope>,
            mixed: Boolean = false,
            selectionActive: Boolean = true,
            title: String = "选择字体",
        ): FontPickerDialog =
            FontPickerDialog().apply {
                arguments =
                    bundleOf(
                        ARG_FONT_ID to (initialFontId ?: 0L),
                        ARG_SCOPE to scope.name,
                        ARG_SCOPES to ArrayList(allowedScopes.map { it.name }),
                        ARG_MIXED to mixed,
                        ARG_SELECTION_ACTIVE to selectionActive,
                        ARG_TITLE to title,
                    )
            }
    }
}
