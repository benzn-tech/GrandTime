package com.benzn.grandtime.device

import android.content.Context
import android.provider.Settings
import java.io.File
import java.util.UUID

/**
 * Who this physical device is, for the device ledger.
 *
 * `assetTag` — the FS-xx label stuck on the case — is the AUTHORITATIVE identity.
 * `deviceUuid` is advisory only: these units were likely flashed from one factory
 * image, so ANDROID_ID may be identical across the whole fleet, and this ROM
 * already misreports SENSOR_ORIENTATION. The backend distrusts a uuid the moment
 * it sees one under two different tags; the tag stays correct either way.
 *
 * Both values are written to TWO places. SharedPreferences alone loses everything
 * to "Clear data", which on a device that rotates between clients is an ordinary
 * thing for someone to do. The sidecar file under getExternalFilesDir() survives
 * that; only uninstall or factory reset loses both, and that is when a human
 * re-types the label from the case.
 */

const val KEY_ASSET_TAG = "device_asset_tag"
const val KEY_DEVICE_UUID = "device_uuid"

/** Injectable persistence, so the rules above are unit-testable without Android stubs. */
interface IdentityStore {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

/** ANDROID_ID values known not to identify anything. */
private val BAD_ANDROID_IDS = setOf(
    "9774d56d682e549c",   // long-standing emulator / buggy-ROM constant
)

/**
 * A stored id always wins, so the identity survives an OS upgrade that rotates
 * ANDROID_ID. Otherwise take ANDROID_ID when it is plausible, else mint one.
 */
fun resolveUuid(androidId: String?, stored: String?, mint: () -> String): String {
    stored?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val candidate = androidId?.trim()?.lowercase()
    val usable = !candidate.isNullOrEmpty() &&
        candidate.any { it != '0' } &&
        candidate !in BAD_ANDROID_IDS
    return if (usable) candidate!! else mint()
}

fun readAssetTag(store: IdentityStore): String? =
    store.read(KEY_ASSET_TAG)?.trim()?.takeIf { it.isNotEmpty() }

fun writeAssetTag(store: IdentityStore, raw: String) {
    val tag = raw.trim().uppercase()
    if (tag.isEmpty()) return
    store.write(KEY_ASSET_TAG, tag)
}

fun shortCodeOf(uuid: String): String = uuid.take(6)

/**
 * SharedPreferences plus a sidecar file. Not unit-tested — verified on hardware,
 * the same convention [com.benzn.grandtime.net.RealHttp] follows.
 */
class AndroidIdentityStore(context: Context) : IdentityStore {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    private fun sidecar(key: String) = File(app.getExternalFilesDir(null), "$key.txt")

    override fun read(key: String): String? =
        prefs.getString(key, null)
            ?: runCatching { sidecar(key).takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        runCatching { sidecar(key).writeText(value) }
    }
}

object DeviceIdentity {
    @Volatile private var appContext: Context? = null
    @Volatile private var store: IdentityStore? = null
    @Volatile private var cachedUuid: String? = null

    /** Call once on cold start. Idempotent. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (store == null) store = AndroidIdentityStore(app)
    }

    fun assetTag(): String? = store?.let { readAssetTag(it) }

    fun setAssetTag(tag: String) {
        store?.let { writeAssetTag(it, tag) }
    }

    /**
     * Empty until [init] has run. Callers treat "" as "no identity yet" and omit
     * the headers entirely rather than sending blanks — a device that has not
     * initialised reports nothing, and the ledger reads that as never-seen,
     * which is true.
     */
    fun deviceUuid(): String {
        cachedUuid?.let { return it }
        val s = store ?: return ""
        val ctx = appContext ?: return ""
        @Suppress("HardwareIds")
        val androidId = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        val resolved = resolveUuid(androidId, s.read(KEY_DEVICE_UUID)) { UUID.randomUUID().toString() }
        s.write(KEY_DEVICE_UUID, resolved)
        cachedUuid = resolved
        return resolved
    }

    fun shortCode(): String = shortCodeOf(deviceUuid())
}
