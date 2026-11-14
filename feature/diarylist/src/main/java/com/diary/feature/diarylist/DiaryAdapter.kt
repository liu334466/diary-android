package com.diary.feature.diarylist

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diary.core.common.DiaryFormats
import com.diary.core.database.search.SearchTokenizer
import com.diary.core.designsystem.DiaryColor
import com.diary.core.model.Diary
import com.diary.core.model.DiaryLimits
import com.diary.feature.diarylist.databinding.ItemDiaryBinding

/**
 * 首页与搜索页共用的日记列表：标题、两行摘要、日期与分类。
 * [highlightTerms] 非空时对标题与摘要中的关键词着色，用于搜索结果。
 */
class DiaryAdapter(
    private val onClick: (Diary) -> Unit,
) : ListAdapter<Diary, DiaryAdapter.DiaryViewHolder>(Diff) {
    private var categoryNames: Map<Long, String> = emptyMap()
    private var diaryTags: Map<Long, List<String>> = emptyMap()
    private var highlightTerms: List<String> = emptyList()

    fun submit(
        diaries: List<Diary>,
        categories: Map<Long, String>,
        highlight: String? = null,
        tags: Map<Long, List<String>> = emptyMap(),
    ) {
        // 分类重命名、标签变化与关键词变化都不改变 Diary 内容，DiffUtil 不会重绑，这里主动刷新一次。
        val termsChanged = highlight != null && SearchTokenizer.termsOf(highlight) != highlightTerms
        if (highlight != null) highlightTerms = SearchTokenizer.termsOf(highlight)
        val namesChanged = categories != categoryNames
        val tagsChanged = tags != diaryTags
        categoryNames = categories
        diaryTags = tags
        submitList(diaries) {
            if (namesChanged || termsChanged || tagsChanged) notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): DiaryViewHolder =
        DiaryViewHolder(
            ItemDiaryBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun onBindViewHolder(
        holder: DiaryViewHolder,
        position: Int,
    ) {
        val diary = getItem(position)
        holder.bind(diary, categoryNames, highlightTerms, diaryTags[diary.id].orEmpty())
    }

    inner class DiaryViewHolder(
        private val binding: ItemDiaryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            diary: Diary,
            names: Map<Long, String>,
            terms: List<String>,
            tags: List<String>,
        ) {
            val hasTitle = !diary.title.isNullOrBlank()
            val title = if (hasTitle) diary.title.orEmpty() else DiaryLimits.UNTITLED_DIARY_NAME
            binding.title.text = highlight(title, terms)
            binding.title.setTextColor(
                if (hasTitle) {
                    DiaryColor.of(binding.root.context).ink
                } else {
                    DiaryColor.of(binding.root.context).muted
                },
            )
            val excerpt = diary.plainText.replace(WHITESPACE, " ").trim()
            binding.excerpt.text = highlight(excerpt, terms)
            binding.excerpt.visibility =
                if (excerpt.isEmpty()) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
            binding.date.text = DiaryFormats.date(diary.createdAt)
            binding.category.text = names[diary.categoryId] ?: DiaryLimits.UNCATEGORIZED_NAME
            val visibleTags = tags.take(MAX_VISIBLE_TAGS)
            binding.tags.text = visibleTags.joinToString(" ") { "#$it" }
            binding.tags.visibility =
                if (visibleTags.isEmpty()) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
            binding.root.setOnClickListener { onClick(diary) }
        }

        /**
         * 关键词着色：中性灰底 + 加粗，与整体黑白灰基调一致。
         */
        private fun highlight(
            text: String,
            terms: List<String>,
        ): CharSequence {
            if (text.isEmpty() || terms.isEmpty()) return text
            val spannable = SpannableString(text)
            val context = binding.root.context
            val background = DiaryColor.of(context).highlight
            val lowered = text.lowercase()
            terms.forEach { term ->
                if (term.isEmpty()) return@forEach
                var index = lowered.indexOf(term)
                while (index >= 0) {
                    val end = (index + term.length).coerceAtMost(text.length)
                    spannable.setSpan(
                        BackgroundColorSpan(background),
                        index,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        index,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    index = lowered.indexOf(term, end)
                }
            }
            return spannable
        }
    }

    private object Diff : DiffUtil.ItemCallback<Diary>() {
        override fun areItemsTheSame(
            oldItem: Diary,
            newItem: Diary,
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: Diary,
            newItem: Diary,
        ): Boolean = oldItem == newItem
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** 列表项最多展示的标签数，超出部分靠搜索页查看。 */
        const val MAX_VISIBLE_TAGS = 2
    }
}
