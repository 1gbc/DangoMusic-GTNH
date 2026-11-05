package me.sorihiko.dangomusicgtnh.events

import me.sorihiko.dangomusicgtnh.event.Event

data class WorldRenderEndEvent(
    val partialTicks: Float,
) : Event