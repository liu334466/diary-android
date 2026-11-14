package com.diary.feature.editor

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import androidx.appcompat.widget.AppCompatEditText

/**
 * 可感知选区变化的正文输入框，用于浮出选区工具条。
 * 屏蔽系统自带的浮动菜单，避免与自定义选区工具条重复。
 */
class RichEditText
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = android.R.attr.editTextStyle,
    ) : AppCompatEditText(context, attrs, defStyleAttr) {
        var onSelectionChangedListener: ((start: Int, end: Int) -> Unit)? = null

        init {
            customSelectionActionModeCallback =
                object : ActionMode.Callback {
                    override fun onCreateActionMode(
                        mode: ActionMode?,
                        menu: Menu?,
                    ): Boolean {
                        menu?.clear()
                        return true
                    }

                    override fun onPrepareActionMode(
                        mode: ActionMode?,
                        menu: Menu?,
                    ): Boolean {
                        menu?.clear()
                        mode?.let { actionMode ->
                            post { actionMode.hide(0) }
                        }
                        return true
                    }

                    override fun onActionItemClicked(
                        mode: ActionMode?,
                        item: android.view.MenuItem?,
                    ): Boolean = false

                    override fun onDestroyActionMode(mode: ActionMode?) = Unit
                }
        }

        override fun onSelectionChanged(
            selStart: Int,
            selEnd: Int,
        ) {
            super.onSelectionChanged(selStart, selEnd)
            onSelectionChangedListener?.invoke(selStart, selEnd)
        }
    }
