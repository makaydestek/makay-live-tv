package com.makay.livetv.domain.model

/**
 * `TvContract.Programs` kaydindan turetilen EPG programi.
 *
 * @property startTimeUtcMillis EPG'nin UTC milisaniye baslangici.
 * @property endTimeUtcMillis EPG'nin UTC milisaniye bitisi.
 * @property durationMillis hesaplanmis sure (0 veya negatifse bilinmiyor).
 */
data class Program(
    val id: Long,
    val channelId: Long,
    val title: String,
    val shortDescription: String? = null,
    val longDescription: String? = null,
    val startTimeUtcMillis: Long,
    val endTimeUtcMillis: Long,
    val episodeTitle: String? = null,
    val seasonNumber: String? = null,
    val episodeNumber: String? = null,
    val genre: String? = null,
    val language: String? = null,
    val rating: String? = null,
    val posterUri: String? = null,
    val isRecording: Boolean = false,
) {
    val durationMillis: Long
        get() = (endTimeUtcMillis - startTimeUtcMillis).coerceAtLeast(0L)

    val hasDescription: Boolean
        get() = !shortDescription.isNullOrBlank() || !longDescription.isNullOrBlank()

    /** Programin toplam suresi icinde `now` anindaki ilerleme (0f..1f). */
    fun progressAt(now: Long): Float {
        if (durationMillis <= 0L) return 0f
        val elapsed = (now - startTimeUtcMillis).toFloat()
        return (elapsed / durationMillis.toFloat()).coerceIn(0f, 1f)
    }

    fun isLiveAt(now: Long): Boolean = now in startTimeUtcMillis until endTimeUtcMillis

    fun isFinishedBy(now: Long): Boolean = now >= endTimeUtcMillis

    fun isUpcomingAt(now: Long): Boolean = now < startTimeUtcMillis

    /** Programin gosterim etiketi: bölüm bilgisi varsa birlesik metin. */
    val subtitle: String?
        get() = episodeTitle?.takeIf { it.isNotBlank() }
}
