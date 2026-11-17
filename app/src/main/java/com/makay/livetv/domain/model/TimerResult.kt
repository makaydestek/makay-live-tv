package com.makay.livetv.domain.model

/**
 * Zamanlayici ekleme sonucu.
 *
 * UI bunu dogru mesajla karsilar: kullanici "Hatirlat" dugmesine bastiginda
 * neden eklenmedigini gorur (sinir, cakisma, program bitmis vb.).
 */
enum class TimerResult {

    /** Eklendi ve alarm kuruldu. */
    ADDED,

    /** Ayni program icin zaten etkin bir zamanlayici var. */
    DUPLICATE,

    /** En fazla [com.makay.livetv.timer.TimerStore.MAX_SCHEDULED_TIMERS] etkin zamanlayici kurulabilir. */
    LIMIT,

    /** Ayni TV girdisinde bu aralikla cakisan baska bir kayit zamanlayicisi var. */
    CONFLICT,

    /** TV girdisi kayit desteklemiyor. */
    NOT_SUPPORTED,

    /** Programin suresi dolmus. */
    ENDED,
}
