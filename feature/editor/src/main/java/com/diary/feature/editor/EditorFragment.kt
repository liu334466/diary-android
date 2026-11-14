package com.diary.feature.editor

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import com.diary.core.model.FontSizeLevel
import com.diary.core.richtext.InlineAttributes
import com.diary.core.richtext.RichTextCodec
import com.diary.core.richtext.RichTextSpans
import com.diary.feature.editor.databinding.FragmentEditorBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 编辑页：标题、富文本正文、格式工具条、选区工具条、插图与自动保存。
 */
@AndroidEntryPoint
class EditorFragment : Fragment() {
    private var _binding: FragmentEditorBinding? = null
    private val binding get() = requireNotNull(_binding)

    /** EditText 的文本永远非空，统一收敛空安全判断。 */
    private val bodyText: Editable
        get() = requireNotNull(binding.bodyInput.text)

    private val viewModel: EditorViewModel by viewModels()
    private var latestState = EditorUiState()
    private var initialized = false
    private var suppressWatcher = false
    private var pendingFontId: Long? = null
    private var pendingSizeLevel: Int? = null
    private var saveJob: Job? = null
    private var limitToastShown = false
    private var imageLimitToastShown = false
    private var exiting = false
    private var appliedBaseStyle: Pair<Long?, Int>? = null
    private var renderedTags: List<com.diary.core.model.Tag>? = null

    private val imagePicker =
        registerForActivityResult(
            ActivityResultContracts.GetContent(),
        ) { uri ->
            if (uri != null) insertImage(uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.backButton.setOnClickListener { exitEditor() }
        binding.doneButton.setOnClickListener { exitEditor() }
        binding.categoryPill.setOnClickListener { showCategoryPicker() }
        binding.tagScroll.setOnClickListener { showTagPicker() }
        binding.toolImage.setOnClickListener { pickImage() }
        binding.toolAlign.setOnClickListener { applyAlignment() }
        binding.toolList.setOnClickListener { applyList() }
        binding.toolFont.setOnClickListener { showFontPicker() }
        binding.toolSize.setOnClickListener { showSizePicker() }
        binding.toolMore.setOnClickListener { showMoreActions() }
        binding.selFont.setOnClickListener { showFontPicker() }
        binding.selSize.setOnClickListener { showSizePicker() }
        binding.selBold.setOnClickListener { toggleBold() }
        binding.selItalic.setOnClickListener { toggleItalic() }
        binding.selUnderline.setOnClickListener { toggleUnderline() }
        binding.selColor.setOnClickListener { showColorPicker() }
        binding.bodyInput.filters =
            arrayOf(
                InputFilter.LengthFilter(DiaryLimits.MAX_CONTENT_CHARS),
            )
        binding.bodyInput.onSelectionChangedListener = { start, end ->
            if (!suppressWatcher) {
                if (start == end || start < 0) {
                    binding.selectionBar.isVisible = false
                } else {
                    binding.selectionBar.isVisible = true
                    updateSelectionBar(start, end)
                    binding.bodyInput.post { positionSelectionBar() }
                }
            }
        }
        binding.editorScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (binding.selectionBar.isVisible) positionSelectionBar()
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = exitEditor()
            },
        )

