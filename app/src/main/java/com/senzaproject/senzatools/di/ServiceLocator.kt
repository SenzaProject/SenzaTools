package com.senzaproject.senzatools.di

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.senzaproject.senzatools.ai.SmartDiagnostics
import com.senzaproject.senzatools.data.DeviceRepository
import com.senzaproject.senzatools.data.OperationLog
import com.senzaproject.senzatools.data.SessionContext
import com.senzaproject.senzatools.data.UiPrefs
import com.senzaproject.senzatools.monitor.HistoryStore
import com.senzaproject.senzatools.monitor.PerformanceMonitor
import com.senzaproject.senzatools.platform.AccessManager

/**
 * Dependency container manual. Dipilih agar build tetap sederhana dan stabil tanpa kapt/ksp;
 * antarmuka kelas sudah dipisah sehingga dapat dipindah ke Hilt tanpa mengubah layer domain.
 */
class ServiceLocator(val app: Application) {
    val operationLog by lazy { OperationLog(app) }
    val device by lazy { DeviceRepository(app) }
    val access by lazy { AccessManager(app, operationLog) }
    val batteryHealth by lazy { com.senzaproject.senzatools.data.BatteryHealthReader(app, access) }
    val editor by lazy { com.senzaproject.senzatools.platform.root.RootEditor(app, access, operationLog) }
    val perf by lazy { com.senzaproject.senzatools.platform.root.PerfTuner(app, access) }
    val updates by lazy { com.senzaproject.senzatools.update.UpdateChecker(app) }
    val display by lazy { com.senzaproject.senzatools.platform.root.DisplayTuner(app, access) }
    val booster by lazy { com.senzaproject.senzatools.platform.root.Booster(app, access) }
    val zram by lazy { com.senzaproject.senzatools.platform.root.ZramTuner(app, access) }
    val diagnostics by lazy { SmartDiagnostics(app, device) }
    val monitor by lazy { PerformanceMonitor(device, access) }
    val history by lazy { HistoryStore(app) }
    val fakeDevice by lazy { com.senzaproject.senzatools.platform.root.FakeDeviceEngine(app, access, editor) }
    val kernel by lazy { com.senzaproject.senzatools.kernel.KernelEngine(app, access, com.senzaproject.senzatools.kernel.KernelStore(app)) }
    val uiPrefs by lazy { UiPrefs(app) }
    val session = SessionContext()
}

val LocalServices = staticCompositionLocalOf<ServiceLocator> { error("ServiceLocator belum disediakan") }
