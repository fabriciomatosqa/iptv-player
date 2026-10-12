package br.com.fabriciomatos.iptvplayer

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.util.Base64
import androidx.annotation.StringRes
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AppLanguage(val tag: String, val label: String)
val appLanguages = listOf(
    AppLanguage("pt-BR", "Português (Brasil)"), AppLanguage("en", "English"), AppLanguage("es", "Español"),
    AppLanguage("fr", "Français"), AppLanguage("de", "Deutsch"), AppLanguage("it", "Italiano"),
    AppLanguage("nl", "Nederlands"), AppLanguage("pl", "Polski"), AppLanguage("ru", "Русский"),
    AppLanguage("uk", "Українська"), AppLanguage("ar", "العربية"), AppLanguage("hi", "हिन्दी"),
    AppLanguage("bn", "বাংলা"), AppLanguage("zh-CN", "简体中文"), AppLanguage("zh-TW", "繁體中文"),
    AppLanguage("ja", "日本語"), AppLanguage("ko", "한국어"), AppLanguage("tr", "Türkçe"),
    AppLanguage("vi", "Tiếng Việt"), AppLanguage("id", "Bahasa Indonesia")
)

class AppSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aurora_settings", Context.MODE_PRIVATE)
    fun language() = prefs.getString("language", "pt-BR").orEmpty()
    fun saveLanguage(tag: String): Boolean = tag in appLanguages.map { it.tag } && prefs.edit().putString("language", tag).commit()
    fun epgUrl() = prefs.getString("epg_url", "").orEmpty()
    fun saveEpgUrl(value: String) = prefs.edit().putString("epg_url", value).commit()
    fun text(@StringRes id: Int, vararg args: Any): String {
        val config = Configuration(app.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(language()))
        return app.createConfigurationContext(config).getString(id, *args)
    }
    fun hasPassword() = prefs.contains("parental_hash")
    fun blockedUntil() = prefs.getLong("parental_blocked_until", 0)
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 150000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    @Synchronized fun verifyPassword(password: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!hasPassword() || blockedUntil() > now) return false
        val salt = Base64.decode(prefs.getString("parental_salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("parental_hash", ""), Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(expected, derive(password, salt))
        val failures = if (ok) 0 else prefs.getInt("parental_failures", 0) + 1
        prefs.edit().putInt("parental_failures", if (failures >= 5) 0 else failures)
            .putLong("parental_blocked_until", if (failures >= 5) now + 30000L else 0L).commit()
        return ok
    }
    @Synchronized fun setPassword(current: String, password: String): Boolean {
        if (password.length !in 4..64 || (hasPassword() && !verifyPassword(current))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return prefs.edit().putString("parental_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("parental_hash", Base64.encodeToString(derive(password, salt), Base64.NO_WRAP))
            .putInt("parental_failures", 0).putLong("parental_blocked_until", 0L).commit()
    }
}
