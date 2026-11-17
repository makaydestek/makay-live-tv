package com.makay.livetv.timer

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zamanlayici olaylarinin uygulama ici dagitimi.
 *
 * `TvAlarmReceiver` arka planda calisirken uygulama (activity) ayakta
 * olabilir; olay dogrudan buraya yazilir ve `AppRoot` toplayip
 * hatirlatma penceresini acar / kaydi baslatir. Surec oluysa alıcı
 * bildirim yoluyla activity'yi baslattiginda ayni olay intent extra'lari
 * ile iletilir (bkz. `MainActivity`).
 *
 * Tek olay tasinir: olayi bir kez okuyan temizler — iki ekranda ayni
 * hatirlatma iki kez gosterilmez.
 */
object TimerEventBus {

    /** Uygulama ici canli olay (receiver → UI). */
    private val _events = MutableSharedFlow<TimerEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TimerEvent> = _events.asSharedFlow()

    /** Intent extra'indan gecici olarak tutulan olay (activity yeniden acilirken). */
    private val _pending = MutableStateFlow<TimerEvent?>(null)
    val pending: StateFlow<TimerEvent?> = _pending.asStateFlow()

    fun emit(event: TimerEvent) {
        _pending.value = event
        _events.tryEmit(event)
    }

    fun consumePending() {
        _pending.value = null
    }
}

/** Zamanlayici tetiklenince UI'a ulasan olay. */
data class TimerEvent(
    val timerId: Long,
    val channelId: Long,
    val channelNumber: String,
    val channelName: String,
    val programTitle: String,
    val isRecord: Boolean,
)