        childFragmentManager.setFragmentResultListener(
            FontPickResult.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle -> handleFontPick(FontPickResult.read(bundle)) }
        childFragmentManager.setFragmentResultListener(
            SizePickResult.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle -> handleSizePick(SizePickResult.read(bundle)) }
        childFragmentManager.setFragmentResultListener(
            TagPickerDialog.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val ids = bundle.getLongArray(TagPickerDialog.ARG_SELECTED)?.toList().orEmpty()
            viewModel.setTags(ids)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        latestState = state
                        if (state.missing) {
                            DiaryUi.toast(requireContext(), "这篇日记不存在或已删除")
                            navigateUpSafely()
                        } else {
                            if (!state.loading && !initialized) {
                                initialized = true
                                renderContent(state)
                            }
                            if (initialized) updateChrome(state)
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (initialized && !exiting) persist()
        super.onStop()
    }

    override fun onDestroyView() {
        saveJob?.cancel()
        binding.bodyInput.onSelectionChangedListener = null
        renderedTags = null
        super.onDestroyView()
        _binding = null
    }

    // ---------- 渲染 ----------

    private fun renderContent(state: EditorUiState) {
        val diary = state.diary ?: return
        suppressWatcher = true
        binding.titleInput.setText(diary.title.orEmpty())
        binding.titleInput.setSelection(binding.titleInput.text.length)
        applyBaseStyle(state)
        appliedBaseStyle = state.baseFontId to state.baseSizeLevel
        val spannable =
            RichTextSpans.toSpannable(
                document = state.document,
                baseSizeSp = state.baseSizeSp,
                listGapPx = DiaryUi.dp(requireContext(), 24),
                fontResolver = { fontId -> state.fonts[fontId] },
                imageResolver = { path -> viewModel.imageDrawable(path) },
                imageMaxWidthPx = viewModel.imageWidthPx(),
                // 显式设置的“黑色”跟随主题正文色，深色模式下不会黑底黑字。
                colorMapper = { color ->
                    if (color == CONTENT_BLACK) DiaryColor.of(requireContext()).ink else color
                },
            )
        binding.bodyInput.setText(spannable)
        if (spannable.isEmpty()) {
            binding.bodyInput.setSelection(0)
        } else {
            binding.bodyInput.setSelection(spannable.length)
        }
        suppressWatcher = false

        binding.titleInput.addTextChangedListener(titleWatcher)
        binding.bodyInput.addTextChangedListener(bodyWatcher)
        updateMeta()
        if (diary.title.isNullOrBlank() && spannable.isEmpty()) {
            binding.titleInput.requestFocus()
        }
    }

    private fun updateChrome(state: EditorUiState) {
        binding.categoryName.text = state.categoryName
        renderTags(state)
        val baseKey = state.baseFontId to state.baseSizeLevel
        if (appliedBaseStyle != baseKey) {
            appliedBaseStyle = baseKey
            applyBaseStyle(state)
        }
        val indicator = binding.saveState
        val palette = DiaryColor.of(requireContext())
        when (val save = state.saveState) {
            SaveState.Idle -> indicator.text = ""
            SaveState.Saving -> {
                indicator.text = "保存中…"
                indicator.setTextColor(palette.muted)
            }
            is SaveState.Saved -> {
                indicator.text = "已保存 ${DiaryFormats.time(save.at)}"
                indicator.setTextColor(palette.muted)
            }
            SaveState.Failed -> {
                indicator.text = "保存失败"
                indicator.setTextColor(palette.danger)
            }
        }
        if (binding.bodyInput.hasSelection()) {
            updateSelectionBar(binding.bodyInput.selectionStart, binding.bodyInput.selectionEnd)
        }
    }

    private fun applyBaseStyle(state: EditorUiState) {
        binding.bodyInput.typeface = state.baseTypeface
        binding.bodyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, state.baseSizeSp)
        binding.titleInput.setTypeface(state.baseTypeface, Typeface.BOLD)
        binding.titleInput.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            maxOf(20f, state.baseSizeSp * 1.32f),
        )
    }

    private fun updateMeta() {
        val text = bodyText
        val chars = text.count { !it.isWhitespace() && it != RichTextCodec.IMAGE_PLACEHOLDER }
        val images = RichTextSpans.countImages(text)
        val limitNote =
            when {
                chars >= DiaryLimits.MAX_CONTENT_CHARS -> " · 已达 2 万字上限"
                images >= DiaryLimits.MAX_IMAGES -> " · 图片已达 20 张上限"
                else -> ""
            }
        val date = latestState.diary?.createdAt?.let(DiaryFormats::date).orEmpty()
        binding.metaText.text =
            listOf(
                date,
                "${DiaryFormats.charCount(chars)} 字",
                "$images 图$limitNote",
            ).filter { it.isNotBlank() }.joinToString(" · ")
        binding.metaText.setTextColor(
            if (limitNote.isBlank()) {
                DiaryColor.of(requireContext()).muted
            } else {
                DiaryColor.of(requireContext()).danger
            },
        )
        if (chars >= DiaryLimits.MAX_CONTENT_CHARS && !limitToastShown) {
            limitToastShown = true
            DiaryUi.toast(requireContext(), "正文最多 2 万字，已达上限")
        }
        if (images >= DiaryLimits.MAX_IMAGES && !imageLimitToastShown) {
            imageLimitToastShown = true
            DiaryUi.toast(requireContext(), "单篇最多插入 20 张图片")
        }
    }

    // ---------- 保存 ----------

    private val titleWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                scheduleSave()
            }
        }

    private val bodyWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) {
                if (suppressWatcher || count <= 0 || s !is Editable) return
                applyPendingStyle(s, start, start + count)
            }

            override fun afterTextChanged(s: Editable?) {
                if (suppressWatcher) return
                updateMeta()
                scheduleSave()
            }
        }

    private fun applyPendingStyle(
        text: Editable,
        start: Int,
        end: Int,
    ) {
        pendingFontId?.let { fontId ->
            EditorFormatting.applyFont(
                text,
                start,
                end,
                fontId,
                viewModel.typefaceOf(fontId),
            )
        }
        pendingSizeLevel?.let { level ->
            EditorFormatting.applySizeLevel(
                text,
                start,
                end,
                level,
                FontSizeLevel.sizeSpOf(level),
            )
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
            viewLifecycleOwner.lifecycleScope.launch {
                delay(600)
                persist()
            }
    }

    private fun persist() {
        if (!initialized) return
        val document = RichTextSpans.fromSpannable(bodyText)
        viewModel.requestSave(binding.titleInput.text.toString(), document)
    }

    private fun exitEditor() {
        if (exiting) return
        exiting = true
        if (!initialized) {
            navigateUpSafely()
            return
        }
        saveJob?.cancel()
        hideKeyboard()
        val blank =
            binding.titleInput.text.isNullOrBlank() &&
                bodyText.isBlank()
        if (blank && latestState.isNewDiary) {
            viewModel.discardIfBlank(true) { navigateUpSafely() }
        } else {
            val document = RichTextSpans.fromSpannable(bodyText)
            viewModel.requestSave(binding.titleInput.text.toString(), document) {
                navigateUpSafely()
            }
        }
    }

    private fun navigateUpSafely() {
        if (!isAdded || isRemoving) return
        findNavController().navigateUp()
    }

    private fun hideKeyboard() {
        val manager =
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager ?: return
        manager.hideSoftInputFromWindow(binding.bodyInput.windowToken, 0)
    }

    // ---------- 选区工具条 ----------

    private fun updateSelectionBar(
        start: Int,
        end: Int,
    ) {
        val attrs = RichTextSpans.attributesAt(bodyText, start, end)
        binding.selFont.text =
            when {
                attrs.fontMixed -> "字体 · 混合"
                else ->
                    attrs.singleFontId?.let { id ->
                        latestState.fontNames[id]?.take(4) ?: "字体"
                    } ?: "字体"
            }
        binding.selSize.text =
            when {
                attrs.sizeMixed -> "字号 · 混合"
                attrs.singleSizeLevel != null -> FontSizeLevel.of(attrs.singleSizeLevel)?.label ?: "字号"
                else -> "字号"
            }
        setToggleState(binding.selBold, attrs.bold)
        setToggleState(binding.selItalic, attrs.italic)
        setToggleState(binding.selUnderline, attrs.underline)
    }

    private fun setToggleState(
        view: TextView,
        enabled: Boolean,
    ) {
        view.background =
            if (enabled) {
                ContextCompat.getDrawable(requireContext(), com.diary.core.designsystem.R.drawable.ds_bg_soft)
            } else {
                null
            }
        view.setTextColor(
            if (enabled) DiaryColor.of(requireContext()).ink else DiaryColor.of(requireContext()).bg,
        )
    }

    private fun positionSelectionBar() {
        val bar = binding.selectionBar
        if (!bar.isVisible) return
        val body = binding.bodyInput
        val layout = body.layout ?: return
        val selection = body.selectionStart
        if (selection < 0) return
        val line = layout.getLineForOffset(selection)
        val bodyLocation = IntArray(2)
        val rootLocation = IntArray(2)
        body.getLocationInWindow(bodyLocation)
        binding.root.getLocationInWindow(rootLocation)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(DiaryUi.dp(requireContext(), 40), View.MeasureSpec.EXACTLY),
        )
        val barWidth = bar.measuredWidth
        val barHeight = DiaryUi.dp(requireContext(), 40)
        val x =
            body.paddingLeft + layout.getPrimaryHorizontal(selection) +
                bodyLocation[0] - rootLocation[0]
        val lineTop =
            body.paddingTop + layout.getLineTop(line) - body.scrollY +
                bodyLocation[1] - rootLocation[1]
        var top = lineTop - barHeight - DiaryUi.dp(requireContext(), 6)
        if (top < DiaryUi.dp(requireContext(), 4)) {
            top = lineTop + (layout.getLineBottom(line) - layout.getLineTop(line)) +
                DiaryUi.dp(requireContext(), 6)
        }
        val maxLeft =
            (binding.root.width - barWidth - DiaryUi.dp(requireContext(), 8))
                .coerceAtLeast(DiaryUi.dp(requireContext(), 8))
        val left =
            (x - barWidth / 2f).coerceIn(
                DiaryUi.dp(requireContext(), 8).toFloat(),
                maxLeft.toFloat(),
            )
        val params = bar.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = left.toInt()
        params.topMargin = top
        bar.layoutParams = params
    }

    // ---------- 格式操作 ----------

    private fun ensureBodyFocus(): Editable {
        val body = binding.bodyInput
        if (!body.hasFocus()) body.requestFocus()
        return bodyText
    }

    private fun toggleBold() {
        val body = binding.bodyInput
        if (!body.hasSelection()) return
        val attrs = RichTextSpans.attributesAt(bodyText, body.selectionStart, body.selectionEnd)
        EditorFormatting.applyBold(bodyText, body.selectionStart, body.selectionEnd, !attrs.bold)
        body.invalidate()
        updateSelectionBar(body.selectionStart, body.selectionEnd)
        scheduleSave()
    }

    private fun toggleItalic() {
        val body = binding.bodyInput
        if (!body.hasSelection()) return
        val attrs = RichTextSpans.attributesAt(bodyText, body.selectionStart, body.selectionEnd)
        EditorFormatting.applyItalic(bodyText, body.selectionStart, body.selectionEnd, !attrs.italic)
        body.invalidate()
        updateSelectionBar(body.selectionStart, body.selectionEnd)
        scheduleSave()
    }

    private fun toggleUnderline() {
        val body = binding.bodyInput
        if (!body.hasSelection()) return
        val attrs = RichTextSpans.attributesAt(bodyText, body.selectionStart, body.selectionEnd)
        EditorFormatting.applyUnderline(
            bodyText,
            body.selectionStart,
            body.selectionEnd,
            !attrs.underline,
        )
        body.invalidate()
        updateSelectionBar(body.selectionStart, body.selectionEnd)
        scheduleSave()
    }

    private fun showColorPicker() {
        val body = binding.bodyInput
        if (!body.hasSelection()) {
            DiaryUi.toast(requireContext(), "请先选中要设置颜色的文字")
            return
        }
        val colors =
            listOf(
                "黑色" to 0xFF111111.toInt(),
                "红色" to 0xFFB3261E.toInt(),
                "蓝色" to 0xFF1B5E9B.toInt(),
                "绿色" to 0xFF2E7D32.toInt(),
                "橙色" to 0xFFB26A00.toInt(),
            )
        val items = colors.map { DiaryUi.ActionItem(it.first) } + DiaryUi.ActionItem("清除颜色")
        DiaryUi.actionSheet(
            context = requireContext(),
            title = "字体颜色",
            subtitle = "只作用于当前选中的文字",
            items = items,
        ) { index ->
            val start = body.selectionStart
            val end = body.selectionEnd
            if (index < colors.size) {
                EditorFormatting.applyColor(bodyText, start, end, colors[index].second)
            } else {
                bodyText.getSpans(start, end, ForegroundColorSpan::class.java)
                    .forEach { bodyText.removeSpan(it) }
            }
            body.invalidate()
            scheduleSave()
        }
    }

    private fun applyAlignment() {
        val text = ensureBodyFocus()
        val cursor = binding.bodyInput.selectionStart.coerceAtLeast(0)
        val result = EditorFormatting.cycleAlignment(text, cursor)
        val message =
            when (result) {
                com.diary.core.richtext.Align.CENTER -> "已居中"
                com.diary.core.richtext.Align.RIGHT -> "已右对齐"
                else -> "已左对齐"
            }
        DiaryUi.toast(requireContext(), message)
        scheduleSave()
    }

    private fun applyList() {
        val text = ensureBodyFocus()
        val cursor = binding.bodyInput.selectionStart.coerceAtLeast(0)
        val result = EditorFormatting.cycleList(text, cursor, DiaryUi.dp(requireContext(), 24))
        val message =
            when (result) {
                com.diary.core.richtext.ListType.UNORDERED -> "无序列表"
                com.diary.core.richtext.ListType.ORDERED -> "有序列表"
                else -> "已取消列表"
            }
        DiaryUi.toast(requireContext(), message)
        scheduleSave()
    }

    // ---------- 字体与字号 ----------

    private fun showFontPicker() {
        val body = binding.bodyInput
        if (!body.hasSelection()) body.requestFocus()
        val hasSelection = body.hasSelection()
        val attrs =
            if (hasSelection) {
                RichTextSpans.attributesAt(bodyText, body.selectionStart, body.selectionEnd)
            } else {
                InlineAttributes()
            }
        FontPickerDialog.newInstance(
            initialFontId = attrs.singleFontId ?: latestState.baseFontId,
            scope = FontScope.SELECTION,
            allowedScopes = listOf(FontScope.SELECTION, FontScope.WHOLE, FontScope.GLOBAL),
            mixed = attrs.fontMixed,
            selectionActive = hasSelection,
        ).show(childFragmentManager, "font_picker")
    }

    private fun showSizePicker() {
        val body = binding.bodyInput
        if (!body.hasSelection()) body.requestFocus()
        val hasSelection = body.hasSelection()
        val attrs =
            if (hasSelection) {
                RichTextSpans.attributesAt(bodyText, body.selectionStart, body.selectionEnd)
            } else {
                InlineAttributes()
            }
        SizePickerDialog.newInstance(
            initialLevel = attrs.singleSizeLevel ?: latestState.baseSizeLevel,
            defaultLevel = latestState.defaultSizeLevel,
            scope = FontScope.SELECTION,
            allowedScopes = listOf(FontScope.SELECTION, FontScope.WHOLE, FontScope.GLOBAL),
            mixed = attrs.sizeMixed,
            selectionActive = hasSelection,
        ).show(childFragmentManager, "size_picker")
    }

    private fun handleFontPick(result: FontPickResult) {
        when (result.scope) {
            FontScope.SELECTION -> {
                val body = binding.bodyInput
                if (body.hasSelection()) {
                    EditorFormatting.applyFont(
                        bodyText,
                        body.selectionStart,
                        body.selectionEnd,
                        result.fontId,
                        viewModel.typefaceOf(result.fontId),
                    )
                    body.invalidate()
                    updateSelectionBar(body.selectionStart, body.selectionEnd)
                } else {
                    pendingFontId = result.fontId
                    DiaryUi.toast(requireContext(), "之后输入的文字将使用该字体")
                }
                scheduleSave()
            }
            FontScope.WHOLE -> viewModel.applyWholeFont(result.fontId)
            FontScope.GLOBAL -> viewModel.applyGlobalFont(result.fontId)
        }
    }

    private fun handleSizePick(result: SizePickResult) {
        when (result.scope) {
            FontScope.SELECTION -> {
                val body = binding.bodyInput
                if (body.hasSelection()) {
                    EditorFormatting.applySizeLevel(
                        bodyText,
                        body.selectionStart,
                        body.selectionEnd,
                        result.level,
                        FontSizeLevel.sizeSpOf(result.level),
                    )
                    body.invalidate()
                    updateSelectionBar(body.selectionStart, body.selectionEnd)
                } else {
                    pendingSizeLevel = result.level
                    DiaryUi.toast(requireContext(), "之后输入的文字将使用该字号")
                }
                scheduleSave()
            }
            FontScope.WHOLE -> viewModel.applyWholeSize(result.level)
            FontScope.GLOBAL -> viewModel.applyGlobalSize(result.level)
        }
    }

    // ---------- 分类 / 标签 / 更多 / 图片 ----------

    /**
     * 标签行：已选标签按标签库顺序展示，末尾固定一个“＋ 标签”入口。
     * 标签集合未变化时跳过重建，避免每次自动保存都刷新视图。
     */
    private fun renderTags(state: EditorUiState) {
        val tags = state.selectedTags
        if (tags == renderedTags) return
        renderedTags = tags

        binding.tagRow.removeAllViews()
        val palette = DiaryColor.of(requireContext())
        tags.forEach { tag ->
            binding.tagRow.addView(
                tagChip(
                    label = tag.name,
                    background = com.diary.core.designsystem.R.drawable.ds_bg_soft,
                    textColor = palette.ink2,
                ),
            )
        }
        binding.tagRow.addView(
            tagChip(
                label = if (tags.isEmpty()) "＋ 添加标签" else "＋",
                background = com.diary.core.designsystem.R.drawable.ds_bg_outline,
                textColor = palette.muted,
            ),
        )
    }

    private fun tagChip(
        label: String,
        background: Int,
        textColor: Int,
    ): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(textColor)
            setBackgroundResource(background)
            val horizontal = DiaryUi.dp(requireContext(), 10)
            setPadding(horizontal, 0, horizontal, 0)
            val params =
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    DiaryUi.dp(requireContext(), 28),
                )
            params.marginEnd = DiaryUi.dp(requireContext(), 8)
            layoutParams = params
            setOnClickListener { showTagPicker() }
        }

    private fun showTagPicker() {
        TagPickerDialog.newInstance(latestState.selectedTagIds)
            .show(childFragmentManager, "tag_picker")
    }

    private fun showCategoryPicker() {
        val categories = latestState.categories
        if (categories.isEmpty()) return
        val current = latestState.diary?.categoryId
        val items =
            categories.map { category ->
                DiaryUi.ActionItem(
                    label = if (category.id == current) "✓ ${category.name}" else category.name,
                )
            }
        DiaryUi.actionSheet(
            context = requireContext(),
            title = "归入分类",
            subtitle = "切换分类不会改变正文内容",
            items = items,
        ) { index ->
            viewModel.updateCategory(categories[index].id)
        }
    }

    private fun showMoreActions() {
        DiaryUi.actionSheet(
            context = requireContext(),
            title = "更多",
            items =
                listOf(
                    DiaryUi.ActionItem("清除当前段落格式"),
                    DiaryUi.ActionItem("删除这篇日记", danger = true),
                ),
        ) { index ->
            when (index) {
                0 -> clearCurrentFormatting()
                1 -> confirmDelete()
            }
        }
    }

    private fun clearCurrentFormatting() {
        val body = binding.bodyInput
        val start = body.selectionStart.coerceAtLeast(0)
        val end = body.selectionEnd.coerceAtLeast(start)
        EditorFormatting.clearFormatting(bodyText, start, end)
        body.invalidate()
        DiaryUi.toast(requireContext(), "已清除格式")
        scheduleSave()
    }

    private fun confirmDelete() {
        DiaryUi.confirm(
            context = requireContext(),
            title = "删除这篇日记？",
            message = "日记会移入回收站，30 天内可恢复。",
            confirmText = "删除",
            onConfirm = {
                saveJob?.cancel()
                viewModel.deleteDiary { navigateUpSafely() }
            },
        )
    }

    private fun pickImage() {
        if (RichTextSpans.countImages(bodyText) >= DiaryLimits.MAX_IMAGES) {
            DiaryUi.toast(requireContext(), "单篇最多插入 20 张图片")
            return
        }
        imagePicker.launch("image/*")
    }

    private fun insertImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val name = viewModel.saveImage(uri)
            if (name == null) {
                DiaryUi.toast(requireContext(), "图片导入失败，请换一张试试")
                return@launch
            }
            if (RichTextSpans.countImages(bodyText) >= DiaryLimits.MAX_IMAGES) {
                DiaryUi.toast(requireContext(), "单篇最多插入 20 张图片")
                return@launch
            }
            val drawable = viewModel.imageDrawable(name)
            if (drawable == null) {
                DiaryUi.toast(requireContext(), "图片读取失败")
                return@launch
            }
            val editable = bodyText
            val cursor = binding.bodyInput.selectionStart.coerceAtLeast(0)
            val prefix = if (cursor > 0 && editable[cursor - 1] != '\n') "\n" else ""
            val suffix = if (cursor < editable.length && editable[cursor] != '\n') "\n" else ""
            suppressWatcher = true
            editable.insert(cursor, prefix + RichTextCodec.IMAGE_PLACEHOLDER + suffix)
            val imageStart = cursor + prefix.length
            editable.setSpan(
                com.diary.core.richtext.ImagePathSpan(
                    name,
                    drawable,
                    viewModel.imageWidthPx(),
                ),
                imageStart,
                imageStart + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            suppressWatcher = false
            binding.bodyInput.setSelection(
                (imageStart + 1 + suffix.length).coerceAtMost(editable.length),
            )
            updateMeta()
            scheduleSave()
        }
    }

    private companion object {
        const val MAX_TITLE = 50

        /** 与调色板中的“黑色”一致，渲染时替换为当前主题正文色。 */
        const val CONTENT_BLACK = 0xFF111111.toInt()
    }
}
