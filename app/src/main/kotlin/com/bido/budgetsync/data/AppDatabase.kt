package com.bido.budgetsync.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY createdAt DESC LIMIT 100")
    fun recent(): Flow<List<Expense>>

    /** Entries the cached workbook data does not contain yet; the offline totals add these on top of it. */
    @Query("SELECT * FROM expenses WHERE inCache = 0 ORDER BY createdAt DESC")
    fun notInCache(): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE synced = 0 ORDER BY createdAt")
    suspend fun unsynced(): List<Expense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: Expense)

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun get(id: String): Expense?

    /** Entries the cached data does not contain yet, once (the widget and alerts have no flow to watch). */
    @Query("SELECT * FROM expenses WHERE inCache = 0")
    suspend fun notInCacheNow(): List<Expense>

    /** Changes an entry that has not been sent yet. */
    @Query("UPDATE expenses SET date = :date, category = :category, description = :description, amount = :amount WHERE id = :id AND synced = 0")
    suspend fun editUnsynced(id: String, date: String, category: String, description: String, amount: Double): Int

    @Query("DELETE FROM expenses WHERE id = :id AND synced = 0")
    suspend fun deleteUnsynced(id: String)

    @Query("UPDATE expenses SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    /** Call right after a successful state fetch: everything already synced is now part of that state. */
    @Query("UPDATE expenses SET inCache = 1 WHERE synced = 1")
    suspend fun markInCache()
}

@Dao
interface CustomCategoryDao {
    @Query("SELECT * FROM custom_categories ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<CustomCategory>>

    @Query("SELECT * FROM custom_categories")
    suspend fun allNow(): List<CustomCategory>

    @Query("SELECT * FROM custom_categories WHERE synced = 0")
    suspend fun unsynced(): List<CustomCategory>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CustomCategory): Long

    @Query("UPDATE custom_categories SET synced = 1 WHERE name IN (:names)")
    suspend fun markSynced(names: List<String>)
}

@Dao
interface MonthClosingDao {
    @Query("SELECT * FROM month_closings")
    fun all(): Flow<List<MonthClosing>>

    @Query("SELECT * FROM month_closings")
    suspend fun allNow(): List<MonthClosing>

    @Query("SELECT * FROM month_closings WHERE synced = 0")
    suspend fun unsynced(): List<MonthClosing>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(closing: MonthClosing)

    @Query("UPDATE month_closings SET synced = 1 WHERE month IN (:months)")
    suspend fun markSynced(months: List<String>)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans")
    fun all(): Flow<List<PlanOverride>>

    @Query("SELECT * FROM plans")
    suspend fun allNow(): List<PlanOverride>

    @Query("SELECT * FROM plans WHERE synced = 0")
    suspend fun unsynced(): List<PlanOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: PlanOverride)

    @Query("UPDATE plans SET synced = 1 WHERE name IN (:names)")
    suspend fun markSynced(names: List<String>)
}

@Dao
abstract class PendingSmsDao {
    @Query("SELECT * FROM pending_sms WHERE status = 'PENDING' ORDER BY receivedAt DESC")
    abstract fun pending(): Flow<List<PendingSms>>

    @Query(
        "SELECT COUNT(*) FROM pending_sms WHERE sender = :sender AND bodyHash = :hash " +
            "AND ABS(receivedAt - :at) < ${PendingSms.SAME_MESSAGE_WINDOW_MS}"
    )
    abstract suspend fun countSame(sender: String, hash: Int, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(sms: PendingSms): Long

    /** Check and insert in one transaction so the receiver and an inbox scan running together cannot both add the message. */
    @Transaction
    open suspend fun insertIfNew(sms: PendingSms): Boolean {
        if (countSame(sms.sender, sms.bodyHash, sms.receivedAt) > 0) return false
        return insert(sms) != -1L
    }

    @Query("UPDATE pending_sms SET status = :status WHERE `key` = :key")
    abstract suspend fun setStatus(key: String, status: String)
}

@Database(
    entities = [Expense::class, PendingSms::class, CustomCategory::class, MonthClosing::class, PlanOverride::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun pendingSmsDao(): PendingSmsDao
    abstract fun customCategoryDao(): CustomCategoryDao
    abstract fun monthClosingDao(): MonthClosingDao
    abstract fun planDao(): PlanDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Adds the month-end decisions and in-app plans; existing data is untouched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS month_closings (month TEXT NOT NULL, reset INTEGER NOT NULL, " +
                        "invested REAL NOT NULL, splurged REAL NOT NULL, synced INTEGER NOT NULL, PRIMARY KEY(month))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS plans (name TEXT NOT NULL, planned REAL NOT NULL, synced INTEGER NOT NULL, PRIMARY KEY(name))"
                )
            }
        }

        /** Keeps queued entries: adds the new columns/table and removes duplicate pending SMS rows already saved. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN inCache INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE expenses SET inCache = synced")
                db.execSQL("ALTER TABLE pending_sms ADD COLUMN bodyHash INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS custom_categories (name TEXT NOT NULL, planned REAL NOT NULL, " +
                        "synced INTEGER NOT NULL, PRIMARY KEY(name))"
                )
                db.execSQL(
                    "DELETE FROM pending_sms WHERE EXISTS (SELECT 1 FROM pending_sms p2 WHERE p2.sender = pending_sms.sender " +
                        "AND p2.amount = pending_sms.amount AND p2.merchant = pending_sms.merchant " +
                        "AND ABS(p2.receivedAt - pending_sms.receivedAt) < ${PendingSms.SAME_MESSAGE_WINDOW_MS} " +
                        "AND p2.rowid < pending_sms.rowid)"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "budgetsync.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
