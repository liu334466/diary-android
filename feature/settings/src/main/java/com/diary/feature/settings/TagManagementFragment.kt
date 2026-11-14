package com.diary.feature.settings

import android.os.Bundle
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diary.core.designsystem.DiaryUi
import com.diary.core.model.DiaryLimits
import com.diary.feature.settings.databinding.FragmentTagManagementBinding
import com.diary.feature.settings.databinding.ItemTagManageBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 标签管理：查看全部标签、重命名（长按）与删除（带影响面提示）。
 */
@AndroidEntryPoint
class TagManagementFragment : Fragment() {
    private var _binding: FragmentTagManagementBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: TagManagementViewModel by viewModels()
    private lateinit var adapter: TagAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTagManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }

        adapter =
            TagAdapter(
                onRename = { item -> showRename(item) },
                onDelete = { item -> confirmDelete(item) },
            )
        binding.tagList.layoutManager = LinearLayoutManager(requireContext())
        binding.tagList.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.events.collect { message ->
                        DiaryUi.toast(requireContext(), message)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.tagList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: TagManagementUiState) {
        if (state.loading) return
        binding.tagCount.text = "${state.tags.size} / ${DiaryLimits.MAX_TAGS}"
        adapter.submitList(state.tags)
        binding.emptyHint.isVisible = state.tags.isEmpty()
        binding.tagList.isVisible = state.tags.isNotEmpty()
    }

    private fun showRename(item: TagItem) {
        DiaryUi.inputDialog(
            context = requireContext(),
            title = "重命名标签",
            subtitle = "长度不超过 ${DiaryLimits.MAX_TAG_NAME_LENGTH} 个字，重名会被拒绝",
            initialValue = item.tag.name,
            placeholder = "标签名",
            maxLength = DiaryLimits.MAX_TAG_NAME_LENGTH,
        ) { name -> viewModel.rename(item.tag, name) }
    }

    private fun confirmDelete(item: TagItem) {
        val consequence =
            if (item.usage > 0) {
                "该标签正被 ${item.usage} 篇日记使用，删除后这些日记会失去这个标签，日记内容不受影响。"
            } else {
                "这个标签还没有被任何日记使用。"
            }
        DiaryUi.confirm(
            context = requireContext(),
            title = "删除标签「${item.tag.name}」？",
            message = consequence,
            confirmText = "删除",
        ) {
            viewModel.delete(item.tag)
        }
    }

    private class TagAdapter(
        private val onRename: (TagItem) -> Unit,
        private val onDelete: (TagItem) -> Unit,
    ) : ListAdapter<TagItem, TagAdapter.TagViewHolder>(Diff) {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): TagViewHolder =
            TagViewHolder(
                ItemTagManageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(
            holder: TagViewHolder,
            position: Int,
        ) {
            holder.bind(getItem(position))
        }

        inner class TagViewHolder(
            private val binding: ItemTagManageBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: TagItem) {
                binding.tagName.text = item.tag.name
                binding.tagUsage.text = if (item.usage > 0) "${item.usage} 篇" else "未使用"
                binding.root.setOnLongClickListener {
                    onRename(item)
                    true
                }
                binding.deleteButton.setOnClickListener { onDelete(item) }
            }
        }

        private object Diff : DiffUtil.ItemCallback<TagItem>() {
            override fun areItemsTheSame(
                oldItem: TagItem,
                newItem: TagItem,
            ): Boolean = oldItem.tag.id == newItem.tag.id

            override fun areContentsTheSame(
                oldItem: TagItem,
                newItem: TagItem,
            ): Boolean = oldItem == newItem
        }
    }
}
