package com.makay.livetv.ui

import android.view.KeyEvent
import android.view.ViewConfiguration

/** Dispatches one action per OK gesture, including remotes without a long-press flag. */
internal class RemoteOkPress {
    private var pressedAt: Long? = null
    private var longDispatched = false

    fun reset() {
        pressedAt = null
        longDispatched = false
    }

    fun handle(event: KeyEvent, onClick: () -> Unit, onLongClick: () -> Unit): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Unit
            else -> return false
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    pressedAt = event.downTime
                    longDispatched = false
                } else if (pressedAt == event.downTime && !longDispatched && event.isLongOk()) {
                    longDispatched = true
                    onLongClick()
                }
            }
            KeyEvent.ACTION_UP -> {
                val shouldDispatch = pressedAt == event.downTime && !event.isCanceled && !longDispatched
                reset()
                if (shouldDispatch) {
                    if (event.isLongOk()) onLongClick() else onClick()
                }
            }
            else -> return false
        }
        return true
    }

    private fun KeyEvent.isLongOk(): Boolean =
        isLongPress || eventTime - downTime >= ViewConfiguration.getLongPressTimeout()
}
