package com.dirtunnel.core.storage.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 本地加密存储（REQ 4.4 配置文件本地加密存储 / TECH 六.1）：
 * 账号密码、frp token、DDNS token 全部经 EncryptedSharedPreferences(AES256-GCM) 加密。
 * 少数机型 KeyStore 异常时降级为应用私有普通文件并标记 [degraded]，UI 会给出风险提示。
 */
@Singleton
class SecurityStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    /** 是否降级（降级后 UI 提示“设备不支持硬件级加密存储”） */
    var degraded: Boolean = false
        private set

    private val prefs: SharedPreferences = createPrefs(appContext)

    private fun createPrefs(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        degraded = true
        Log.w(TAG, "EncryptedSharedPreferences 初始化失败，降级为普通私有存储", t)
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    // ===== 通用 =====

    fun getSecret(key: String): String? = try {
        prefs.getString(key, null)
    } catch (t: Throwable) {
        Log.w(TAG, "读取加密项失败: $key", t)
        null
    }

    fun setSecret(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "写入加密项失败: $key", t)
        }
    }

    fun removeSecret(key: String) {
        try {
            prefs.edit().remove(key).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "删除加密项失败: $key", t)
        }
    }

    // ===== 语义化 key =====

    fun servicePassword(serviceId: Long): String? = getSecret("service_password_$serviceId")

    fun setServicePassword(serviceId: Long, password: String?) {
        if (password.isNullOrEmpty()) removeSecret("service_password_$serviceId")
        else setSecret("service_password_$serviceId", password)
    }

    /** 自建 FRP 档案 token；profileId = 0 表示公共隧道。 */
    fun frpToken(profileId: Long): String? = getSecret("frp_token_$profileId")

    fun setFrpToken(profileId: Long, token: String?) {
        if (token.isNullOrEmpty()) removeSecret("frp_token_$profileId")
        else setSecret("frp_token_$profileId", token)
    }

    fun ddnsToken(): String? = getSecret("ddns_token")

    fun setDdnsToken(token: String?) {
        if (token.isNullOrEmpty()) removeSecret("ddns_token")
        else setSecret("ddns_token", token)
    }

    /** SFTP 用户密码（与服务密码分开存放，便于未来多账号扩展）。 */
    fun sftpPassword(serviceId: Long): String? = getSecret("sftp_password_$serviceId")

    fun setSftpPassword(serviceId: Long, password: String?) {
        if (password.isNullOrEmpty()) removeSecret("sftp_password_$serviceId")
        else setSecret("sftp_password_$serviceId", password)
    }

    companion object {
        private const val TAG = "SecurityStore"
        private const val SECURE_FILE = "dirtunnel_secure"
        private const val FALLBACK_FILE = "dirtunnel_secure_fallback"
    }
}
