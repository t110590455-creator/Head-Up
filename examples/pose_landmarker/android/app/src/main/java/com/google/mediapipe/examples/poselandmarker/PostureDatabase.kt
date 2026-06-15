package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(
    tableName = "posture_records",
    indices = [Index(value = ["timestampMs"])],
)
data class PostureRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestampMs: Long,
    val durationMs: Long,
    val angleDegrees: Int,
    val rawAngleDegrees: Float,
    val neckFlexionDegrees: Int,
    val shoulderBalanceDegrees: Int,
    val screenDistanceCm: Int?,
    val landmarkConfidence: Float,
    val zone: String,
    val source: String,
)

@Dao
interface PostureRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: PostureRecordEntity)

    @Query("SELECT * FROM posture_records WHERE timestampMs >= :fromMs AND timestampMs < :toMs ORDER BY timestampMs ASC")
    fun recordsBetween(fromMs: Long, toMs: Long): List<PostureRecordEntity>

    @Query("DELETE FROM posture_records")
    fun deleteAll()

    @Query("DELETE FROM posture_records WHERE timestampMs < :cutoffMs")
    fun deleteOlderThan(cutoffMs: Long)
}

@Database(
    entities = [PostureRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PostureDatabase : RoomDatabase() {
    abstract fun postureRecordDao(): PostureRecordDao

    companion object {
        @Volatile
        private var instance: PostureDatabase? = null

        fun getInstance(context: Context): PostureDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PostureDatabase::class.java,
                "headup-posture.db",
            ).build().also { instance = it }
        }
    }
}
