package ca.devdcs.backup.data.db.converters

import androidx.room.TypeConverter
import ca.devdcs.backup.domain.model.CheckpointState
import ca.devdcs.backup.domain.model.DestinationConfig
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.model.SchedulePolicy
import ca.devdcs.backup.domain.model.TargetSelection

/**
 * Room converters for the nested value objects stored as JSON text columns. Enums are *not*
 * converted here: entities keep them as `storageKey` strings and the mappers translate, which
 * keeps the SQL readable (`WHERE status = 'running'`) and avoids nullable/non-null converter
 * ambiguity. Must have a public no-arg constructor for Room to instantiate it.
 */
class DftbTypeConverters {

    private val json get() = DbJson.instance

    @TypeConverter
    fun targetSelectionToJson(value: TargetSelection): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToTargetSelection(value: String): TargetSelection = json.decodeFromString(value)

    @TypeConverter
    fun pipelineOptionsToJson(value: PipelineOptions): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToPipelineOptions(value: String): PipelineOptions = json.decodeFromString(value)

    @TypeConverter
    fun schedulePolicyToJson(value: SchedulePolicy?): String? = value?.let { json.encodeToString(it) }

    @TypeConverter
    fun jsonToSchedulePolicy(value: String?): SchedulePolicy? = value?.let { json.decodeFromString(it) }

    @TypeConverter
    fun retentionPolicyToJson(value: RetentionPolicy): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToRetentionPolicy(value: String): RetentionPolicy = json.decodeFromString(value)

    @TypeConverter
    fun destinationConfigToJson(value: DestinationConfig): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToDestinationConfig(value: String): DestinationConfig = json.decodeFromString(value)

    @TypeConverter
    fun checkpointStateToJson(value: CheckpointState): String = json.encodeToString(value)

    @TypeConverter
    fun jsonToCheckpointState(value: String): CheckpointState = json.decodeFromString(value)
}
