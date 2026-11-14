package com.diary.core.designsystem

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

/**
 * 全局统一的轻量交互组件：底部操作面板、确认框、单行输入框、Toast。
 * 视觉口径与原型一致，避免各页面重复实现。
 */
object DiaryUi {
    data class ActionItem(
        val label: String,
        val danger: Boolean = false,
        val enabled: Boolean = true,
    )

    fun dp(
        context: Context,
        value: Int,
    ): Int = (value * context.resources.displayMetrics.density).toInt()

    fun toast(
        context: Context,
        message: String,
    ) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 底部操作面板，返回当前对话框以便调用方关闭。
     */
    fun actionSheet(
        context: Context,
        title: String,
        subtitle: String? = null,
        items: List<ActionItem>,
        onSelected: (Int) -> Unit,
    ): BottomSheetDialog {
        val dialog = BottomSheetDialog(context)
        val palette = DiaryColor.of(context)
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(palette.bg)
            }

        val grabber =
            View(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(context, 36), dp(context, 4)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = dp(context, 8)
                        bottomMargin = dp(context, 4)
                    }
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(context, 2).toFloat()
                        setColor(palette.lineStrong)
                    }
            }
        container.addView(grabber)

        container.addView(
            TextView(context).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.ink)
                setPadding(
                    dp(context, 20),
                    dp(context, 10),
                    dp(context, 20),
                    if (subtitle == null) {
                        dp(context, 10)
                    } else {
                        dp(context, 4)
                    },
                )
            },
        )

        if (!subtitle.isNullOrBlank()) {
            container.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(palette.muted)
                    setPadding(dp(context, 20), 0, dp(context, 20), dp(context, 12))
                },
            )
        }

        items.forEachIndexed { index, item ->
            container.addView(
                TextView(context).apply {
                    text = item.label
                    textSize = 16f
                    setTextColor(
                        when {
                            !item.enabled -> palette.lineStrong
                            item.danger -> palette.danger
                            else -> palette.ink
                        },
                    )
                    setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
                    isEnabled = item.enabled
                    background = context.getDrawable(R.drawable.ds_bg_ripple)
                    setOnClickListener {
                        if (!item.enabled) return@setOnClickListener
                        dialog.dismiss()
                        onSelected(index)
                    }
                },
            )
            if (index < items.lastIndex) {
                container.addView(
                    View(context).apply {
                        layoutParams =
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                dp(context, 1),
                            )
                        setBackgroundColor(palette.line)
                    },
                )
            }
        }

        container.setPadding(0, 0, 0, dp(context, 12))
        dialog.setContentView(container)
        dialog.show()
        return dialog
    }

    /**
     * 二次确认。危险操作使用红色确认按钮。
     */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmText: String,
        danger: Boolean = true,
        cancelText: String = "取消",
        onConfirm: () -> Unit,
    ) {
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(cancelText, null)
                .setPositiveButton(confirmText) { _, _ -> onConfirm() }
                .create()
        val palette = DiaryColor.of(context)
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                if (danger) palette.danger else palette.ink,
            )
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(palette.ink2)
        }
        dialog.show()
    }

    /**
     * 单行输入对话框，自带非空与长度校验；重名等业务校验由调用方处理。
     */
    fun inputDialog(
        context: Context,
        title: String,
        subtitle: String? = null,
        initialValue: String = "",
        placeholder: String = "",
        maxLength: Int,
        confirmText: String = "保存",
        onConfirm: (String) -> Unit,
    ) {
        val palette = DiaryColor.of(context)
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
            }
        val inputLayout =
            TextInputLayout(context).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
                setBoxBackgroundColor(palette.bgSoft)
                setBoxCornerRadii(
                    dp(context, 8).toFloat(),
                    dp(context, 8).toFloat(),
                    dp(context, 8).toFloat(),
                    dp(context, 8).toFloat(),
                )
            }
        val input =
            EditText(context).apply {
                setText(initialValue)
                hint = placeholder
                textSize = 15f
                filters = arrayOf(InputFilter.LengthFilter(maxLength))
                setSingleLine(true)
            }
        inputLayout.addView(input)
        container.addView(inputLayout)

        if (!subtitle.isNullOrBlank()) {
            container.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(palette.muted)
                    setPadding(0, dp(context, 8), 0, 0)
                },
            )
        }

        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton(confirmText, null)
                .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            positive.setTextColor(palette.ink)
            positive.setOnClickListener {
                val value = input.text.toString().trim()
                when {
                    value.isEmpty() -> inputLayout.error = "名称不能为空"
                    value.length > maxLength -> inputLayout.error = "最多 $maxLength 个字"
                    else -> {
                        dialog.dismiss()
                        onConfirm(value)
                    }
                }
            }
            input.requestFocus()
            input.setSelection(input.text.length)
        }
        dialog.show()
    }
}
