package fan.summer.fengyu.ai.tools;

import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComputerKeyMapTest {

    @Test
    void parsesPlainKeys() {
        assertEquals(KeyEvent.VK_ENTER, ComputerKeyMap.parse("enter", "mac os x").keyCode());
        assertEquals(KeyEvent.VK_ENTER, ComputerKeyMap.parse("Return", "Mac OS X").keyCode());
        assertEquals(KeyEvent.VK_ESCAPE, ComputerKeyMap.parse("esc", "linux").keyCode());
        assertEquals(KeyEvent.VK_TAB, ComputerKeyMap.parse("tab", "linux").keyCode());
        assertEquals(KeyEvent.VK_SPACE, ComputerKeyMap.parse("space", "linux").keyCode());
        assertEquals(KeyEvent.VK_C, ComputerKeyMap.parse("c", "linux").keyCode());
        assertEquals(KeyEvent.VK_3, ComputerKeyMap.parse("3", "linux").keyCode());
        assertEquals(KeyEvent.VK_F5, ComputerKeyMap.parse("f5", "linux").keyCode());
        assertEquals(KeyEvent.VK_UP, ComputerKeyMap.parse("up", "linux").keyCode());
        assertEquals(List.of(), ComputerKeyMap.parse("enter", "linux").modifiers());
    }

    @Test
    void parsesModifierCombos() {
        var combo = ComputerKeyMap.parse("cmd+shift+3", "mac os x");
        assertEquals(List.of(KeyEvent.VK_META, KeyEvent.VK_SHIFT), combo.modifiers());
        assertEquals(KeyEvent.VK_3, combo.keyCode());

        assertEquals(List.of(KeyEvent.VK_CONTROL), ComputerKeyMap.parse("ctrl+c", "windows 11").modifiers());
        assertEquals(List.of(KeyEvent.VK_ALT), ComputerKeyMap.parse("alt+f4", "windows 11").modifiers());
        assertEquals(List.of(KeyEvent.VK_SHIFT), ComputerKeyMap.parse("shift+arrowright", "linux").modifiers());
        assertEquals(KeyEvent.VK_RIGHT, ComputerKeyMap.parse("shift+arrowright", "linux").keyCode());
    }

    @Test
    void controlOrMetaResolvesPerOs() {
        assertEquals(KeyEvent.VK_META, ComputerKeyMap.parse("controlormeta+a", "mac os x")
                .modifiers().get(0));
        assertEquals(KeyEvent.VK_CONTROL, ComputerKeyMap.parse("controlormeta+a", "windows 11")
                .modifiers().get(0));
        assertEquals(KeyEvent.VK_CONTROL, ComputerKeyMap.parse("ControlOrMeta+A", "linux")
                .modifiers().get(0));
    }

    @Test
    void unknownKeysFailWithFriendlyMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ComputerKeyMap.parse("caps+lol", "linux"));
        assertTrue(e.getMessage().contains("unknown modifier 'caps'"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ComputerKeyMap.parse("notakey", "linux"));
        assertThrows(IllegalArgumentException.class, () -> ComputerKeyMap.parse("", "linux"));
        assertThrows(IllegalArgumentException.class, () -> ComputerKeyMap.parse("a+b+c+d+e+f", "linux"),
                "combos longer than 5 tokens are rejected");
    }

    @Test
    void strokesMapAsciiWithShiftHandling() {
        assertEquals(new ComputerKeyMap.Stroke(KeyEvent.VK_A, false), ComputerKeyMap.strokeFor('a'));
        assertEquals(new ComputerKeyMap.Stroke(KeyEvent.VK_A, true), ComputerKeyMap.strokeFor('A'));
        assertEquals(new ComputerKeyMap.Stroke(KeyEvent.VK_5, false), ComputerKeyMap.strokeFor('5'));
        // '!' is shift+1 on a US layout.
        assertEquals(new ComputerKeyMap.Stroke(KeyEvent.VK_1, true), ComputerKeyMap.strokeFor('!'));
        assertEquals(new ComputerKeyMap.Stroke(KeyEvent.VK_SPACE, false), ComputerKeyMap.strokeFor(' '));
        assertEquals(new ComputerKeyMap.Stroke(KeyEvent.VK_SLASH, true), ComputerKeyMap.strokeFor('?'));
    }

    @Test
    void typeableDetectsNonAscii() {
        assertTrue(ComputerKeyMap.typeable("hello world 123 !?"));
        assertFalse(ComputerKeyMap.typeable("你好"));
        assertFalse(ComputerKeyMap.typeable("café"));
    }
}
