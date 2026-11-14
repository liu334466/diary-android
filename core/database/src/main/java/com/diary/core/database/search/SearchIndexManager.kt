package com.diary.core.database.search

import androidx.room.withTransaction
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.dao.DiaryDao
import com.diary.core.database.dao.DiaryFtsDao
import com.diary.core.database.entity.DiaryFtsEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全文索引的全量补建。
 *
 * 索引行数与应用内日记总数不一致时（升级到 v2、异常中断、外部写库）重建一次；
 * 正常写入由 [com.diary.core.database.repository.DiaryRepository] 增量维护，不走这里。
 */
@Singleton
class SearchIndexManager
    @Inject
    constructor(
        private val diaryDao: DiaryDao,
        private val diaryFtsDao: DiaryFtsDao,
        private val database: DiaryDatabase,
    ) {
        private val mutex = Mutex()

        @Volatile
        private var checked = false

        suspend fun ensureIndexed() {
            if (checked) return
            mutex.withLock {
                if (checked) return
                val expected = diaryDao.countAll()
                if (diaryFtsDao.count() != expected) {
                    database.withTransaction {
                        diaryFtsDao.clear()
                        val rows = diaryFtsDao.indexRows()
                        if (rows.isNotEmpty()) {
                            diaryFtsDao.upsertAll(
                                rows.map { row ->
                                    DiaryFtsEntity(
                                        rowId = row.id,
                                        titleTokens = SearchTokenizer.indexOf(row.title),
                                        bodyTokens = SearchTokenizer.indexOf(row.plainText),
                                    )
                                },
                            )
                        }
                    }
                }
                checked = true
            }
        }
    }
