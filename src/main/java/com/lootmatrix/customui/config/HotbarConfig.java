package com.lootmatrix.customui.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration for the custom Adventure mode hotbar.
 */
public class HotbarConfig {

    public static final ForgeConfigSpec SPEC;
    public static final HotbarConfig INSTANCE;

    static {
        Pair<HotbarConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(HotbarConfig::new);
        SPEC = pair.getRight();
        INSTANCE = pair.getLeft();
    }

    // ==================== Position Configuration ====================
    /**
     * Padding from the right edge of the screen
     */
    public final ForgeConfigSpec.IntValue rightPadding;

    /**
     * Padding from the bottom edge of the screen
     */
    public final ForgeConfigSpec.IntValue bottomPadding;

    /**
     * Spacing between hotbar slots (vertical)
     */
    public final ForgeConfigSpec.IntValue slotSpacing;

    // ==================== Slot Appearance Configuration ====================
    /**
     * Slot width
     */
    public final ForgeConfigSpec.IntValue slotWidth;

    /**
     * Slot height
     */
    public final ForgeConfigSpec.IntValue slotHeight;

    /**
     * Item icon size
     */
    public final ForgeConfigSpec.IntValue iconSize;

    /**
     * Background color for unselected slots (ARGB hex)
     */
    public final ForgeConfigSpec.IntValue normalBgColor;

    /**
     * Background color for selected slot (ARGB hex)
     */
    public final ForgeConfigSpec.IntValue selectedBgColor;

    /**
     * Text color for unselected slots (ARGB hex)
     */
    public final ForgeConfigSpec.IntValue normalTextColor;

    /**
     * Text color for selected slot (ARGB hex)
     */
    public final ForgeConfigSpec.IntValue selectedTextColor;

    // ==================== Hotkey Hint Configuration ====================
    /**
     * Hotkey hint background color (unselected) - white
     */
    public final ForgeConfigSpec.IntValue hotkeyBgColor;

    /**
     * Hotkey hint text color (unselected) - black
     */
    public final ForgeConfigSpec.IntValue hotkeyTextColor;

    /**
     * Hotkey hint background color (selected) - dark gray
     */
    public final ForgeConfigSpec.IntValue hotkeySelectedBgColor;

    /**
     * Hotkey hint text color (selected) - white
     */
    public final ForgeConfigSpec.IntValue hotkeySelectedTextColor;

    /**
     * Hotkey hint scale
     */
    public final ForgeConfigSpec.DoubleValue hotkeyScale;

    /**
     * Hotkey hint padding
     */
    public final ForgeConfigSpec.DoubleValue hotkeyPadding;

    /**
     * Custom key character mappings (format: "keyName=displayChar", e.g., "Mouse Button 4=M4")
     * Maps specific key names to custom display characters in the hotkey hint.
     */
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> hotkeyCharMappings;

    // ==================== Filter Configuration ====================
    /**
     * Item tags to hide from the hotbar (items with these tags won't be displayed)
     */
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> hiddenItemTags;

    /**
     * Item tags that allow temporary selection with auto-return
     */
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> temporaryUseTags;

    /**
     * Item tags to skip when scrolling (scroll wheel will skip these items)
     */
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> skipScrollTags;

    // ==================== Custom Icon Configuration ====================
    /**
     * Item IDs with custom icons (format: "modid:itemid")
     */
    public final ForgeConfigSpec.ConfigValue<List<? extends String>> customIconItems;

    // ==================== Toggle Configuration ====================
    /**
     * Enable or disable the custom adventure hotbar
     */
    public final ForgeConfigSpec.BooleanValue enabled;

    /**
     * Show item info display (gun info / item name) below the hotbar
     * Disabling this can improve performance
     */
    public final ForgeConfigSpec.BooleanValue showItemInfo;

    private HotbarConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("Custom Adventure Mode Hotbar Configuration")
                .push("adventure_hotbar");

        // Toggle
        enabled = builder
                .comment("Enable or disable the custom adventure hotbar")
                .define("enabled", true);

        showItemInfo = builder
                .comment("Show item info display (gun info / item name) below the hotbar",
                        "Disabling this can improve performance by reducing text rendering")
                .define("showItemInfo", true);


        // Position settings
        builder.comment("Position Settings").push("position");

        rightPadding = builder
                .comment("Padding from the right edge of the screen")
                .defineInRange("rightPadding", 5, 0, 500);

        bottomPadding = builder
                .comment("Padding from the bottom edge of the screen")
                .defineInRange("bottomPadding", 1, 0, 500);

        slotSpacing = builder
                .comment("Vertical spacing between slots")
                .defineInRange("slotSpacing", 1, 0, 50);

