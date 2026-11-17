package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.core.discovery.partition.PartitionGroup
import ca.devdcs.backup.core.discovery.partition.PartitionSource
import ca.devdcs.backup.core.pipeline.crypto.VaultKeyStore
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import ca.devdcs.backup.domain.model.EncryptionMode
import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.PipelineOptions
import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import ca.devdcs.backup.domain.model.SlotPolicy
import ca.devdcs.backup.domain.model.TargetSelection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The six wizard pages, in order: (a) name … (f) review. The ordinal is the route's `step`. */
@Serializable
enum class WizardStep {
    NAME,
    TARGETS,
    DESTINATION,
    OPTIONS,
    SCHEDULE,
    REVIEW,
    ;

    companion object {
        /** Step for a route index; out-of-range values clamp to the nearest step. */
        fun fromIndex(index: Int): WizardStep = entries[index.coerceIn(0, entries.lastIndex)]
    }
}

/**
 * Where the AES-256-GCM key of a profile comes from. Exists because [PipelineOptions] alone cannot
 * tell "typed at every run" (no key ref) from "device vault key" (ref to a random key) or "vault-held
 * passphrase" (ref to a stored password set up outside the wizard).
 */
@Serializable
enum class KeyChoice {
    NONE,

    /** Typed for every run and never stored; unattended runs pause until the user enters it. */
    PASSPHRASE,

    /** Random 256-bit key created in this device's vault; the backup is bound to this device. */
    VAULT_KEY,

    /** A vault-held passphrase the profile already references; kept as is, never offered for new profiles. */
    STORED_SECRET,
    ;

    val encrypts: Boolean get() = this != NONE

    companion object {
        /** Classifies an existing key ref by the vault prefix [VaultKeyStore.createVaultKey] uses. */
        fun ofRef(ref: String?): KeyChoice = when {
            ref.isNullOrBlank() -> PASSPHRASE
            ref.startsWith(VaultKeyStore.REF_PREFIX + "-") -> VAULT_KEY
            else -> STORED_SECRET
        }
    }
}

/** Unit of the INTERVAL frequency editor. */
@Serializable
enum class IntervalUnit(val minutes: Int) {
    MINUTES(1),
    HOURS(60),
}

/** Defaults a brand-new profile starts from (the user's app-wide preferences). */
data class DraftDefaults(
    val archiveFormat: ArchiveFormat = ArchiveFormat.DEFAULT,
    val compressionLevel: Int = PipelineOptions.DEFAULT_COMPRESSION_LEVEL,
    val checksumAlgorithm: ChecksumAlgorithm = ChecksumAlgorithm.DEFAULT,
    val destinationId: String? = null,
)

/**
 * Everything the profile wizard has collected so far, including half-typed text (interval, retention
 * limits) and navigation state (current step, visited steps). Kept @Serializable so the ViewModel can
 * park it in the SavedStateHandle as JSON and a process death resumes the wizard exactly where it
 * was. Pure value: conversion from/to [BackupProfile] lives here and is unit-tested.
 */
