package com.dirtunnel.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dirtunnel.core.storage.entity.DirectoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DirectoryDao {
    @Query("SELECT * FROM directories ORDER BY favorite DESC, lastUsedAt DESC")
    fun observeAll(): Flow<List<DirectoryEntity>>

    @Query("SELECT * FROM directories ORDER BY favorite DESC, lastUsedAt DESC")
    suspend fun getAll(): List<DirectoryEntity>

    @Query("SELECT * FROM directories WHERE id = :id")
    suspend fun getById(id: Long): DirectoryEntity?

    @Query("SELECT * FROM directories WHERE id = :id")
    fun observeById(id: Long): Flow<DirectoryEntity?>

    @Query("SELECT * FROM directories WHERE treeUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DirectoryEntity?

    @Query("SELECT * FROM directories WHERE favorite = 1 ORDER BY lastUsedAt DESC")
    fun observeFavorites(): Flow<List<DirectoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DirectoryEntity): Long

    @Update
    suspend fun update(entity: DirectoryEntity)

    @Delete
    suspend fun delete(entity: DirectoryEntity)

    @Query("DELETE FROM directories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE directories SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE directories SET lastUsedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())
}