        builder.pop();

        // Slot appearance settings
        builder.comment("Slot Appearance Settings").push("appearance");

        slotWidth = builder
                .comment("Width of each slot")
                .defineInRange("slotWidth", 80, 40, 200);

        slotHeight = builder
                .comment("Height of each slot")
                .defineInRange("slotHeight", 22, 16, 50);

        iconSize = builder
                .comment("Size of item icons")
                .defineInRange("iconSize", 16, 8, 32);

        normalBgColor = builder
                .comment("Background color for unselected slots (ARGB hex, e.g., 0xCC000000)")
                .defineInRange("normalBgColor", 0xCC000000, Integer.MIN_VALUE, Integer.MAX_VALUE);

        selectedBgColor = builder
                .comment("Background color for selected slot (ARGB hex, e.g., 0xAAFFFFFF)")
                .defineInRange("selectedBgColor", 0xAAFFFFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);

        normalTextColor = builder
                .comment("Text color for unselected slots (ARGB hex, e.g., 0xFFFFFFFF)")
                .defineInRange("normalTextColor", 0xFFFFFFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);

        selectedTextColor = builder
                .comment("Text color for selected slot (ARGB hex, e.g., 0xFF000000)")
                .defineInRange("selectedTextColor", 0xFF000000, Integer.MIN_VALUE, Integer.MAX_VALUE);

        builder.pop();

        // Hotkey hint settings
        builder.comment("Hotkey Hint Settings").push("hotkey");

        hotkeyBgColor = builder
                .comment("Hotkey background color for unselected (ARGB hex) - darker")
                .defineInRange("hotkeyBgColor", 0x99FFFFFF, Integer.MIN_VALUE, Integer.MAX_VALUE);

        hotkeyTextColor = builder
                .comment("Hotkey text color for unselected (ARGB hex) - lighter")
                .defineInRange("hotkeyTextColor", 0xFF292929, Integer.MIN_VALUE, Integer.MAX_VALUE);

        hotkeySelectedBgColor = builder
                .comment("Hotkey background color for selected (ARGB hex) - darker")
                .defineInRange("hotkeySelectedBgColor", 0xDD333333, Integer.MIN_VALUE, Integer.MAX_VALUE);

        hotkeySelectedTextColor = builder
                .comment("Hotkey text color for selected (ARGB hex)")
                .defineInRange("hotkeySelectedTextColor", 0xFFAAAAAA, Integer.MIN_VALUE, Integer.MAX_VALUE);

        hotkeyScale = builder
                .comment("Scale for hotkey hint text")
                .defineInRange("hotkeyScale", 0.75, 0.5, 2.0);

        hotkeyPadding = builder
                .comment("Padding around hotkey text")
                .defineInRange("hotkeyPadding", 2.0, 0.0, 10.0);

