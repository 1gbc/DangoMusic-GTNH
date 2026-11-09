package me.sorihiko.dangomusicgtnh.legacy

import me.sorihiko.dangomusicgtnh.DangoLogger
import java.lang.reflect.Method

/** lwjgl3ify queues committed SDL text as ordinary LWJGL keyboard character events. */
internal class TextInputFocus {
    var active = false
        private set

    fun setActive(focused: Boolean) {
        if (focused == active) return
        val methods = inputEvents ?: return
        try {
            (if (focused) methods.begin else methods.end).invoke(null)
            active = focused
        } catch (error: ReflectiveOperationException) {
            DangoLogger.warn("Could not change lwjgl3ify text focus: {}", error.javaClass.simpleName)
            inputEvents = null
        }
    }

    private class InputEvents(val begin: Method, val end: Method)

    companion object {
        private var inputEvents: InputEvents? = try {
            val api = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents")
            InputEvents(api.getMethod("beginTextInput"), api.getMethod("endTextInput"))
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