@Serializable
data class ProfileDraft(
    val profileId: String,
    val isNew: Boolean,
    val createdAt: Long,
    val lastRunAt: Long? = null,
    /** The profile's enabled flag (scheduler ownership); preserved across edits. */
    val enabled: Boolean = true,
    val name: String = "",
    val description: String = "",
    val targets: TargetSelection = TargetSelection.EMPTY,
    /** Slot policy applied to every A/B partition group picked in this draft. */
    val slotPolicy: SlotPolicy = SlotPolicy.ACTIVE,
    /** Components given to newly selected apps (and to all selected apps on "apply to all"). */
    val defaultComponents: Set<AppComponent> = DEFAULT_COMPONENTS,
    val defaultComponentsTouched: Boolean = false,
    val destinationId: String? = null,
    val pipeline: PipelineOptions = PipelineOptions.DEFAULT,
    val keyChoice: KeyChoice = KeyChoice.NONE,
    /** Key ref the profile held when it was loaded; reused so backups made with it stay readable. */
    val existingKeyRef: String? = null,
    /** Set when the loaded profile used an encryption mode DFTB cannot run; cleared by any key choice. */
    val unsupportedEncryption: String? = null,
    val scheduleEnabled: Boolean = false,
    val schedule: SchedulePolicy = SchedulePolicy(),
    val intervalText: String = DEFAULT_INTERVAL_HOURS.toString(),
    val intervalUnit: IntervalUnit = IntervalUnit.HOURS,
    val keepLastEnabled: Boolean = false,
    val keepLastText: String = DEFAULT_KEEP_LAST.toString(),
    val maxAgeEnabled: Boolean = false,
    val maxAgeText: String = DEFAULT_MAX_AGE_DAYS.toString(),
    val step: WizardStep = WizardStep.NAME,
    val visited: Set<WizardStep> = setOf(WizardStep.NAME),
    /** Steps whose errors are shown (the user tried to leave them, or tried to save). */
    val showErrorsFor: Set<WizardStep> = emptySet(),
    val dirty: Boolean = false,
    /** True after "add destination" until the newly created destination is auto-selected. */
    val awaitingNewDestination: Boolean = false,
    val knownDestinationIds: Set<String> = emptySet(),
) {
    /** Partition groups selected in this draft, by [partitionGroupKey][ca.devdcs.backup.ui.discovery.partitionGroupKey]. */
    val selectedPartitionKeys: Set<String> get() = targets.partitions.mapTo(LinkedHashSet()) { it.groupKey() }

    val selectedPackages: Set<String> get() = targets.apps.mapTo(LinkedHashSet()) { it.packageName }

    /** Interval in minutes, or null when [intervalText] is not a positive whole number. */
    val intervalMinutes: Int?
        get() = intervalText.trim().toIntOrNull()?.takeIf { it > 0 }?.let { amount ->
            val total = amount.toLong() * intervalUnit.minutes
            if (total > Int.MAX_VALUE) null else total.toInt()
        }

    /** Whole number ≥ 1 typed for keep-last, or null. */
    val keepLastValue: Int? get() = keepLastText.trim().toIntOrNull()?.takeIf { it >= 1 }

    /** Whole number ≥ 1 typed for max age, or null. */
    val maxAgeValue: Int? get() = maxAgeText.trim().toIntOrNull()?.takeIf { it >= 1 }

    /** The schedule the profile would store: null when scheduling is off. */
    fun effectiveSchedule(): SchedulePolicy? {
        if (!scheduleEnabled) return null
        return if (schedule.frequency == ScheduleFrequency.INTERVAL) {
            schedule.copy(intervalMinutes = intervalMinutes)
        } else {
            schedule.copy(intervalMinutes = null)
        }
    }

    /** Retention limits; a disabled or unparsable limit means "no limit" of that kind. */
    fun effectiveRetention(): RetentionPolicy = RetentionPolicy(
        keepLast = if (keepLastEnabled) keepLastValue else null,
        maxAgeDays = if (maxAgeEnabled) maxAgeValue else null,
    )

    /**
     * Pipeline as stored: encryption mode follows [keyChoice], the level is clamped to what applies,
     * and [keyRef] is kept only for the choices that live in the vault (a typed passphrase has none).
     */
    fun effectivePipeline(keyRef: String?): PipelineOptions {
        val choice = PipelineRules.effectiveKeyChoice(keyChoice, pipeline)
        val normalized = PipelineRules.normalize(pipeline, choice)
        val range = PipelineRules.levelRange(normalized, TargetShape.of(targets))
        val level = if (range != null) normalized.compressionLevel.coerceIn(range) else normalized.compressionLevel
        return normalized.copy(
            compressionLevel = level,
            encryptionKeyRef = when (choice) {
                KeyChoice.VAULT_KEY, KeyChoice.STORED_SECRET -> keyRef
                KeyChoice.NONE, KeyChoice.PASSPHRASE -> null
            },
        )
    }

    /**
     * The profile this draft saves as. [keyRef] is the resolved vault ref for [KeyChoice.VAULT_KEY]
     * and [KeyChoice.STORED_SECRET] (null otherwise); [now] becomes `updatedAt`.
     */
    fun toProfile(now: Long, keyRef: String?): BackupProfile = BackupProfile(
        id = profileId,
        name = name.trim(),
        description = description.trim(),
        targets = targets,
        destinationId = destinationId,
        pipeline = effectivePipeline(keyRef),
        schedule = effectiveSchedule(),
        retention = effectiveRetention(),
        createdAt = createdAt,
        updatedAt = now,
        lastRunAt = lastRunAt,
        enabled = enabled,
    )

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_INTERVAL_HOURS = 6
        const val DEFAULT_KEEP_LAST = 5
        const val DEFAULT_MAX_AGE_DAYS = 30

        val DEFAULT_COMPONENTS: Set<AppComponent> = setOf(AppComponent.APK, AppComponent.INTERNAL_DATA)

        /** Components a new draft offers by default, intersected with what the current tier can capture. */
        val DEFAULT_COMPONENT_CANDIDATES: Set<AppComponent> =
            setOf(AppComponent.APK, AppComponent.INTERNAL_DATA, AppComponent.EXTERNAL_DATA, AppComponent.OBB)

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(text: String): ProfileDraft = JSON.decodeFromString(serializer(), text)

        /** A fresh draft for a new profile with id [id], seeded from [defaults]. */
        fun create(id: String, now: Long, defaults: DraftDefaults): ProfileDraft = ProfileDraft(
            profileId = id,
            isNew = true,
            createdAt = now,
            destinationId = defaults.destinationId,
            pipeline = PipelineOptions(
                archiveFormat = PipelineRules.treeFormatOf(defaults.archiveFormat, PipelineOptions.DEFAULT.compressionCodec),
                compressionLevel = defaults.compressionLevel,
                checksumAlgorithm = defaults.checksumAlgorithm,
            ),
        )

        /** Draft that edits [profile]; navigation starts at [step]. */
        fun fromProfile(profile: BackupProfile, step: WizardStep = WizardStep.NAME): ProfileDraft {
            val pipeline = profile.pipeline
            val supported = pipeline.encryptionMode == EncryptionMode.NONE || pipeline.encryptionMode == EncryptionMode.AES_256_GCM
            val keyChoice = when {
                pipeline.encryptionMode == EncryptionMode.NONE -> KeyChoice.NONE
                pipeline.encryptionMode == EncryptionMode.AES_256_GCM -> KeyChoice.ofRef(pipeline.encryptionKeyRef)
                else -> KeyChoice.NONE
            }
            val schedule = profile.schedule
            val interval = schedule?.intervalMinutes
            val (intervalText, intervalUnit) = when {
                interval == null -> DEFAULT_INTERVAL_HOURS.toString() to IntervalUnit.HOURS
                interval % IntervalUnit.HOURS.minutes == 0 -> (interval / IntervalUnit.HOURS.minutes).toString() to IntervalUnit.HOURS
                else -> interval.toString() to IntervalUnit.MINUTES
            }
            val slotted = profile.targets.partitions.firstOrNull { it.slotPolicy != SlotPolicy.NONE }?.slotPolicy
            val retention = profile.retention
            return ProfileDraft(
                profileId = profile.id,
                isNew = false,
                createdAt = profile.createdAt,
                lastRunAt = profile.lastRunAt,
                enabled = profile.enabled,
                name = profile.name,
                description = profile.description,
                targets = profile.targets,
                slotPolicy = slotted ?: SlotPolicy.ACTIVE,
                defaultComponents = profile.targets.apps.firstOrNull()?.components ?: DEFAULT_COMPONENTS,
                defaultComponentsTouched = true,
                destinationId = profile.destinationId,
                pipeline = pipeline.copy(
                    archiveFormat = PipelineRules.treeFormatOf(pipeline.archiveFormat, pipeline.compressionCodec),
                    encryptionMode = if (supported) pipeline.encryptionMode else EncryptionMode.NONE,
                    maskFilenames = supported && pipeline.maskFilenames,
                ),
                keyChoice = keyChoice,
                existingKeyRef = pipeline.encryptionKeyRef?.takeIf { it.isNotBlank() },
                unsupportedEncryption = if (supported) null else pipeline.encryptionMode.storageKey,
                scheduleEnabled = schedule != null,
                schedule = schedule ?: SchedulePolicy(),
                intervalText = intervalText,
                intervalUnit = intervalUnit,
                keepLastEnabled = retention.keepLast != null,
                keepLastText = (retention.keepLast ?: DEFAULT_KEEP_LAST).toString(),
                maxAgeEnabled = retention.maxAgeDays != null,
                maxAgeText = (retention.maxAgeDays ?: DEFAULT_MAX_AGE_DAYS).toString(),
                step = step,
                // An existing profile was complete once, so every page may be opened directly.
                visited = WizardStep.entries.toSet(),
            )
        }
    }
}