        hotkeyCharMappings = builder
                .comment("Custom key character mappings (format: \"keyName=displayChar\")",
                        "Maps specific key names to custom display characters in the hotkey hint.",
                        "Example: [\"Button 4=M4\", \"Button 5=M5\", \"Numpad 1=N1\"]")
                .defineList("hotkeyCharMappings",
                        Arrays.asList(
                                // Mouse buttons - English
                                "Left Button=M1",
                                "Right Button=M2",
                                "Middle Button=M3",
                                "Button 4=M4",
                                "Button 5=M5",
                                "Button 6=M6",
                                "Button 7=M7",
                                "Button 8=M8",
                                // Mouse buttons - Chinese
                                "左键=M1",
                                "右键=M2",
                                "中键=M3",
                                "鼠标按键4=M4",
                                "鼠标按键5=M5",
                                "鼠标按键6=M6",
                                "鼠标按键7=M7",
                                "鼠标按键8=M8",
                                // Numpad - English
                                "Numpad 0=N0",
                                "Numpad 1=N1",
                                "Numpad 2=N2",
                                "Numpad 3=N3",
                                "Numpad 4=N4",
                                "Numpad 5=N5",
                                "Numpad 6=N6",
                                "Numpad 7=N7",
                                "Numpad 8=N8",
                                "Numpad 9=N9",
                                "Numpad Add=N+",
                                "Numpad Subtract=N-",
                                "Numpad Multiply=N*",
                                "Numpad Divide=N/",
                                "Numpad Decimal=N.",
                                "Numpad Enter=N↵",
                                // Numpad - Chinese
                                "小键盘 0=N0",
                                "小键盘 1=N1",
                                "小键盘 2=N2",
                                "小键盘 3=N3",
                                "小键盘 4=N4",
                                "小键盘 5=N5",
                                "小键盘 6=N6",
                                "小键盘 7=N7",
                                "小键盘 8=N8",
                                "小键盘 9=N9",
                                "小键盘 加=N+",
                                "小键盘 减=N-",
                                "小键盘 乘=N*",
                                "小键盘 除=N/",
                                // Function keys - English
                                "F1=F1",
                                "F2=F2",
                                "F3=F3",
                                "F4=F4",
                                "F5=F5",
                                "F6=F6",
                                "F7=F7",
                                "F8=F8",
                                "F9=F9",
                                "F10=F10",
                                "F11=F11",
                                "F12=F12",
                                // Special keys - English
                                "Left Shift=L⇧",
                                "Right Shift=R⇧",
                                "Left Control=LC",
                                "Right Control=RC",
                                "Left Alt=LA",
                                "Right Alt=RA",
                                "Space=␣",
                                "Tab=⇥",
                                "Caps Lock=⇪",
                                "Enter=↵",
                                "Backspace=⌫",
                                "Delete=Del",
                                "Insert=Ins",
                                "Home=Hm",
                                "End=End",
                                "Page Up=PU",
                                "Page Down=PD",
                                "Up=↑",
                                "Down=↓",
                                "Left=←",
                                "Right=→",
                                "Escape=Esc",
                                "Print Screen=Prt",
                                "Scroll Lock=SL",
                                "Pause=Pau",
                                // Special keys - Chinese
                                "左Shift=L⇧",
                                "右Shift=R⇧",
                                "左Control=LC",
                                "右Control=RC",
                                "左Alt=LA",
                                "右Alt=RA",
                                "空格=␣",
                                "退格=⌫",
                                "删除=Del",
                                "插入=Ins",
                                "回车=↵",
                                "上=↑",
                                "下=↓",
                                "左=←",
                                "右=→",
                                // Punctuation and symbols
                                "Grave Accent=`",
                                "Minus=-",
                                "Equal==",
                                "Left Bracket=[",
                                "Right Bracket=]",
                                "Backslash=\\",
                                "Semicolon=;",
                                "Apostrophe='",
                                "Comma=,",
                                "Period=.",
                                "Slash=/",
                                // Windows/Super and Menu keys - English
                                "Left Win=LW",
                                "Right Win=RW",
                                "Left Super=LW",
                                "Right Super=RW",
                                "Menu=☰",
                                // Windows/Super and Menu keys - Chinese
                                "左Windows=LW",
                                "右Windows=RW",
                                "菜单=☰",
                                // Number Lock and related
                                "Num Lock=NL",
                                "数字锁定=NL",
                                // Arrow keys - alternative Chinese
                                "上箭头=↑",
                                "下箭头=↓",
                                "左箭头=←",
                                "右箭头=→",
                                // More Chinese key name variants
                                "制表符=⇥",
                                "大写锁定=⇪",
                                "转义=Esc",
                                "打印屏幕=Prt",
                                "滚动锁定=SL",
                                "暂停=Pau",
                                "主页=Hm",
                                "结束=End",
                                "向上翻页=PU",
                                "向下翻页=PD",
                                // Letter keys (single letter stays as is, but can add mappings if needed)
                                "Q=Q", "W=W", "E=E", "R=R", "T=T", "Y=Y", "U=U", "I=I", "O=O", "P=P",
                                "A=A", "S=S", "D=D", "F=F", "G=G", "H=H", "J=J", "K=K", "L=L",
                                "Z=Z", "X=X", "C=C", "V=V", "B=B", "N=N", "M=M",
                                // Number keys (top row)
                                "0=0", "1=1", "2=2", "3=3", "4=4", "5=5", "6=6", "7=7", "8=8", "9=9"
                        ),
                        obj -> obj instanceof String);

        builder.pop();

        // Filter settings - hidden from config file, using internal defaults
        // These settings are kept for internal use but not exposed to users
        hiddenItemTags = builder
                .defineList("_internal_hiddenItemTags",
                        Arrays.asList("customui:hotbar_hidden"),
                        obj -> obj instanceof String);

        temporaryUseTags = builder
                .defineList("_internal_temporaryUseTags",
                        Arrays.asList("customui:temporary_use"),
                        obj -> obj instanceof String);

        skipScrollTags = builder
                .defineList("_internal_skipScrollTags",
                        Arrays.asList("customui:skip_scroll"),
                        obj -> obj instanceof String);


        // Custom icon settings
        builder.comment("Custom Icon Settings").push("custom_icons");

        customIconItems = builder
                .comment("Item IDs with custom icons (format: modid:itemid)")
                .defineList("customIconItems",
                        Arrays.asList(),
                        obj -> obj instanceof String);

        builder.pop();

        builder.pop(); // End adventure_hotbar
    }
}

