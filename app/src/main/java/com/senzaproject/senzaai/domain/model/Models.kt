package com.senzaproject.senzaai.domain.model

enum class AccessMode(val label: String) {
    NORMAL("Normal"),
    ADB_SHIZUKU("ADB / Shizuku"),
    ROOT("Root")
}

enum class Certainty(val label: String) {
    PROVEN("Terbukti"),
    PROBABLE("Kemungkinan"),
    UNKNOWN("Belum dapat dipastikan")
}

enum class Severity { INFO, WARN, ERROR }

data class InfoRow(val label: String, val value: String)

data class InfoSection(val title: String, val rows: List<InfoRow>)

data class MemorySnapshot(val totalBytes: Long, val availBytes: Long) {
    val usedBytes: Long get() = (totalBytes - availBytes).coerceAtLeast(0)
    val percent: Float get() = if (totalBytes > 0) usedBytes * 100f / totalBytes else 0f
}

data class StorageSnapshot(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val percent: Float get() = if (totalBytes > 0) usedBytes * 100f / totalBytes else 0f
}

data class BatterySnapshot(
    val percent: Int,
    val tempC: Float?,
    val voltageMv: Int?,
    val status: String,
    val plugged: String,
    val technology: String?,
    val health: String,
    val currentMa: Int?,
    val powerW: Float?,
    val cycleCount: Int?,
    val capacityMah: Int?,
    val capacityNote: String?,
    val isCharging: Boolean
)

data class CpuCore(
    val index: Int,
    val online: Boolean,
    val curKhz: Long?,
    val minKhz: Long?,
    val maxKhz: Long?,
    val governor: String?
)

data class CpuSnapshot(
    val usagePercent: Float?,
    val usageIsEstimate: Boolean,
    val cores: List<CpuCore>,
    val tempC: Float?
)

data class RootEnvironment(
    val managers: List<String>,
    val suPath: String?,
    val verified: Boolean,
    val details: List<String>
)

data class AccessState(
    val active: AccessMode,
    val root: RootEnvironment,
    val shizukuInstalled: Boolean,
    val capabilities: List<String>,
    val limits: List<String>
)

data class Finding(
    val title: String,
    val evidence: String,
    val certainty: Certainty,
    val severity: Severity,
    val recommendation: String
)

data class DiagnosticReport(
    val time: Long,
    val findings: List<Finding>
)
