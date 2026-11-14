package com.diary.core.backup

import android.content.Context
import android.net.Uri
import com.diary.core.common.DiaryImageStore
import com.diary.core.database.entity.CategoryEntity
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryTagLink
import com.diary.core.database.entity.FontAssetEntity
import com.diary.core.database.entity.TagEntity
import com.diary.core.database.repository.BackupRepository
import com.diary.core.database.repository.DiaryBackupContent
import com.diary.core.font.FontCache
import com.diary.core.font.FontFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地备份与恢复。
 *
 * 备份是一个 ZIP 包：`backup.json` 记录分类、日记、标签、关联与字体元信息，
 * `images/` 与 `fonts/` 存放插图与导入字体文件本体，全部来自应用私有目录。
 *
 * 恢复采用覆盖式策略：先校验清单，再把文件落盘并整体替换数据库内容，
 * 任何一步失败都不会留下半份数据（数据库写入在单个事务内完成）。
 */
@Singleton
class BackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val backupRepository: BackupRepository,
        private val imageStore: DiaryImageStore,
        private val fontFileStore: FontFileStore,
        private val fontCache: FontCache,
    ) {
        suspend fun export(uri: Uri): BackupSummary =
            withContext(Dispatchers.IO) {
                val stream =
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw BackupException("无法写入备份文件")
                stream.use { exportTo(it) }
            }

        suspend fun restore(uri: Uri): BackupSummary =
            withContext(Dispatchers.IO) {
                val stream =
                    context.contentResolver.openInputStream(uri)
                        ?: throw BackupException("无法读取备份文件")
                stream.use { restoreFrom(it) }
            }

        /** 导出到任意输出流；测试可直接传入内存流。 */
        suspend fun exportTo(output: OutputStream): BackupSummary =
            withContext(Dispatchers.IO) {
                val content = backupRepository.snapshot()
                val images =
                    imageStore.listFiles()
                        .filter { it.name.isNotBlank() && !it.name.endsWith(".tmp") }
                val fontFiles =
                    content.fonts
                        .map { fontFileStore.fileOf(it.fileName) }
                        .filter { it.isFile }
                val createdAt = System.currentTimeMillis()
                val json =
                    buildJson(
                        content = content,
                        imageNames = images.map { it.name },
                        fontNames = fontFiles.map { it.name },
                        createdAt = createdAt,
                    )

                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    zip.putNextEntry(ZipEntry(BackupFormat.ENTRY_JSON))
                    zip.write(json)
                    zip.closeEntry()
                    images.forEach { zip.writeFile(BackupFormat.IMAGE_PREFIX + it.name, it) }
                    fontFiles.forEach { zip.writeFile(BackupFormat.FONT_PREFIX + it.name, it) }
                    zip.finish()
                }

                BackupSummary(
                    createdAt = createdAt,
                    diaryCount = content.diaries.size,
                    categoryCount = content.categories.size,
                    tagCount = content.tags.size,
                    imageCount = images.size,
                    fontCount = fontFiles.size,
                )
            }

        /** 从任意输入流恢复；测试可直接传入内存流。 */
        suspend fun restoreFrom(input: InputStream): BackupSummary =
            withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "backup_import_${UUID.randomUUID()}")
                try {
                    temp.mkdirs()
                    val payload = extract(input, temp)
                    val parsed = parse(payload.json)
                    // 清单校验通过后再动文件与数据库，降低半途失败的风险。
                    replaceImages(payload.images)
                    replaceFonts(payload.fonts)
                    backupRepository.replaceAll(parsed.content)
                    BackupSummary(
                        createdAt = parsed.createdAt,
                        diaryCount = parsed.content.diaries.size,
                        categoryCount = parsed.content.categories.size,
                        tagCount = parsed.content.tags.size,
                        imageCount = payload.images.size,
                        fontCount = payload.fonts.size,
                    )
                } catch (e: BackupException) {
                    throw e
                } catch (e: Exception) {
                    throw BackupException("恢复失败：${e.message ?: "备份文件无法解析"}")
                } finally {
                    temp.deleteRecursively()
                }
            }

        // ---------- 打包 ----------

        private fun buildJson(
            content: DiaryBackupContent,
            imageNames: List<String>,
            fontNames: List<String>,
            createdAt: Long,
        ): ByteArray =
            JSONObject().apply {
                put(FIELD_FORMAT, BackupFormat.FORMAT)
                put(FIELD_VERSION, BackupFormat.VERSION)
                put(FIELD_CREATED_AT, createdAt)
                put("categories", content.categories.toJsonArray(CategoryEntity::toJson))
                put("diaries", content.diaries.toJsonArray(DiaryEntity::toJson))
                put("tags", content.tags.toJsonArray(TagEntity::toJson))
                put("tagLinks", content.tagLinks.toJsonArray(DiaryTagLink::toJson))
                put("fonts", content.fonts.toJsonArray(FontAssetEntity::toJson))
                put("images", JSONArray(imageNames))
                put("fontFiles", JSONArray(fontNames))
            }.toString().toByteArray(Charsets.UTF_8)

        private fun ZipOutputStream.writeFile(
            entryName: String,
            file: File,
        ) {
            putNextEntry(ZipEntry(entryName))
            file.inputStream().use { source -> source.copyTo(this, BackupFormat.BUFFER_SIZE) }
            closeEntry()
        }

        // ---------- 解包 ----------

        private data class Extracted(
            val json: String,
            val images: List<File>,
            val fonts: List<File>,
        )

        private fun extract(
            input: InputStream,
            temp: File,
        ): Extracted {
            val imagesDir = File(temp, "images").apply { mkdirs() }
            val fontsDir = File(temp, "fonts").apply { mkdirs() }
            var json: String? = null
            var total = 0L
            var entries = 0

            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    entries += 1
                    if (entries > BackupFormat.MAX_ENTRIES) {
                        throw BackupException("备份文件条目过多")
                    }
                    val name = entry.name
                    when {
                        name == BackupFormat.ENTRY_JSON -> {
                            val bytes = zip.readLimited(BackupFormat.MAX_JSON_BYTES)
                            total += bytes.size
                            json = bytes.toString(Charsets.UTF_8)
                        }

                        name.startsWith(BackupFormat.IMAGE_PREFIX) -> {
                            val bytes = zip.readLimited(BackupFormat.MAX_ENTRY_BYTES)
                            total += bytes.size
                            File(imagesDir, safeName(name)).writeBytes(bytes)
                        }

                        name.startsWith(BackupFormat.FONT_PREFIX) -> {
                            val bytes = zip.readLimited(BackupFormat.MAX_ENTRY_BYTES)
                            total += bytes.size
                            File(fontsDir, safeName(name)).writeBytes(bytes)
                        }

                        else -> Unit
                    }
                    if (total > BackupFormat.MAX_TOTAL_BYTES) {
                        throw BackupException("备份文件过大")
                    }
                    zip.closeEntry()
                }
            }

            return Extracted(
                json = json ?: throw BackupException("备份文件缺少数据清单"),
                images = imagesDir.listFiles()?.filter { it.isFile }.orEmpty(),
                fonts = fontsDir.listFiles()?.filter { it.isFile }.orEmpty(),
            )
        }

        private fun ZipInputStream.readLimited(limit: Long): ByteArray {
            val buffer = ByteArray(BackupFormat.BUFFER_SIZE)
            val output = java.io.ByteArrayOutputStream()
            var size = 0L
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                size += read
                if (size > limit) throw BackupException("备份文件中的单个文件过大")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }

        /** 只取文件名部分，避免 zip 路径穿越写到其他目录。 */
        private fun safeName(entryName: String): String {
            val base = entryName.substringAfterLast('/')
            if (base.isBlank() || base == "." || base == ".." || base.contains('\\')) {
                throw BackupException("备份文件包含非法路径")
            }
            return base
        }

        // ---------- 解析 ----------

        private data class Parsed(
            val createdAt: Long,
            val content: DiaryBackupContent,
        )

        private fun parse(json: String): Parsed {
            val root =
                try {
                    JSONObject(json)
                } catch (_: Exception) {
                    throw BackupException("备份清单已损坏")
                }
            if (root.optString(FIELD_FORMAT) != BackupFormat.FORMAT) {
                throw BackupException("不是本应用生成的备份文件")
            }
            val version = root.optInt(FIELD_VERSION, 0)
            if (version != BackupFormat.VERSION) {
                throw BackupException("备份文件版本不受支持（v$version）")
            }
            return try {
                val content =
                    DiaryBackupContent(
                        categories = root.array("categories").mapObjects(::categoryFromJson),
                        diaries = root.array("diaries").mapObjects(::diaryFromJson),
                        tags = root.array("tags").mapObjects(::tagFromJson),
                        tagLinks = root.array("tagLinks").mapObjects(::tagLinkFromJson),
                        fonts = root.array("fonts").mapObjects(::fontFromJson),
                    )
                requireValidIds(content.categories.map { it.id }, "分类")
                requireValidIds(content.diaries.map { it.id }, "日记")
                requireValidIds(content.tags.map { it.id }, "标签")
                requireValidIds(content.fonts.map { it.id }, "字体")
                Parsed(createdAt = root.optLong(FIELD_CREATED_AT, 0L), content = content)
            } catch (e: BackupException) {
                throw e
            } catch (_: Exception) {
                throw BackupException("备份清单字段不完整")
            }
        }

        /** 主键必须为正且不重复，否则恢复会因冲突失败并留下半完成状态。 */
        private fun requireValidIds(
            ids: List<Long>,
            label: String,
        ) {
            if (ids.any { it <= 0L }) {
                throw BackupException("备份清单中的${label}编号无效")
            }
            if (ids.size != ids.toSet().size) {
                throw BackupException("备份清单中的${label}存在重复编号")
            }
        }

        private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

        private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
            (0 until length()).map { transform(getJSONObject(it)) }

        // ---------- 文件替换 ----------

        private fun replaceImages(files: List<File>) {
            imageStore.listFiles().forEach { it.delete() }
            imageStore.invalidateCache()
            files.forEach { source ->
                source.copyTo(imageStore.fileOf(source.name), overwrite = true)
            }
        }

        private fun replaceFonts(files: List<File>) {
            fontFileStore.prepareDirectory()
            fontFileStore.directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            fontCache.clear()
            files.forEach { source ->
                source.copyTo(fontFileStore.fileOf(source.name), overwrite = true)
            }
        }

        private companion object {
            const val FIELD_FORMAT = "format"
            const val FIELD_VERSION = "version"
            const val FIELD_CREATED_AT = "createdAt"
        }
    }

