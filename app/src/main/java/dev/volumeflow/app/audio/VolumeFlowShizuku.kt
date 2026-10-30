package dev.volumeflow.app.audio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

object VolumeFlowShizuku {
    private const val PERMISSION_REQUEST = 4816
    private val _status = MutableStateFlow("Shizuku not connected")
    val status: StateFlow<String> = _status
    @Volatile private var remote: IVolumeFlowService? = null
    private var appContext: Context? = null
    private var started = false

    private val component get() = ComponentName("dev.volumeflow.app", VolumeFlowUserService::class.java.name)
    private val args get() = Shizuku.UserServiceArgs(component)
        .daemon(false).processNameSuffix("audio").debuggable(false).version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = IVolumeFlowService.Stub.asInterface(binder)
            _status.value = "Audio engine connected"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            _status.value = "Audio engine disconnected"
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        refreshPermissionState()
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        _status.value = "Shizuku stopped. Restart Shizuku to reconnect."
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) bindService()
            else _status.value = "Shizuku permission denied"
        }
    }

    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        if (started) {
            refreshPermissionState()
            return
        }
        started = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }.onFailure { _status.value = "Install and start Shizuku first" }
        refreshPermissionState()
    }

    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                _status.value = "Open Shizuku and start its service"
                return
            }
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bindService()
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST)
            }
        } catch (_: Throwable) {
            _status.value = "Shizuku is unavailable"
        }
    }

    private fun refreshPermissionState() {
        try {
            if (!Shizuku.pingBinder()) {
                _status.value = "Open Shizuku and start its service"
            } else if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bindService()
            } else {
                _status.value = "Shizuku connected. Permission required."
            }
        } catch (_: Throwable) {
            _status.value = "Install and start Shizuku first"
        }
    }

    @Synchronized
    private fun bindService() {
        if (remote != null) return
        val context = appContext ?: return
        try {
            _status.value = "Connecting audio engine…"
            Shizuku.bindUserService(args, connection)
        } catch (t: Throwable) {
            _status.value = "Could not bind audio engine: ${t.message ?: "unknown error"}"
        }
    }

    fun applyAppGain(packageName: String, gain: Float): Boolean {
        return try {
            val service = remote ?: return false
            service.applyAppGain(packageName, gain.coerceIn(0f, 1f))
            true
        } catch (_: Throwable) {
            remote = null
            _status.value = "Audio engine disconnected"
            false
        }
    }

    fun activePackages(): List<String> = runCatching { remote?.getActiveAppPackages().orEmpty() }.getOrDefault(emptyList())
    fun activeSessionCount(): Int = runCatching { remote?.getActiveSessionCount() ?: 0 }.getOrDefault(0)
    fun isReady(): Boolean = remote != null
}
