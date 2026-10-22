package com.dirtunnel.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dirtunnel.core.storage.entity.ServiceInstanceEntity
import com.dirtunnel.core.storage.entity.ServiceTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServiceDao {
    @Query("SELECT * FROM service_instances ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ServiceInstanceEntity>>

    @Query("SELECT * FROM service_instances WHERE directoryId = :directoryId ORDER BY createdAt DESC")
    fun observeForDirectory(directoryId: Long): Flow<List<ServiceInstanceEntity>>

    @Query("SELECT * FROM service_instances")
    suspend fun getAll(): List<ServiceInstanceEntity>

    @Query("SELECT * FROM service_instances WHERE id = :id")
    suspend fun getById(id: Long): ServiceInstanceEntity?

    @Query("SELECT * FROM service_instances WHERE autoStart = 1")
    suspend fun getAutoStart(): List<ServiceInstanceEntity>

    @Query("SELECT * FROM service_instances WHERE enableTunnel = 1")
    suspend fun getTunneled(): List<ServiceInstanceEntity>

    @Query("SELECT COUNT(*) FROM service_instances WHERE port = :port AND id != :excludeId")
    suspend fun countByPort(port: Int, excludeId: Long = 0): Int

    @Query("SELECT * FROM service_instances WHERE port = :port AND id != :excludeId LIMIT 1")
    suspend fun getByPort(port: Int, excludeId: Long = 0): ServiceInstanceEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ServiceInstanceEntity): Long

    @Update
    suspend fun update(entity: ServiceInstanceEntity)

    @Delete
    suspend fun delete(entity: ServiceInstanceEntity)

    @Query("DELETE FROM service_instances WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM service_templates ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ServiceTemplateEntity>>

    @Query("SELECT * FROM service_templates")
    suspend fun getAll(): List<ServiceTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ServiceTemplateEntity): Long

    @Delete
    suspend fun delete(entity: ServiceTemplateEntity)

    @Query("DELETE FROM service_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
