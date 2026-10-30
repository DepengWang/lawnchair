package app.lawnchair.smartspace

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

internal val clockHealthPermissions = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(DistanceRecord::class),
)

internal data class HealthMetrics(
    val steps: Long? = null,
    val sleepMinutes: Long? = null,
    val heartRateBpm: Long? = null,
    val oxygenSaturationPercent: Int? = null,
    val hasRunningSession: Boolean = false,
    val runningDistanceMeters: Double? = null,
)

internal object HealthMetricsReader {

    suspend fun read(context: Context): HealthMetrics {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return HealthMetrics()
        }

        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val dayAgo = now.minus(Duration.ofHours(24))

            val steps = if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                safely {
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(todayStart, now),
                        ),
                    )[StepsRecord.COUNT_TOTAL]
                }
            } else null

            val sleepSession = if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
                safely {
                    client.readRecords(
                        ReadRecordsRequest<SleepSessionRecord>(
                            timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofHours(36)), now),
                        ),
                    ).records.maxByOrNull { it.endTime }
                }
            } else null
            val sleepMinutes = sleepSession?.let {
                Duration.between(it.startTime, it.endTime).toMinutes().takeIf { minutes -> minutes > 0 }
            }

            val heartRate = if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
                safely {
                    client.readRecords(
                        ReadRecordsRequest<HeartRateRecord>(
                            timeRangeFilter = TimeRangeFilter.between(dayAgo, now),
                        ),
                    ).records.asSequence()
                        .flatMap { it.samples.asSequence() }
                        .filter { it.time >= dayAgo && it.time <= now }
                        .maxByOrNull { it.time }
                        ?.beatsPerMinute
                }
            } else null

            val oxygenSaturation = if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted) {
                safely {
                    client.readRecords(
                        ReadRecordsRequest<OxygenSaturationRecord>(
                            timeRangeFilter = TimeRangeFilter.between(dayAgo, now),
                        ),
                    ).records.maxByOrNull { it.time }
                        ?.percentage?.value?.roundToInt()
                }
            } else null

            val runningSessions = if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                safely {
                    client.readRecords(
                        ReadRecordsRequest<ExerciseSessionRecord>(
                            timeRangeFilter = TimeRangeFilter.between(todayStart, now),
                        ),
                    ).records.filter {
                        it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING ||
                            it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
                    }
                }.orEmpty()
            } else emptyList()

            val runningDistance = if (
                runningSessions.isNotEmpty() &&
                HealthPermission.getReadPermission(DistanceRecord::class) in granted
            ) {
                safely {
                    val distances = client.readRecords(
                        ReadRecordsRequest<DistanceRecord>(
                            timeRangeFilter = TimeRangeFilter.between(todayStart, now),
                        ),
                    ).records
                    runningSessions.mapNotNull { session ->
                        val matchingDistances = distances.filter { distance ->
                            distance.metadata.dataOrigin.packageName == session.metadata.dataOrigin.packageName &&
                                distance.startTime < session.endTime && distance.endTime > session.startTime
                        }
                        selectSessionDistance(session, matchingDistances)
                    }.takeIf { it.isNotEmpty() }?.sum()
                }
            } else null

            HealthMetrics(
                steps = steps,
                sleepMinutes = sleepMinutes,
                heartRateBpm = heartRate,
                oxygenSaturationPercent = oxygenSaturation,
                hasRunningSession = runningSessions.isNotEmpty(),
                runningDistanceMeters = runningDistance,
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            HealthMetrics()
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private fun selectSessionDistance(
        session: ExerciseSessionRecord,
        distances: List<DistanceRecord>,
    ): Double? {
        if (distances.isEmpty()) return null

        val completeSessionDistance = distances
            .filter { distance ->
                distance.startTime <= session.startTime &&
                    distance.endTime >= session.endTime
            }
            .minByOrNull { distance ->
                Duration.between(distance.startTime, distance.endTime).toMillis()
            }
        if (completeSessionDistance != null) {
            return completeSessionDistance.distance.inMeters
        }

        val nonContainedDistances = distances.filterNot { candidate ->
            distances.any { container ->
                container !== candidate &&
                    container.startTime <= candidate.startTime &&
                    container.endTime >= candidate.endTime &&
                    (container.startTime < candidate.startTime || container.endTime > candidate.endTime)
            }
        }
        return nonContainedDistances.takeIf { it.isNotEmpty() }?.sumOf { it.distance.inMeters }
    }
}
