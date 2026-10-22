package com.dirtunnel.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dirtunnel.core.storage.entity.FrpProfileEntity
import com.dirtunnel.core.storage.entity.TcpForwardEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FrpProfileDao {
    @Query("SELECT * FROM frp_profiles ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FrpProfileEntity>>

    @Query("SELECT * FROM frp_profiles")
    suspend fun getAll(): List<FrpProfileEntity>

    @Query("SELECT * FROM frp_profiles WHERE id = :id")
    suspend fun getById(id: Long): FrpProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: FrpProfileEntity): Long

    @Update
    suspend fun update(entity: FrpProfileEntity)

    @Delete
    suspend fun delete(entity: FrpProfileEntity)

    @Query("DELETE FROM frp_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ForwardDao {
    @Query("SELECT * FROM tcp_forwards ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TcpForwardEntity>>

    @Query("SELECT * FROM tcp_forwards WHERE enabled = 1")
    suspend fun getEnabled(): List<TcpForwardEntity>

    @Query("SELECT * FROM tcp_forwards WHERE id = :id")
    suspend fun getById(id: Long): TcpForwardEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TcpForwardEntity): Long

    @Update
    suspend fun update(entity: TcpForwardEntity)

    @Delete
    suspend fun delete(entity: TcpForwardEntity)

    @Query("DELETE FROM tcp_forwards WHERE id = :id")
    suspend fun deleteById(id: Long)
}
