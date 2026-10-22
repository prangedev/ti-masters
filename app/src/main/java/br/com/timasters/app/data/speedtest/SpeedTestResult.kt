package br.com.timasters.app.data.speedtest

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "speed_tests")
data class SpeedTestResult(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val measuredAt: Long,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val pingMs: Double?,
    val jitterMs: Double?,
    /** [br.com.timasters.app.data.network.TransportType] name. */
    val connectionType: String,
    /** Wi-Fi name when known. */
    val networkName: String?,
    val isp: String?,
    val serverLocation: String?
)

@Dao
interface SpeedTestDao {

    @Query("SELECT * FROM speed_tests ORDER BY measuredAt DESC")
    fun observeAll(): Flow<List<SpeedTestResult>>

    @Insert
    suspend fun insert(result: SpeedTestResult): Long

    @Query("DELETE FROM speed_tests")
    suspend fun deleteAll()
}
