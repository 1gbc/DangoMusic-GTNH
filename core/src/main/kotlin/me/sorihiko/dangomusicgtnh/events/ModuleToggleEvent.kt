package me.sorihiko.dangomusicgtnh.events

import me.sorihiko.dangomusicgtnh.event.Event
import me.sorihiko.dangomusicgtnh.module.Module

abstract class ModuleToggleEvent(
    val module: Module,
) : Event {
    class Enable(module: Module) : ModuleToggleEvent(module)
    class Disable(module: Module) : ModuleToggleEvent(module)
}