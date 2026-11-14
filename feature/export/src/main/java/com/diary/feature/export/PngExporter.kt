package com.diary.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PNG 长图导出：与阅读页同构的 TextView 离屏绘制。
 * 宽度固定 1080px，超过单段高度上限时按内容顺序分段输出多张，避免一次性分配超大 Bitmap。
 */
@Singleton
class PngExporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun export(
            content: ExportContent,
            directory: File,
            baseName: String,
            onProgress: (Int) -> Unit,
            isCancelled: () -> Boolean,
        ): List<File> =
            withContext(Dispatchers.Main) {
                val view = buildView(content)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)

                val totalHeight = view.measuredHeight.coerceAtLeast(1)
                val cuts = cutPoints(view, totalHeight)
                val partCount = cuts.size - 1
                val files = ArrayList<File>(partCount)
                try {
                    for (index in 0 until partCount) {
                        if (isCancelled()) throw CancellationException("导出已取消")
                        val top = cuts[index]
                        val height = cuts[index + 1] - top
                        val bitmap = Bitmap.createBitmap(WIDTH_PX, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.translate(0f, -top.toFloat())
                        view.draw(canvas)

                        val file =
                            File(
                                directory,
                                if (partCount == 1) "$baseName.png" else "$baseName-${index + 1}.png",
                            )
                        file.outputStream().use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        bitmap.recycle()
                        files += file
                        onProgress((index + 1) * 100 / partCount)
                    }
                } catch (throwable: Throwable) {
                    files.forEach { it.delete() }
                    throw throwable
                }
                files
            }

        /**
         * 分段边界优先落在正文行底，避免同一行文字被切到两张图里。
         */
        private fun cutPoints(
            root: View,
            totalHeight: Int,
        ): List<Int> {
            val candidates = sortedSetOf(0, totalHeight)
            val container = root as? LinearLayout
            val body = container?.getChildAt(container.childCount - 1) as? TextView
            val layout = body?.layout
            if (body != null && layout != null) {
                val offset = body.top + body.paddingTop
                for (line in 0 until layout.lineCount) {
                    val bottom = offset + layout.getLineBottom(line)
                    if (bottom in 1 until totalHeight) candidates += bottom
                }
            }
            val result = ArrayList<Int>()
            result += 0
            var current = 0
            while (current < totalHeight) {
                val limit = current + PART_HEIGHT_PX
                val next =
                    candidates
                        .filter { it > current && it <= limit }
                        .maxOrNull()
                        ?: minOf(limit, totalHeight)
                result += next
                current = next
            }
            return result
        }

        private fun buildView(content: ExportContent): View {
            val container =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    setPadding(PADDING_PX, TOP_PADDING_PX, PADDING_PX, BOTTOM_PADDING_PX)
                }
            container.addView(
                TextView(context).apply {
                    text = content.meta
                    setTextColor(MUTED_COLOR)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, META_PX)
                    includeFontPadding = false
                },
            )
            container.addView(
                TextView(context).apply {
                    text = content.title
                    setTextColor(INK_COLOR)
                    setTypeface(content.baseTypeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, TITLE_PX)
                    setLineSpacing(TITLE_EXTRA_PX, 1f)
                    includeFontPadding = false
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = TITLE_MARGIN_PX }
                },
            )
            if (content.document.blocks.any { it.text.isNotBlank() }) {
                container.addView(
                    TextView(context).apply {
                        setText(content.spannable(SCALE, LIST_GAP_PX, CONTENT_WIDTH_PX))
                        setTextColor(INK_COLOR)
                        setTypeface(content.baseTypeface)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, content.baseSizeSp * SCALE)
                        setLineSpacing(0f, BODY_LINE_SPACING)
                        includeFontPadding = false
                        layoutParams =
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { topMargin = BODY_MARGIN_PX }
                    },
                )
            }
            return container
        }

        companion object {
            /** 长图固定宽度，保证同一篇日记在任何设备导出的像素宽度一致。 */
            const val WIDTH_PX = 1080
            private const val PART_HEIGHT_PX = 8000
            private const val SCALE = 2f
            private const val PADDING_PX = 64
            private const val TOP_PADDING_PX = 72
            private const val BOTTOM_PADDING_PX = 96
            private const val META_PX = 24f
            private const val TITLE_PX = 48f
            private const val TITLE_EXTRA_PX = 8f
            private const val TITLE_MARGIN_PX = 28
            private const val BODY_MARGIN_PX = 40
            private const val BODY_LINE_SPACING = 1.9f
            private const val LIST_GAP_PX = 48
            private const val CONTENT_WIDTH_PX = WIDTH_PX - PADDING_PX * 2
            private val INK_COLOR = Color.parseColor("#111111")
            private val MUTED_COLOR = Color.parseColor("#8A8A8A")
        }
    }
