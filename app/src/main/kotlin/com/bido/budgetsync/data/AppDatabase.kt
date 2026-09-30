package com.bido.budgetsync.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY createdAt DESC LIMIT 100")
    fun recent(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE synced = 0 ORDER BY createdAt")
    suspend fun unsynced(): List<Expense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: Expense)

    @Query("DELETE FROM expenses WHERE id = :id AND synced = 0")
    suspend fun deleteUnsynced(id: String)

    @Query("UPDATE expenses SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

@Dao
interface PendingSmsDao {
    @Query("SELECT * FROM pending_sms WHERE status = 'PENDING' ORDER BY receivedAt DESC")
    fun pending(): Flow<List<PendingSms>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sms: PendingSms): Long

    @Query("UPDATE pending_sms SET status = :status WHERE `key` = :key")
    suspend fun setStatus(key: String, status: String)
}

@Database(entities = [Expense::class, PendingSms::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun pendingSmsDao(): PendingSmsDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "budgetsync.db")
                .build().also { instance = it }
        }
    }
}
