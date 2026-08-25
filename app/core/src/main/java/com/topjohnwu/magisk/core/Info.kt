package com.topjohnwu.magisk.core

import android.app.KeyguardManager
import android.os.Build
import androidx.lifecycle.MutableLiveData
import com.topjohnwu.magisk.StubApk
import com.topjohnwu.magisk.core.ktx.getProperty
import com.topjohnwu.magisk.core.model.UpdateInfo
import com.topjohnwu.magisk.core.repository.NetworkService
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils.fastCmd
import com.topjohnwu.superuser.ShellUtils.fastCmdResult
import kotlinx.coroutines.Runnable

val isRunningAsStub get() = Info.stub != null

object Info {

    var stub: StubApk.Data? = null

    private val EMPTY_UPDATE = UpdateInfo()
    var update = EMPTY_UPDATE
        private set

    suspend fun fetchUpdate(svc: NetworkService): UpdateInfo? {
        return if (update === EMPTY_UPDATE) {
            svc.fetchUpdate()?.apply { update = this }
        } else update
    }

    fun resetUpdate() {
        update = EMPTY_UPDATE
    }

    var isRooted = false
    var noDataExec = false
    var patchBootVbmeta = false

    @JvmStatic var env = Env()
        private set
    @JvmStatic var isSAR = false
        private set
    var legacySAR = false
        private set
    var isAB = false
        private set
    var slot = ""
        private set
    var isVendorBoot = false
        private set
    // Primary: set when this process is Zygisk-injected (normal boot-patched path).
    // Fallback in init(): Quest / live Magisk often runs Zygisk in zygote without injecting
    // the manager (parasitic shell, post-boot setup, no zygote recycle).
    @JvmField var isZygiskEnabled = System.getenv("ZYGISK_ENABLED") == "1"
    @JvmStatic val isFDE get() = crypto == "block"
    @JvmStatic var ramdisk = false
        private set
    private var crypto = ""

    val isEmulator =
        Build.DEVICE.contains("vsoc")
            || getProperty("ro.kernel.qemu", "0") == "1"
            || getProperty("ro.boot.qemu", "0") == "1"

    @JvmStatic val isUnlockedBootloader =
        getProperty("ro.boot.verifiedbootstate", "") == "orange"

    val isConnected = MutableLiveData(false)

    val showSuperUser: Boolean get() {
        return env.isActive && (Const.USER_ID == 0
                || Config.suMultiuserMode == Config.Value.MULTIUSER_MODE_USER)
    }

    val isDeviceSecure get() =
        AppContext.getSystemService(KeyguardManager::class.java).isDeviceSecure

    class Env(
        val versionString: String = "",
        val isDebug: Boolean = false,
        code: Int = -1
    ) {
        val versionCode = when {
            code < Const.Version.MIN_VERCODE -> -1
            isRooted -> code
            else -> -1
        }
        val isUnsupported = code > 0 && code < Const.Version.MIN_VERCODE
        val isActive = versionCode > 0
    }

    fun init(shell: Shell) {
        if (shell.isRoot) {
            val v = fastCmd(shell, "magisk -v").split(":")
            env = Env(
                v[0], v.size >= 3 && v[2] == "D",
                runCatching { fastCmd("magisk -V").toInt() }.getOrDefault(-1)
            )
            Config.denyList = fastCmdResult(shell, "magisk --denylist status")
            if (!isZygiskEnabled) {
                isZygiskEnabled = detectRuntimeZygisk(shell)
            }
        }

        val map = mutableMapOf<String, String>()
        val list = object : CallbackList<String>(Runnable::run) {
            override fun onAddElement(e: String) {
                val split = e.split("=")
                if (split.size >= 2) {
                    map[split[0]] = split[1]
                }
            }
        }
        shell.newJob().add("(app_init)").to(list).exec()

        fun getVar(name: String) = map[name] ?: ""
        fun getBool(name: String) = map[name].toBoolean()

        isSAR = getBool("SYSTEM_AS_ROOT")
        ramdisk = getBool("RAMDISKEXIST")
        isAB = getBool("ISAB")
        patchBootVbmeta = getBool("PATCHVBMETAFLAG")
        crypto = getVar("CRYPTOTYPE")
        slot = getVar("SLOT")
        legacySAR = getBool("LEGACYSAR")
        isVendorBoot = getBool("VENDORBOOT")

        // Default presets
        Config.recovery = getBool("RECOVERYMODE")
        Config.keepVerity = getBool("KEEPVERITY")
        Config.keepEnc = getBool("KEEPFORCEENCRYPT")
    }

    private fun detectRuntimeZygisk(shell: Shell): Boolean {
        if (!fastCmdResult(shell,
                "magisk --sqlite \"SELECT value FROM settings WHERE key='zygisk' LIMIT 1\" | grep -q 'value=1'"
            )) return false

        val bridge = getProperty("ro.dalvik.vm.native.bridge", "")
        if (!bridge.startsWith("libzygisk.so")) return false

        return fastCmdResult(shell, """
            for z in zygote64 zygote; do
              pid=$(pidof ${'$'}z 2>/dev/null) || continue
              [ -n "${'$'}pid" ] || continue
              grep -q libzygisk.so /proc/${'$'}pid/maps 2>/dev/null && exit 0
            done
            exit 1
        """.trimIndent())
    }
}
