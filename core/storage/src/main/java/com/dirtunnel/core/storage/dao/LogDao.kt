package com.dirtunnel.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dirtunnel.core.storage.entity.ConnectionLogEntity
import com.dirtunnel.core.storage.entity.TransferLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    // ===== 连接日志 =====

    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeConnections(limit: Int = 500): Flow<List<ConnectionLogEntity>>

    @Query("SELECT * FROM connection_logs ORDER BY timestamp DESC")
    suspend fun getAllConnections(): List<ConnectionLogEntity>

    @Query("SELECT * FROM connection_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun observeConnectionsSince(since: Long): Flow<List<ConnectionLogEntity>>

    @Insert
    suspend fun insertConnection(log: ConnectionLogEntity): Long

    @Query("DELETE FROM connection_logs")
    suspend fun clearConnections()

    // ===== 传输日志 =====

    @Query("SELECT * FROM transfer_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeTransfers(limit: Int = 500): Flow<List<TransferLogEntity>>

    @Query("SELECT * FROM transfer_logs ORDER BY timestamp DESC")
    suspend fun getAllTransfers(): List<TransferLogEntity>

    @Query("SELECT * FROM transfer_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun observeTransfersSince(since: Long): Flow<List<TransferLogEntity>>

    @Insert
    suspend fun insertTransfer(log: TransferLogEntity): Long

    @Query("DELETE FROM transfer_logs")
    suspend fun clearTransfers()

    // ===== 一键清空（REQ 五 · 安全） =====

    @Query("DELETE FROM connection_logs")
    suspend fun clearAllConnections()

    @Query("DELETE FROM transfer_logs")
    suspend fun clearAllTransfers()

    @Query("SELECT COUNT(*) FROM connection_logs")
    fun observeConnectionCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transfer_logs")
    fun observeTransferCount(): Flow<Int>
}