// ---------- JSON 映射 ----------

private fun <T> List<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray =
    JSONArray().also { array -> forEach { array.put(transform(it)) } }

private fun CategoryEntity.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("name", name)
        put("sortOrder", sortOrder)
        put("isSystem", isSystem)
        put("createdAt", createdAt)
    }

private fun categoryFromJson(obj: JSONObject): CategoryEntity =
    CategoryEntity(
        id = obj.getLong("id"),
        name = obj.getString("name"),
        sortOrder = obj.optInt("sortOrder", 0),
        isSystem = obj.optBoolean("isSystem", false),
        createdAt = obj.getLong("createdAt"),
    )

private fun DiaryEntity.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("title", title ?: JSONObject.NULL)
        put("contentJson", contentJson)
        put("plainText", plainText)
        put("categoryId", categoryId)
        put("fontId", fontId ?: JSONObject.NULL)
        put("fontSizeLevel", fontSizeLevel ?: JSONObject.NULL)
        put("charCount", charCount)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("deletedAt", deletedAt ?: JSONObject.NULL)
    }

private fun diaryFromJson(obj: JSONObject): DiaryEntity =
    DiaryEntity(
        id = obj.getLong("id"),
        title = obj.stringOrNull("title"),
        contentJson = obj.optString("contentJson"),
        plainText = obj.optString("plainText"),
        categoryId = obj.getLong("categoryId"),
        fontId = obj.longOrNull("fontId"),
        fontSizeLevel = obj.intOrNull("fontSizeLevel"),
        charCount = obj.optInt("charCount", 0),
        createdAt = obj.getLong("createdAt"),
        updatedAt = obj.getLong("updatedAt"),
        deletedAt = obj.longOrNull("deletedAt"),
    )

private fun TagEntity.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
    }

private fun tagFromJson(obj: JSONObject): TagEntity =
    TagEntity(
        id = obj.getLong("id"),
        name = obj.getString("name"),
        createdAt = obj.getLong("createdAt"),
    )

private fun DiaryTagLink.toJson(): JSONObject =
    JSONObject().apply {
        put("diaryId", diaryId)
        put("tagId", tagId)
    }

private fun tagLinkFromJson(obj: JSONObject): DiaryTagLink =
    DiaryTagLink(
        diaryId = obj.getLong("diaryId"),
        tagId = obj.getLong("tagId"),
    )

private fun FontAssetEntity.toJson(): JSONObject =
    JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("fileName", fileName)
        put("fileSize", fileSize)
        put("source", source)
        put("status", status)
        put("importedAt", importedAt)
    }

private fun fontFromJson(obj: JSONObject): FontAssetEntity =
    FontAssetEntity(
        id = obj.getLong("id"),
        displayName = obj.getString("displayName"),
        fileName = obj.getString("fileName"),
        fileSize = obj.optLong("fileSize", 0L),
        source = obj.optInt("source", 0),
        status = obj.optInt("status", 0),
        importedAt = obj.getLong("importedAt"),
    )

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)

private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
