package me.sorihiko.dangomusicgtnh.legacy

import me.sorihiko.dangomusicgtnh.platform.KeyBindingLink
import me.sorihiko.dangomusicgtnh.platform.NativePlayerKey
import me.sorihiko.dangomusicgtnh.platform.PlayerAction
import me.sorihiko.dangomusicgtnh.platform.PlayerKeyBinding
import me.sorihiko.dangomusicgtnh.platform.savedPlayerKeys
import net.minecraft.client.settings.KeyBinding
import cpw.mods.fml.client.registry.ClientRegistry

/** saveOptions() 会把所有按键（包括其他模组的）写回 options.txt，所以只在我们的按键真的变了时才保存（issue #18） */
object LegacyKeyBindings {
    private val mappings by lazy {
        PlayerAction.values().associateWith { KeyBinding(it.translationKey, it.defaultKey, "key.categories.dangomusicgtnh") }
    }
    private var registered = false
    private var links: List<KeyBindingLink>? = null

    // Controlling adds these methods to 1.7.10 KeyBinding via mixin. Vanilla Forge still supports single keys.
    private val modifiers by lazy {
        try {
            ModifierAccess()
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    private class ModifierAccess {
        private val type = KeyBinding::class.java
        private val getModifier = type.getMethod("controlling\$getKeyModifier")
        private val setBinding = type.getMethod("controlling\$setKeyModifierAndCode", getModifier.returnType, Int::class.javaPrimitiveType)
        private val isModifierActive = type.getMethod("controlling\$isModifierActive")
        private val getDisplayName = type.getMethod("controlling\$getDisplayName")
        private val modifierKey = getModifier.returnType.getMethod("isKeyCodeModifier", Int::class.javaPrimitiveType)
        private val activeModifier = getModifier.returnType.getMethod("getActiveModifier")
        private val values = getModifier.returnType.enumConstants.associateBy { (it as Enum<*>).name }

        fun activeModifier(): String? = (activeModifier.invoke(null) as? Enum<*>)?.name?.let(::supported)
        fun modifier(mapping: KeyBinding) = (getModifier.invoke(mapping) as Enum<*>).name.takeUnless { it == "NONE" }
        fun supported(name: String) = name.takeIf { it != "NONE" && it in values }
        fun write(mapping: KeyBinding, key: NativePlayerKey) {
            setBinding.invoke(mapping, values.getValue(key.modifier ?: "NONE"), key.code)
        }
        fun isActive(mapping: KeyBinding) = isModifierActive.invoke(mapping) as Boolean
        fun isModifierKey(code: Int) = modifierKey.invoke(null, code) as Boolean
        fun displayName(mapping: KeyBinding) = getDisplayName.invoke(mapping) as String
    }

    @JvmStatic
    fun register() {
        if (registered) return
        mappings.values.forEach { ClientRegistry.registerKeyBinding(it) }
        registered = true
    }

    fun sync(bindings: Collection<PlayerKeyBinding>) {
        if (!registered) return
        val settings = mc.gameSettings ?: return
        var dirty = false
        var refresh = false
        if (links == null) {
            val saved = savedPlayerKeys(mc.mcDataDir.toPath().resolve("options.txt"))
            links = bindings.map { binding ->
                val mapping = mappings.getValue(binding.action)
                val before = read(mapping)
                val savedKey = saved[binding.action.translationKey]?.let(::parse)
                KeyBindingLink(binding, { read(mapping) }, { write(mapping, it) }, savedKey).also {
                    val current = read(mapping)
                    if (current != before) refresh = true
                    if (current != (savedKey ?: before)) dirty = true
                }
            }
        }
        links!!.forEach { if (it.sync()) dirty = true }
        if (refresh || dirty) KeyBinding.resetKeyBindingArrayAndHash()
        if (dirty) settings.saveOptions()
    }

    fun isModifierKey(keyCode: Int): Boolean = modifiers?.isModifierKey(keyCode) == true

    fun activeModifier(): String? = modifiers?.activeModifier()

    fun modifierActive(action: PlayerAction): Boolean {
        val access = modifiers ?: return true
        val mapping = mappings.getValue(action)
        if (access.modifier(mapping) != null) return access.isActive(mapping)
        val code = mapping.keyCode
        if (access.isModifierKey(code)) return true
        // 和 Controlling 一样，共用主键时优先识别当前按下的组合键。
        val bindings = mc.gameSettings?.keyBindings?.asIterable() ?: mappings.values
        return bindings.none { other ->
            other !== mapping && other.keyCode == code && access.modifier(other) != null && access.isActive(other)
        }
    }

    fun displayName(action: PlayerAction): String? {
        val access = modifiers ?: return null
        val mapping = mappings.getValue(action)
        return if (access.modifier(mapping) == null) null else access.displayName(mapping)
    }

    private fun read(mapping: KeyBinding) = NativePlayerKey(mapping.keyCode, modifiers?.modifier(mapping))

    private fun write(mapping: KeyBinding, key: NativePlayerKey) {
        val access = modifiers
        if (access != null) access.write(mapping, key) else mapping.keyCode = key.code
    }

    private fun parse(serialized: String): NativePlayerKey? {
        val code = serialized.substringBefore(':').toIntOrNull() ?: return null
        val modifier = modifiers?.supported(serialized.substringAfter(':', ""))
        return NativePlayerKey(code, modifier)
    }
}