/**
 * Selection key of a stored partition target, matching
 * [partitionGroupKey][ca.devdcs.backup.ui.discovery.partitionGroupKey] of the group it came from:
 * `logical == true` targets came from mapper groups, everything else from by-name groups.
 */
fun PartitionTarget.groupKey(): String {
    val source = if (logical == true) PartitionSource.MAPPER else PartitionSource.BY_NAME
    return "${source.name}/$name"
}

/** Pure edits of the target selection used by the wizard. */
object TargetEdits {

    /** Adds [group] (as a target with [slotPolicy]) or removes it when already selected. */
    fun togglePartition(targets: TargetSelection, group: PartitionGroup, key: String, slotPolicy: SlotPolicy): TargetSelection {
        val existing = targets.partitions.filter { it.groupKey() == key }
        return if (existing.isNotEmpty()) {
            targets.copy(partitions = targets.partitions - existing.toSet())
        } else {
            targets.copy(partitions = targets.partitions + group.toTarget(slotPolicy))
        }
    }

    /** Applies [policy] to every slotted target; un-slotted ones keep [SlotPolicy.NONE]. */
    fun applySlotPolicy(targets: TargetSelection, policy: SlotPolicy): TargetSelection =
        targets.copy(partitions = targets.partitions.map { if (it.slotPolicy != SlotPolicy.NONE) it.copy(slotPolicy = policy) else it })

    fun toggleApp(targets: TargetSelection, packageName: String, components: Set<AppComponent>): TargetSelection =
        if (targets.apps.any { it.packageName == packageName }) {
            targets.copy(apps = targets.apps.filterNot { it.packageName == packageName })
        } else {
            targets.copy(apps = targets.apps + AppTarget(packageName, components))
        }

    fun setAppComponents(targets: TargetSelection, packageName: String, components: Set<AppComponent>): TargetSelection =
        targets.copy(apps = targets.apps.map { if (it.packageName == packageName) it.copy(components = components) else it })

    fun applyComponentsToAll(targets: TargetSelection, components: Set<AppComponent>): TargetSelection =
        targets.copy(apps = targets.apps.map { it.copy(components = components) })

    /** Normalized form used to compare locations: trimmed, no trailing slash (except the root). */
    fun normalizeLocation(location: String): String {
        val trimmed = location.trim()
        return if (trimmed.length > 1 && trimmed.endsWith('/')) trimmed.trimEnd('/').ifEmpty { "/" } else trimmed
    }
}
