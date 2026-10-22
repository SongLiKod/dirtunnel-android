package com.dirtunnel.core.storage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.dirtunnel.core.storage.dao.DirectoryDao
import com.dirtunnel.core.storage.dao.ForwardDao
import com.dirtunnel.core.storage.dao.FrpProfileDao
import com.dirtunnel.core.storage.dao.LogDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.dao.TemplateDao
import com.dirtunnel.core.storage.entity.ConnectionLogEntity
import com.dirtunnel.core.storage.entity.DirectoryEntity
import com.dirtunnel.core.storage.entity.FrpProfileEntity
import com.dirtunnel.core.storage.entity.ServiceInstanceEntity
import com.dirtunnel.core.storage.entity.ServiceTemplateEntity
import com.dirtunnel.core.storage.entity.TcpForwardEntity
import com.dirtunnel.core.storage.entity.TransferLogEntity

/**
 * Room：连接日志、访问日志 + 配置实体（TECH 一 · 本地持久化）。
 */
@Database(
    entities = [
        DirectoryEntity::class,
        ServiceInstanceEntity::class,
        ServiceTemplateEntity::class,
        FrpProfileEntity::class,
        TcpForwardEntity::class,
        ConnectionLogEntity::class,
        TransferLogEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun directoryDao(): DirectoryDao
    abstract fun serviceDao(): ServiceDao
    abstract fun templateDao(): TemplateDao
    abstract fun frpProfileDao(): FrpProfileDao
    abstract fun forwardDao(): ForwardDao
    abstract fun logDao(): LogDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "dirtunnel.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
