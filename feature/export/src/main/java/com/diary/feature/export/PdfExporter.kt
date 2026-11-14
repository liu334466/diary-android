package com.diary.feature.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PDF 导出：A4 比例分页，正文以文本方式绘制并嵌入当前字体，保证文字可复制、可检索。
 * 画布按 2 倍绘制后再落到 A4 页面，兼顾图片清晰度与字号换算。
 */
@Singleton
class PdfExporter
    @Inject
    constructor() {
        private data class Block(val layout: StaticLayout, val spaceAfter: Float)

        suspend fun export(
            content: ExportContent,
            file: File,
            onProgress: (Int) -> Unit,
            isCancelled: () -> Boolean,
        ): File =
            withContext(Dispatchers.Default) {
                val document = PdfDocument()
                var pageIndex = 0
                var page: PdfDocument.Page? = null
                var canvas: Canvas? = null
                var y = TOP_MARGIN_PX

                val metaPaint =
                    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = MUTED_COLOR
                        textSize = META_PX
                    }
                val titlePaint =
                    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = INK_COLOR
                        textSize = TITLE_PX
                        typeface = Typeface.create(content.baseTypeface, Typeface.BOLD)
                    }
                val bodyPaint =
                    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = INK_COLOR
                        textSize = content.baseSizeSp * SCALE
                        typeface = content.baseTypeface
                    }

                val blocks =
                    buildList {
                        add(Block(staticLayout(content.meta, metaPaint, CONTENT_WIDTH_PX, 1f), META_GAP_PX))
                        add(Block(staticLayout(content.title, titlePaint, CONTENT_WIDTH_PX, 1.15f), TITLE_GAP_PX))
                        if (content.document.blocks.any { it.text.isNotBlank() }) {
                            val body = content.spannable(SCALE, LIST_GAP_PX, CONTENT_WIDTH_PX)
                            add(Block(staticLayout(body, bodyPaint, CONTENT_WIDTH_PX, BODY_LINE_SPACING), 0f))
                        }
                    }

                val totalHeight =
                    blocks.fold(0f) { acc, block ->
                        acc + block.layout.height + block.spaceAfter
                    }
                val pageContentHeight = PAGE_HEIGHT_PX - TOP_MARGIN_PX - BOTTOM_MARGIN_PX
                val estimatedPages = ((totalHeight / pageContentHeight).toInt() + 1).coerceAtLeast(1)

                fun openPage() {
                    pageIndex++
                    val newPage =
                        document.startPage(
                            PdfDocument.PageInfo.Builder(PAGE_WIDTH_PT, PAGE_HEIGHT_PT, pageIndex).create(),
                        )
                    page = newPage
                    val newCanvas = newPage.canvas
                    newCanvas.drawColor(Color.WHITE)
                    newCanvas.scale(SCALE, SCALE)
                    canvas = newCanvas
                    y = TOP_MARGIN_PX
                }

                fun closePage() {
                    page?.let(document::finishPage)
                    page = null
                    canvas = null
                    onProgress((pageIndex * 100 / estimatedPages).coerceIn(0, 99))
                }

                try {
                    blocks.forEach { block ->
                        val layout = block.layout
                        var line = 0
                        while (line < layout.lineCount) {
                            if (isCancelled()) throw CancellationException("导出已取消")
                            if (canvas == null) openPage()
                            val lineTop = layout.getLineTop(line)
                            val available = PAGE_HEIGHT_PX - BOTTOM_MARGIN_PX - y
                            var end = line
                            while (end < layout.lineCount &&
                                layout.getLineBottom(end) - lineTop <= available
                            ) {
                                end++
                            }
                            if (end == line) end++
                            val sliceBottom = layout.getLineBottom(end - 1)
                            val target = requireNotNull(canvas)
                            target.save()
                            target.translate(LEFT_MARGIN_PX.toFloat(), y - lineTop)
                            target.clipRect(0f, lineTop.toFloat(), CONTENT_WIDTH_PX.toFloat(), sliceBottom.toFloat())
                            layout.draw(target)
                            target.restore()
                            y += (sliceBottom - lineTop)
                            line = end
                            if (line < layout.lineCount) closePage()
                        }
                        y += block.spaceAfter
                    }
                    if (pageIndex == 0) openPage()
                    closePage()
                    if (isCancelled()) throw CancellationException("导出已取消")

                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use(document::writeTo)
                    if (file.length() <= 0L) {
                        throw IllegalStateException("PDF 写入失败")
                    }
                    file
                } catch (throwable: Throwable) {
                    runCatching { document.close() }
                    file.delete()
                    throw throwable
                } finally {
                    runCatching { document.close() }
                }
            }

        private fun staticLayout(
            text: CharSequence,
            paint: TextPaint,
            width: Int,
            lineSpacingMultiplier: Float,
        ): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, lineSpacingMultiplier)
                .setIncludePad(false)
                .build()

        companion object {
            private const val PAGE_WIDTH_PT = 595
            private const val PAGE_HEIGHT_PT = 842
            private const val SCALE = 2f
            private const val PAGE_HEIGHT_PX = (PAGE_HEIGHT_PT * SCALE).toInt()
            private const val LEFT_MARGIN_PX = 96
            private const val TOP_MARGIN_PX = 112f
            private const val BOTTOM_MARGIN_PX = 96f
            private const val CONTENT_WIDTH_PX = (PAGE_WIDTH_PT * SCALE).toInt() - LEFT_MARGIN_PX * 2
            private const val META_PX = 24f
            private const val TITLE_PX = 48f
            private const val META_GAP_PX = 28f
            private const val TITLE_GAP_PX = 40f
            private const val BODY_LINE_SPACING = 1.9f
            private const val LIST_GAP_PX = 48
            private val INK_COLOR = Color.parseColor("#111111")
            private val MUTED_COLOR = Color.parseColor("#8A8A8A")
        }
    }
