package me.sorihiko.dangomusicgtnh.events

import me.sorihiko.dangomusicgtnh.event.Event

data class WorldRenderEndEvent(
    val partialTicks: Float,
    val playerEyeY: Double? = null,
) : Event