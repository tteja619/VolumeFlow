package dev.volumeflow.app.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

class VolumeFlowUserService : IVolumeFlowService.Stub() {
    private var audioManager: AudioManager? = null
    private var packageManager: android.content.pm.PackageManager? = null
    private var proxyMethod: java.lang.reflect.Method? = null
    private var uidMethod: java.lang.reflect.Method? = null
    private var uidField: java.lang.reflect.Field? = null
    private var playerStateMethod: java.lang.reflect.Method? = null
    private var setVolumeMethod: java.lang.reflect.Method? = null
    @Volatile private var engineStatus: String = "Audio service starting"
    private val gains = ConcurrentHashMap<String, Float>()
    private val uidPackages = ConcurrentHashMap<Int, String>()

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            reapplyAll()
        }
    }

    init {
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
            if (Looper.myLooper() == null) Looper.prepare()
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getDeclaredMethod("systemMain").apply { isAccessible = true }
            val thread = systemMain.invoke(null)
            val getSystemContext = activityThread.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            val systemContext = getSystemContext.invoke(thread) as Context
            audioManager = systemContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            packageManager = systemContext.packageManager

            proxyMethod = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerProxy").apply { isAccessible = true }
            uidMethod = runCatching { AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid").apply { isAccessible = true } }.getOrNull()
            uidField = runCatching { AudioPlaybackConfiguration::class.java.getDeclaredField("mClientUid").apply { isAccessible = true } }.getOrNull()
            playerStateMethod = runCatching { AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState").apply { isAccessible = true } }.getOrNull()
            audioManager?.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
            engineStatus = if (audioManager != null && proxyMethod != null) "Audio APIs initialized" else "Audio APIs partially initialized"
            Log.i(TAG, engineStatus)
        } catch (t: Throwable) {
            engineStatus = "Initialization failed: ${t.javaClass.simpleName}: ${t.message ?: "no details"}"
            Log.e(TAG, engineStatus, t)
        }
    }

    private fun clientUid(config: AudioPlaybackConfiguration): Int {
        return runCatching { (uidMethod?.invoke(config) as? Int) ?: -1 }.getOrDefault(-1)
            .takeIf { it > 0 }
            ?: runCatching { uidField?.getInt(config) ?: -1 }.getOrDefault(-1)
    }

    private fun packageForUid(uid: Int): String? {
        if (uid <= 1000) return null
        uidPackages[uid]?.let { return it }
        val name = runCatching {
            packageManager?.getPackagesForUid(uid)?.firstOrNull {
                it != "android" && it != "com.android.systemui" && it != "dev.volumeflow.app"
            }
        }.getOrNull()
        if (name != null) uidPackages[uid] = name
        return name
    }

    private fun setPlayerGain(config: AudioPlaybackConfiguration, gain: Float) {
        try {
            val proxy = proxyMethod?.invoke(config) ?: return
            val method = setVolumeMethod ?: proxy.javaClass.getDeclaredMethod(
                "setVolume", Float::class.javaPrimitiveType
            ).apply { isAccessible = true }.also { setVolumeMethod = it }
            method.invoke(proxy, gain.coerceIn(0f, 1f))
        } catch (t: Throwable) {
            Log.w(TAG, "Could not set playback gain for a player", t)
        }
    }

    override fun applyAppGain(packageName: String, gain: Float) {
        val safeGain = gain.coerceIn(0f, 1f)
        gains[packageName] = safeGain
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        configs.forEach { config ->
            if (packageForUid(clientUid(config)) == packageName) setPlayerGain(config, safeGain)
        }
    }

    private fun reapplyAll() {
        if (gains.isEmpty()) return
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        configs.forEach { config ->
            val pkg = packageForUid(clientUid(config)) ?: return@forEach
            gains[pkg]?.let { setPlayerGain(config, it) }
        }
    }

    override fun getActiveAppPackages(): List<String> {
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        return configs.mapNotNull { config ->
            val started = runCatching { playerStateMethod?.invoke(config) as? Int == 2 }.getOrDefault(false)
            if (started) packageForUid(clientUid(config)) else null
        }.distinct()
    }

    override fun getActiveSessionCount(): Int =
        runCatching { audioManager?.activePlaybackConfigurations?.size ?: 0 }.getOrDefault(0)

    override fun getEngineStatus(): String {
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }
            .getOrElse { return "$engineStatus | Playback query failed: ${it.javaClass.simpleName}: ${it.message}" }
        val sessions = configs.map { config ->
            val uid = clientUid(config)
            val pkg = packageForUid(uid) ?: "unknown"
            val state = runCatching { playerStateMethod?.invoke(config)?.toString() ?: "unavailable" }.getOrDefault("error")
            val proxy = runCatching { proxyMethod?.invoke(config) }.getOrNull()
            val proxyVolume = proxy?.let {
                runCatching {
                    it.javaClass.getDeclaredMethod("setVolume", Float::class.javaPrimitiveType).apply { isAccessible = true }
                    "available"
                }.getOrElse { "missing" }
            } ?: "unavailable"
            "$pkg(uid=$uid,state=$state,proxy=$proxyVolume)"
        }
        return "$engineStatus | sessions=${configs.size} | " +
            if (sessions.isEmpty()) "No active playback sessions detected" else sessions.joinToString("; ")
    }

    override fun destroy() {
        runCatching { audioManager?.unregisterAudioPlaybackCallback(playbackCallback) }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object { private const val TAG = "VolumeFlowService" }
}
