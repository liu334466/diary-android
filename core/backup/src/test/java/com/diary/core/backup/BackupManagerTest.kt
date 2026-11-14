package com.diary.core.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.diary.core.common.DiaryImageStore
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.entity.CategoryEntity
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryTagEntity
import com.diary.core.database.entity.FontAssetEntity
import com.diary.core.database.entity.TagEntity
import com.diary.core.database.repository.BackupRepository
import com.diary.core.database.repository.DiaryBackupContent
import com.diary.core.database.repository.FontRepository
import com.diary.core.database.search.SearchTokenizer
import com.diary.core.font.FontCache
import com.diary.core.font.FontFileStore
import com.diary.core.model.DiaryLimits
import com.diary.core.model.FontAsset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 备份包往返：导出后覆盖恢复，日记、标签关联、插图与字体文件都应回到导出时状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupManagerTest {
    private lateinit var context: Context
    private lateinit var database: DiaryDatabase
    private lateinit var imageStore: DiaryImageStore
    private lateinit var fontFileStore: FontFileStore
    private lateinit var repository: BackupRepository
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java)
                .addCallback(DiaryDatabase.seedCallback { NOW })
                .allowMainThreadQueries()
                .build()
        repository =
            BackupRepository(
                database.diaryDao(),
                database.categoryDao(),
                database.fontAssetDao(),
                database.tagDao(),
                database.diaryFtsDao(),
                database,
            )
        imageStore = DiaryImageStore(context)
        fontFileStore = FontFileStore(context)
        fontFileStore.prepareDirectory()
        val fontRepository =
            FontRepository(
                database.fontAssetDao(),
                database.diaryDao(),
                database,
            )
        manager =
            BackupManager(
                context = context,
                backupRepository = repository,
                imageStore = imageStore,
                fontFileStore = fontFileStore,
                fontCache = FontCache(fontFileStore, fontRepository),
            )
        // 清掉其他用例可能残留的私有目录文件。
        imageStore.listFiles().forEach { it.delete() }
        fontFileStore.directory.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        database.close()
        imageStore.listFiles().forEach { it.delete() }
        fontFileStore.directory.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `导出后恢复还原日记标签插图与字体`() =
        runTest {
            val categoryId =
                database.categoryDao().insert(
                    CategoryEntity(name = "工作", sortOrder = 1, isSystem = false, createdAt = NOW),
                )
            val diaryId =
                database.diaryDao().insert(
                    DiaryEntity(
                        title = "周末的雨",
                        contentJson = "{\"blocks\":[]}",
                        plainText = "雨下了一整天",
                        categoryId = categoryId,
                        charCount = 6,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                )
            val tagId = database.tagDao().insert(TagEntity(name = "旅行", createdAt = NOW))
            database.tagDao().insertLinks(listOf(DiaryTagEntity(diaryId, tagId)))
            database.fontAssetDao().insert(
                FontAssetEntity(
                    displayName = "手写体",
                    fileName = FONT_FILE,
                    fileSize = FONT_BYTES.size.toLong(),
                    source = FontAsset.SOURCE_IMPORTED,
                    status = FontAsset.STATUS_OK,
                    importedAt = NOW,
                ),
            )
            imageStore.fileOf(IMAGE_FILE).writeBytes(IMAGE_BYTES)
            fontFileStore.fileOf(FONT_FILE).writeBytes(FONT_BYTES)

            val exported = ByteArrayOutputStream()
            val exportSummary = manager.exportTo(exported)
            assertEquals(1, exportSummary.diaryCount)
            assertEquals(1, exportSummary.tagCount)
            assertEquals(1, exportSummary.imageCount)
            assertEquals(1, exportSummary.fontCount)

            // 导出后继续使用应用：新增日记、删除插图与字体文件。
            database.diaryDao().insert(
                DiaryEntity(
                    title = "导出之后",
                    contentJson = "{\"blocks\":[]}",
                    plainText = "新内容",
                    categoryId = DiaryLimits.UNCATEGORIZED_ID,
                    charCount = 3,
                    createdAt = NOW + 1_000,
                    updatedAt = NOW + 1_000,
                ),
            )
            imageStore.listFiles().forEach { it.delete() }
            fontFileStore.fileOf(FONT_FILE).delete()

            val restoreSummary =
                manager.restoreFrom(
                    ByteArrayInputStream(exported.toByteArray()),
                )
            assertEquals(1, restoreSummary.diaryCount)

            val diaries = database.diaryDao().getAll()
            assertEquals(listOf("周末的雨"), diaries.map { it.title })
            assertEquals(categoryId, diaries.first().categoryId)
            assertEquals(listOf(tagId), database.tagDao().tagIdsOf(diaryId))
            assertTrue(imageStore.fileOf(IMAGE_FILE).exists())
            assertTrue(fontFileStore.fileOf(FONT_FILE).exists())
            assertEquals(
                1,
                database.diaryFtsDao().search(
                    requireNotNull(SearchTokenizer.matchQuery("雨")),
                    10,
                ).size,
            )
        }

    @Test
    fun `恢复时缺少系统分类会补回并把失效分类归入未分类`() =
        runTest {
            val id =
                database.diaryDao().insert(
                    DiaryEntity(
                        title = "孤儿分类",
                        contentJson = "{\"blocks\":[]}",
                        plainText = "",
                        categoryId = 99L,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                )

            repository.replaceAll(
                DiaryBackupContent(diaries = database.diaryDao().getAll().filter { it.id == id }),
            )

            assertNotNull(database.categoryDao().getById(DiaryLimits.UNCATEGORIZED_ID))
            assertEquals(
                DiaryLimits.UNCATEGORIZED_ID,
                database.diaryDao().getById(id)?.categoryId,
            )
        }

    @Test
    fun `非本应用备份文件被拒绝且不改动现有数据`() =
        runTest {
            val diaryId =
                database.diaryDao().insert(
                    DiaryEntity(
                        title = "原有日记",
                        contentJson = "{\"blocks\":[]}",
                        plainText = "",
                        categoryId = DiaryLimits.UNCATEGORIZED_ID,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                )
            val zip = zipOf("{\"format\":\"other-app\",\"version\":1}")

            val error =
                runCatching {
                    manager.restoreFrom(ByteArrayInputStream(zip))
                }.exceptionOrNull()

            assertTrue(error is BackupException)
            assertNotNull(database.diaryDao().getById(diaryId))
            assertEquals(0, database.tagDao().getAll().size)
        }

    private fun zipOf(json: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val IMAGE_FILE = "img_backup_test.jpg"
        const val FONT_FILE = "font_backup_test.ttf"
        val IMAGE_BYTES = byteArrayOf(1, 2, 3, 4)
        val FONT_BYTES = byteArrayOf(5, 6, 7, 8)
    }
}
