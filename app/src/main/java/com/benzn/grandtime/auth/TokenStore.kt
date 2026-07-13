package com.benzn.grandtime.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class PersistedSession(
    val refreshToken: String,
    val sub: String,
    val displayName: String,
    val folder: String,
    val namePrefix: String?,
)

interface TokenStore {
    fun save(session: PersistedSession)
    fun load(): PersistedSession?
    fun clear()
}

/** 测试/无 Keystore 环境用。 */
class InMemoryTokenStore : TokenStore {
    private var value: PersistedSession? = null
    override fun save(session: PersistedSession) { value = session }
    override fun load(): PersistedSession? = value
    override fun clear() { value = null }
}

/** 真机:EncryptedSharedPreferences(AES256-GCM MasterKey)。同步读写。 */
class EncryptedTokenStore(context: Context) : TokenStore {
    private val prefs = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "fs_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun save(session: PersistedSession) {
        prefs.edit()
            .putString("rt", session.refreshToken)
            .putString("sub", session.sub)
            .putString("name", session.displayName)
            .putString("folder", session.folder)
            .putString("prefix", session.namePrefix)
            .apply()
    }

    override fun load(): PersistedSession? {
        val rt = prefs.getString("rt", null) ?: return null
        return PersistedSession(
            refreshToken = rt,
            sub = prefs.getString("sub", "") ?: "",
            displayName = prefs.getString("name", "") ?: "",
            folder = prefs.getString("folder", "device") ?: "device",
            namePrefix = prefs.getString("prefix", null),
        )
    }

    override fun clear() { prefs.edit().clear().apply() }
}
