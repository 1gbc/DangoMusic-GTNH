import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Paths;

import me.sorihiko.dangomusicgtnh.DangoMusic;
import me.sorihiko.dangomusicgtnh.module.impls.music.NeteaseMusicModule;
import me.sorihiko.dangomusicgtnh.module.values.FloatValue;
import me.sorihiko.dangomusicgtnh.module.values.IntValue;
import me.sorihiko.dangomusicgtnh.music.MusicScreen;
import me.sorihiko.dangomusicgtnh.platform.Keys;
import me.sorihiko.dangomusicgtnh.platform.Platform;
import me.sorihiko.dangomusicgtnh.shadow.kotlin.Unit;
import me.sorihiko.dangomusicgtnh.shadow.kotlin.jvm.functions.Function1;

/** Run against the shaded Mod JAR with Gson and Log4j API on the classpath. */
public final class SettingsInputCheck {
    private final MusicScreen screen;
    private final Object settings;
    private boolean ctrl;

    private SettingsInputCheck() throws Exception {
        Platform platform = (Platform) Proxy.newProxyInstance(
            Platform.class.getClassLoader(), new Class<?>[] {Platform.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getConfigDir": return Paths.get(".");
                    case "getCtrlDown": return ctrl;
                    default: throw new AssertionError("Unexpected platform call: " + method.getName());
                }
            });
        Constructor<DangoMusic> constructor = DangoMusic.class.getDeclaredConstructor(Platform.class);
        constructor.setAccessible(true);
        field(DangoMusic.class, "instance").set(null, constructor.newInstance(platform));
        screen = new MusicScreen(new NeteaseMusicModule(), () -> Unit.INSTANCE);
        settings = field(MusicScreen.class, "settings").get(screen);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void edit(Object setting) throws Exception {
        Class<?> type = Class.forName(settings.getClass().getName() + "$Row");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != 9) continue;
            constructor.setAccessible(true);
            Object control = constructor.getParameterTypes()[3].getEnumConstants()[0];
            field(settings.getClass(), "editRow").set(settings,
                constructor.newInstance("Fixture", "", setting, control, null,
                    (Function1<Object, String>) Object::toString, 1f, "", null));
            field(settings.getClass(), "editText").set(settings, "");
            return;
        }
        throw new AssertionError("Settings row constructor unavailable");
    }

    private void key(char ch, int key, boolean sdlTextActive) throws Exception {
        Method dispatch;
        try {
            dispatch = MusicScreen.class.getMethod("keyTyped", char.class, int.class, boolean.class);
        } catch (NoSuchMethodException baseline) {
            MusicScreen.class.getMethod("keyTyped", char.class, int.class).invoke(screen, ch, key);
            return;
        }
        dispatch.invoke(screen, ch, key, sdlTextActive);
    }

    private void typed(char ch, int key) throws Exception {
        key(ch, key, true);         // SDL physical key event with a translated character
        key(ch, Keys.NONE, true);  // SDL committed text for the same key
    }

    private void expectText(String expected) throws Exception {
        String actual = (String) field(settings.getClass(), "editText").get(settings);
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) throws Exception {
        SettingsInputCheck test = new SettingsInputCheck();
        IntValue integer = new IntValue(0, 999, 0);
        test.edit(integer);
        test.typed('1', 2);
        test.expectText("1");
        test.typed('1', 2);
        test.key('\0', 79, true); // Keypad key may have no physical character.
        test.key('1', Keys.NONE, true);
        test.expectText("111");
        test.key('\0', Keys.BACK, true);
        test.key('\0', Keys.NUMPADENTER, true);
        if (integer.getValue() != 11) throw new AssertionError("Integer commit");

        FloatValue decimal = new FloatValue(-10f, 10f, 0f, 2);
        test.edit(decimal);
        test.typed('-', 12);
        test.typed('1', 2);
        test.typed(',', 51);
        test.typed('5', 6);
        test.expectText("-1,5");
        test.key('\0', Keys.RETURN, true);
        if (decimal.getValue() != -1.5f) throw new AssertionError("Decimal commit");
        test.edit(decimal);
        test.key('2', 3, false);
        test.expectText("2");
        test.ctrl = true;
        test.key('\u0001', Keys.A, true);
        test.expectText("");
        test.ctrl = false;
        test.key('\0', Keys.ESCAPE, true);
        if (decimal.getValue() != -1.5f) throw new AssertionError("Cancel changed the value");

        field(test.settings.getClass(), "hexEditing").set(test.settings, true);
        test.typed('a', Keys.A);
        test.typed('1', 2);
        Object hex = field(test.settings.getClass(), "hexText").get(test.settings);
        if (!"A1".equals(hex)) throw new AssertionError("Hex input: " + hex);
        System.out.println("Settings input passed: SDL pairs, repeated digits, keypad, decimal, edit controls, fallback and hex.");
    }
}
