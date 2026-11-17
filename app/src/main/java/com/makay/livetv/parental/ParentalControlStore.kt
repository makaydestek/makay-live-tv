package com.makay.livetv.parental

import android.content.Context
import com.makay.livetv.domain.model.Channel
import com.makay.livetv.domain.model.Program
import java.security.MessageDigest

/** Uygulama ici PIN ve yerel kanal kilitleri. TV saglayicisindaki kilitlere dokunmaz. */
class ParentalControlStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("livetv_parental", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()
    val maxAllowedAge: Int? get() = prefs.getInt(KEY_MAX_AGE, AGE_LIMIT_OFF).takeIf { it != AGE_LIMIT_OFF }

    /** Menu kilidi acikken Ayarlar/Kanal listesi/Zamanlayicilar PIN ister. */
    val menuLockEnabled: Boolean get() = prefs.getBoolean(KEY_MENU_LOCK, false)

    fun setPin(pin: String): Boolean {
        if (pin.length !in 4..8 || pin.any { !it.isDigit() }) return false
        prefs.edit().putString(KEY_PIN_HASH, hash(pin)).apply()
        return true
    }

    fun verify(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val supplied = hash(pin)
        return MessageDigest.isEqual(stored.toByteArray(), supplied.toByteArray())
    }

    fun clearPin(pin: String): Boolean {
        if (!verify(pin)) return false
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_LOCKED)
            .remove(KEY_MENU_LOCK)
            .putInt(KEY_MAX_AGE, AGE_LIMIT_OFF)
            .apply()
        return true
    }

    /** Menu kilidini acar/kapar; PIN yokken acilamaz. */
    fun setMenuLock(enabled: Boolean): Boolean {
        if (enabled && !hasPin) return false
        prefs.edit().putBoolean(KEY_MENU_LOCK, enabled).apply()
        return true
    }

    fun isLocked(channel: Channel): Boolean = favoriteKey(channel) in prefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty()

    fun setLocked(channel: Channel, locked: Boolean): Boolean {
        if (!hasPin) return false
        val key = favoriteKey(channel)
        val values = prefs.getStringSet(KEY_LOCKED, emptySet()).orEmpty().toMutableSet()
        if (locked) values.add(key) else values.remove(key)
        prefs.edit().putStringSet(KEY_LOCKED, values).apply()
        return true
    }

    fun setMaxAllowedAge(age: Int?): Boolean {
        if (age != null && (!hasPin || age !in SUPPORTED_AGE_LIMITS)) return false
        prefs.edit().putInt(KEY_MAX_AGE, age ?: AGE_LIMIT_OFF).apply()
        return true
    }

    fun requiresPin(program: Program): Boolean {
        val limit = maxAllowedAge ?: return false
        val ratingAge = ageFromRating(program.rating) ?: return false
        return ratingAge > limit
    }

    fun applyTo(channels: List<Channel>): List<Channel> =
        channels.map { it.copy(isLocallyLocked = isLocked(it)) }

    private fun hash(pin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(("MakayLiveTV:$pin").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun ageFromRating(raw: String?): Int? {
        val value = raw?.uppercase().orEmpty()
        if (value.isBlank()) return null
        when {
            "TV-Y7" in value -> return 7
            "TV-Y" in value || "TV-G" in value || value == "G" || value.endsWith("/G") -> return 0
            "TV-PG" in value || value == "PG" || value.endsWith("/PG") -> return 10
            "TV-14" in value || "PG-13" in value -> return 14
            "TV-MA" in value || "NC-17" in value || "\\bR\\b".toRegex().containsMatchIn(value) -> return 18
        }
        return "(?:^|[^0-9])(\\d{1,2})\\s*\\+?(?:$|[^0-9])".toRegex()
            .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_LOCKED = "locked_channels"
        private const val KEY_MAX_AGE = "max_allowed_age"
        private const val KEY_MENU_LOCK = "menu_lock"
        private const val AGE_LIMIT_OFF = -1
        val SUPPORTED_AGE_LIMITS = setOf(7, 10, 12, 14, 16, 18)
        fun favoriteKey(channel: Channel): String = "${channel.inputId}|${channel.displayNumber}"
    }
}
