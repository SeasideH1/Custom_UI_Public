package com.lootmatrix.customui.client.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.lootmatrix.customui.hud.HudAnchor;
import com.lootmatrix.customui.hud.HudEasing;
import com.lootmatrix.customui.hud.HudElement;
import com.lootmatrix.customui.hud.HudKeyframe;
import com.lootmatrix.customui.hud.HudTemplate;
import com.lootmatrix.customui.hud.HudTemplateRegistry;
import com.lootmatrix.customui.network.HudTemplateDeletePacket;
import com.lootmatrix.customui.network.HudTemplateUploadPacket;
import com.lootmatrix.customui.network.ModNetworkHandler;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Visual HUD template editor (opened with /customui hud editor [id]).
 *
 * Layout: left = template library (click to select, double-click to load,
 * drag onto the canvas to embed as a TEMPLATE element) + element palette;
 * right = scrollable property panel (element or selected keyframe);
 * bottom = keyframe timeline with playhead, transport and easing controls;
 * center = live canvas preview rendered through the real overlay pipeline.
 *
 * Saving uploads the template JSON to the server (world storage) which then
 * broadcasts the new definition to every client.
 */
@OnlyIn(Dist.CLIENT)
public class HudEditorScreen extends Screen {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson GSON_COMPACT = new GsonBuilder().create();
    private static final int UNDO_LIMIT = 64;
    /** Rapid edits of the same control within this window merge into one undo step. */
    private static final long UNDO_COALESCE_MS = 1200;

    private static final int TOP_H = 24;
    // Panel sizes: user-resizable by dragging the dividers (viewport auto-fits).
    // Kept upper-case so the 60+ existing layout references stay untouched.
    private int LEFT_W = 112;
    private int RIGHT_W = 138;
    private int TL_H = 56;
    private static final int LEFT_W_MIN = 80;
    private static final int RIGHT_W_MIN = 110;
    private static final int TL_H_MIN = 40;
    private static final int DIVIDER_GRAB = 4;
    /** 0 = none, 1 = left divider, 2 = right divider, 3 = timeline divider. */
    private int resizingDivider = 0;
    private static final int ROW_H = 16;
    private static final int LIB_ROW_H = 11;
    /** Right property panel: label column + value column layout. */
    private static final int RIGHT_PAD = 4;
    private static final int RIGHT_LABEL_MIN = 48;
    private static final int RIGHT_FIELD_GAP = 0;
    private static final int RIGHT_FIELD_H = 13;
    private static final double DRAG_START_SLOP = 4.0D;

    private static final int PANEL_BG = 0xE0101418;
    private static final int PANEL_EDGE = 0xFF2A3540;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int TEXT_DIM = 0xFF9AA7B0;

    private final String initialTemplateId;
    private HudTemplate template;

    private int selectedElement = -1;
    private int selectedKeyframe = -1;
    private float playhead = 0f;
    private boolean playing = false;
    /** Preview playback rate (keyframe ticks per real second, 20 = in-game speed). */
    private float playTicksPerSecond = 20f;
    /** Wall-clock anchor for time-driven playback (re-anchored on play/rate change). */
    private long playAnchorMillis = 0L;
    private float playAnchorPlayhead = 0f;
    /** Wall-clock-interpolated preview time (frame-smooth while playing). */
    private float renderTime = 0f;

    private String idText = "customui:new_template";
    @Nullable private EditBox idBox;

    // Right panel rows
    private static final class Row {
        final String label;
        @Nullable final AbstractWidget widget;
        Row(String label, @Nullable AbstractWidget widget) {
            this.label = label;
            this.widget = widget;
        }
    }
    private final List<Row> rightRows = new ArrayList<>();
    private int rightScroll = 0;

    // Library state
    private List<String> libraryIds = new ArrayList<>();
    private int libScroll = 0;
    private int libSelected = -1;
    private long libLastClickMillis = 0;
    private int libLastClickIndex = -1;
    private int libDragIndex = -1;
    private boolean libDragging = false;

    // Canvas / timeline interactions
    private boolean pendingElementDrag = false;
    private boolean draggingElement = false;
    private boolean draggingPlayhead = false;
    private int pendingKeyframeDrag = -1;
    private int draggingKeyframe = -1;
    private double lastMouseX, lastMouseY;
    private double pendingDragStartMouseX, pendingDragStartMouseY;

    // Virtual-screen viewport: the full game screen is scaled down into the
    // uncovered center area so panels never hide canvas content. All canvas
    // math stays in virtual (real-screen) coordinates; only rendering and
    // mouse input pass through the viewport transform.
    private static final int VIEWPORT_PAD = 8;
    private float viewportX, viewportY, viewportScale = 1f;
    /** Snapshot of the main framebuffer (3D world) drawn scaled into the viewport. */
    @Nullable private com.mojang.blaze3d.pipeline.TextureTarget worldSnapshot;
    // Center-line snapping while dragging (virtual pixels)
    private static final float SNAP_DISTANCE = 5f;
    private boolean snapActiveX = false;
    private boolean snapActiveY = false;
    /** Grab offset: primary element box center minus virtual mouse, fixed at drag start. */
    private float dragGrabOffsetX, dragGrabOffsetY;
    private boolean dragGrabValid = false;

    private String status = "";
    private long statusUntilMillis = 0;
    private final float[] boxScratch = new float[4];
    private String savedTemplateSnapshot = "";

    // Undo / redo / clipboard
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();
    private String lastUndoTag = "";
    private long lastUndoTagMillis = 0;
    @Nullable private String elementClipboard;

    // Control palette: one control per row with a style preview + name.
    // Includes all element types plus prefab composite controls.
    private static final String[][] PALETTE_ENTRIES = {
            {"文本", "TEXT"}, {"图片", "IMAGE"},
            {"矩形", "RECT"}, {"圆角矩形", "ROUNDED_RECT"},
            {"圆形/圆环", "CIRCLE"}, {"三角形", "TRIANGLE"},
            {"边框", "BORDER"}, {"线条", "LINE"},
            {"渐变矩形", "GRADIENT_RECT"}, {"扇形/弧", "ARC"},
            {"进度条", "PROGRESS"}, {"嵌套模板", "TEMPLATE"},
            {"组合", "GROUP"}, {"性能图表", "STAT"},
            {"占领点进度", "PRESET_CAPTURE"},
            {"占领点3D标记", "PRESET_CAPTURE_3D"},
            {"双方计分栏", "PRESET_TEAM_SCORE"},
    };
    private static final int PALETTE_H = 132;
    private static final int PALETTE_ROW_H = 13;
    private int paletteScroll = 0;

    /** Editor-side style preset cursor per element (transient, not saved). */
    private final java.util.IdentityHashMap<HudElement, Integer> stylePresetIndex =
            new java.util.IdentityHashMap<>();

    // Group editing: chain of entered GROUP elements (empty = top level).
    // The "edit list" is the children of the deepest entered group.
    private final List<HudElement> groupPath = new ArrayList<>();
    /** Extra selected indices (multi-select via Shift+click); primary = selectedElement. */
    private final java.util.LinkedHashSet<Integer> extraSelected = new java.util.LinkedHashSet<>();
    private final float[] editRectScratch = new float[4];
    private long canvasLastClickMillis = 0;
    private int canvasLastClickElement = -1;

    // Hover state (topmost element under cursor + overlap hint, rebuilt only on change)
    private int hoveredElement = -1;
    private int hoverStackCount = 0;
    private String hoverInfo = "";
    private int hoverInfoWidth = 0;
    private int hoverInfoSignature = Integer.MIN_VALUE;
    /** Set when a canvas/timeline drag starts; the first actual move pushes one undo snapshot. */
    private boolean dragUndoArmed = false;

    // Big-edit overlay (right-click any text row to open a large multi-line editor)
    @Nullable private MultiLineEditBox bigEditBox;
    @Nullable private EditBox bigEditTarget;
    private final List<EditBox> bigEditableBoxes = new ArrayList<>();
    @Nullable private AutoCloseable templateCacheListener;
    private boolean templateCacheDirty = false;

    // Texture path autocomplete (IMAGE elements)
    @Nullable private EditBox textureBox;
    private List<String> textureIndex = List.of();
    private final List<String> textureSuggestions = new ArrayList<>();
    private int textureSuggestionSelected = -1;
    private static final int SUGGESTION_ROW_H = 11;
    private static final int SUGGESTION_LIMIT = 8;

    public HudEditorScreen(String templateId) {
        super(Component.literal("HUD Editor"));
        this.initialTemplateId = templateId == null ? "" : templateId;
    }

    /** Packet bridge entry point (EDITOR action). */
    public static void open(String templateId) {
        Minecraft.getInstance().setScreen(new HudEditorScreen(templateId));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }


    @Override
    public void removed() {
        // Editor works on private copies; drop their accumulated runtime caches
        if (template != null) {
            HudElementRuntime.clearTemplate(template);
        }
        if (worldSnapshot != null) {
            worldSnapshot.destroyBuffers();
            worldSnapshot = null;
        }
        if (templateCacheListener != null) {
            try {
                templateCacheListener.close();
            } catch (Exception ignored) {
            }
            templateCacheListener = null;
        }
        super.removed();
    }

    // ==================== Init / layout ====================

    @Override
    protected void init() {
        if (templateCacheListener == null) {
            templateCacheListener = HudClientTemplateCache.addChangeListener(this::onTemplateCacheChanged);
        }
        updateViewport();
        if (template == null) {
            HudTemplate source = initialTemplateId.isEmpty() ? null : HudClientTemplateCache.get(initialTemplateId);
            if (source != null) {
                template = source.copy();
                idText = template.id;
            } else {
                template = new HudTemplate();
                template.id = idText;
            }
            markCurrentTemplateSnapshotSaved();
        }
        if (textureIndex.isEmpty()) {
            // One-time texture id index for the autocomplete popup (all loaded packs)
            List<String> index = new ArrayList<>();
            Minecraft.getInstance().getResourceManager()
                    .listResources("textures", rl -> rl.getPath().endsWith(".png"))
                    .keySet().forEach(rl -> index.add(rl.toString()));
            Collections.sort(index);
            textureIndex = index;
        }
        refreshLibrary();
        rebuildAll();
        setStatus("提示：右键文本输入框可打开大编辑器");
    }

    private void onTemplateCacheChanged() {
        // Coalesce bursts (chunked full syncs) into one rebuild on the next frame
        templateCacheDirty = true;
    }

    private void applyTemplateCacheRefresh() {
        if (!templateCacheDirty) return;
        templateCacheDirty = false;
        refreshLibrary();
        rebuildAll();
        setStatus("模板库已刷新");
    }

    private void refreshLibrary() {
        libraryIds = new ArrayList<>(HudClientTemplateCache.snapshot().keySet());
        if (libSelected >= libraryIds.size()) libSelected = -1;
    }

    private void rebuildAll() {
        clearWidgets();
        rightRows.clear();
        bigEditableBoxes.clear();
        textureBox = null;
        textureSuggestions.clear();
        textureSuggestionSelected = -1;

        // ---- Top bar ----
        idBox = new EditBox(font, 30, 4, 150, 14, Component.literal("id"));
        idBox.setMaxLength(128);
        idBox.setValue(idText);
        idBox.setResponder(s -> idText = s);
        addRenderableWidget(idBox);

        int x = 184;
        addRenderableWidget(Button.builder(Component.literal("新建"), b -> newTemplate()).bounds(x, 3, 34, 16).build());
        x += 36;
        addRenderableWidget(Button.builder(Component.literal("保存"), b -> saveToServer()).bounds(x, 3, 34, 16).build());
        x += 36;
        addRenderableWidget(Button.builder(Component.literal("复制JSON"), b -> copyJson()).bounds(x, 3, 58, 16).build());
        x += 60;
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(x, 3, 34, 16).build());

        // ---- Left palette (scrollable preview list, rendered in renderPanels) ----
        int py = paletteTop();
        addRenderableWidget(Button.builder(Component.literal("载入选中模板"), b -> loadSelectedLibrary())
                .bounds(4, py + PALETTE_H - 16, LEFT_W - 8 - 38, 14).build());
        addRenderableWidget(Button.builder(Component.literal("删除"), b -> deleteSelectedLibrary())
                .bounds(4 + LEFT_W - 8 - 36, py + PALETTE_H - 16, 36, 14).build());

        // ---- Timeline transport ----
        int ty = height - TL_H + 36;
        int tx = LEFT_W + 4;
        addRenderableWidget(Button.builder(Component.literal(playing ? "暂停" : "播放"), b ->
                setPlaying(!playing)).bounds(tx, ty, 34, 14).build());
        tx += 36;
        addRenderableWidget(Button.builder(Component.literal("|<"), b -> {
            playhead = 0f;
            playing = false;
            rebuildAll();
        }).bounds(tx, ty, 20, 14).build());
        tx += 22;
        addRenderableWidget(Button.builder(Component.literal("+关键帧"), b -> addKeyframeAtPlayhead())
                .bounds(tx, ty, 48, 14).build());
        tx += 50;
        addRenderableWidget(Button.builder(Component.literal("-关键帧"), b -> deleteSelectedKeyframe())
                .bounds(tx, ty, 48, 14).build());
        tx += 50;
        addRenderableWidget(Button.builder(Component.literal("循环:" + (template.loop ? "开" : "关")), b -> {
            pushUndo(null);
            template.loop = !template.loop;
            rebuildAll();
        }).bounds(tx, ty, 50, 14).build());
        tx += 52;
        EditBox lifetimeBox = new EditBox(font, tx + 38, ty, 40, 14, Component.literal("lifetime"));
        lifetimeBox.setValue(Integer.toString(template.lifetime));
        lifetimeBox.setResponder(s -> {
            try {
                int parsed = Integer.parseInt(s.trim());
                pushUndo(boxTag(lifetimeBox));
                template.lifetime = parsed;
            } catch (NumberFormatException ignored) { }
        });
        addRenderableWidget(lifetimeBox);
        tx += 80;
        // Time-driven preview: free-form rate input (ticks per real second, 20 = in-game)
        EditBox rateBox = new EditBox(font, tx + 56, ty, 40, 14, Component.literal("ticksPerSecond"));
        rateBox.setValue(formatPlayRate(playTicksPerSecond));
        rateBox.setResponder(s -> {
            try {
                float parsed = Float.parseFloat(s.trim());
                if (parsed > 0f && parsed <= 1000f) {
                    // Re-anchor so the new rate applies from the current frame
                    playAnchorPlayhead = playing ? currentPlayTime() : playhead;
                    playAnchorMillis = net.minecraft.Util.getMillis();
                    playTicksPerSecond = parsed;
                }
            } catch (NumberFormatException ignored) { }
        });
        addRenderableWidget(rateBox);

        // ---- Right panel rows ----
        buildRightRows();
        layoutRightRows();
    }

    private int paletteTop() {
        return height - TL_H - PALETTE_H;
    }

    private int paletteRowsTop() {
        return paletteTop() + 12;
    }

    private int paletteVisibleRows() {
        return Math.max(1, (PALETTE_H - 12 - 18) / PALETTE_ROW_H);
    }

    private boolean inPalette(double x, double y) {
        return x >= 0 && x < LEFT_W && y >= paletteRowsTop()
                && y < paletteRowsTop() + paletteVisibleRows() * PALETTE_ROW_H;
    }

    private int libraryBottom() {
        return paletteTop() - 4;
    }

    // ==================== Right panel construction ====================

    private void buildRightRows() {
        HudElement e = selectedElementOrNull();
        if (e == null) {
            buildTemplateRows();
            return;
        }
        if (selectedElements().size() > 1) {
            buildMultiSelectRows();
            return;
        }
        HudKeyframe kf = selectedKeyframeOrNull();
        if (kf != null) {
            buildKeyframeRows(e, kf);
        } else {
            buildElementRows(e);
        }
    }

    /** Multi-selection view: group action + on-screen help. */
    private void buildMultiSelectRows() {
        rightRows.add(new Row("已多选 " + selectedElements().size() + " 个元素", null));
        rightRows.add(new Row("组合", smallButton("成组", this::groupSelected)));
        rightRows.add(new Row("取消多选", smallButton("取消", () -> {
            extraSelected.clear();
            rightScroll = 0;
            rebuildAll();
        })));
        buildHelpRows();
    }

    /** On-screen instructions for selecting / grouping / keyframe dragging. */
    private void buildHelpRows() {
        rightRows.add(new Row("== 操作说明 ==", null));
        rightRows.add(new Row("Ctrl/Shift+点击: 多选", null));
        rightRows.add(new Row("G: 多选成组", null));
        rightRows.add(new Row("U: 解组", null));
        rightRows.add(new Row("Alt+点击: 切换重叠元素", null));
        rightRows.add(new Row("双击组合: 进入组内编辑", null));
        rightRows.add(new Row("Backspace: 返回上层", null));
        rightRows.add(new Row("Delete: 删除所选", null));
        rightRows.add(new Row("播放头停在关键帧上时", null));
        rightRows.add(new Row("  拖动只改该关键帧", null));
        rightRows.add(new Row("金边框: 数据源已连通", null));
        rightRows.add(new Row("红边框: 数据源不可用", null));
    }

    /** Template-level settings, shown when no element is selected. */
    private void buildTemplateRows() {
        rightRows.add(new Row("未选中元素", null));
        rightRows.add(new Row("点击画布选择", null));
        rightRows.add(new Row("== 模板设置 ==", null));
        rightRows.add(new Row("类型", cycleButton(template.isGui() ? "GUI(可交互)" : "HUD(显示)", () -> {
            template.screenType = template.isGui() ? "hud" : "gui";
            rebuildAll();
        })));
        if (template.isGui()) {
            rightRows.add(new Row("暂停游戏", cycleButton(template.pauseGame ? "是" : "否", () -> {
                template.pauseGame = !template.pauseGame;
                rebuildAll();
            })));
            rightRows.add(new Row("背景模糊", cycleButton(template.blurBackground ? "开" : "关", () -> {
                template.blurBackground = !template.blurBackground;
                rebuildAll();
            })));
            rightRows.add(new Row("开启键", cycleButton(guiKeyLabel(template.openKey), () -> cycleGuiKey(true))));
            if (template.openKey != null) {
                buildGuiKeyRows(template.openKey);
            }
            rightRows.add(new Row("关闭键", cycleButton(guiKeyLabel(template.closeKey), () -> cycleGuiKey(false))));
            if (template.closeKey != null) {
                buildGuiKeyRows(template.closeKey);
            }
        }
        buildHelpRows();
    }

    private static String guiKeyLabel(@Nullable com.lootmatrix.customui.hud.HudGuiKey k) {
        if (k == null) return "无";
        return k.interactKey == 0 ? "模板默认键" : "交互键" + k.interactKey;
    }

    /** Cycle: 无 → 交互键1 → 2 → 3 → 模板默认键 → 无. */
    private void cycleGuiKey(boolean open) {
        com.lootmatrix.customui.hud.HudGuiKey k = open ? template.openKey : template.closeKey;
        if (k == null) {
            k = new com.lootmatrix.customui.hud.HudGuiKey();
            k.interactKey = 1;
        } else if (k.interactKey >= 1 && k.interactKey < 3) {
            k.interactKey++;
        } else if (k.interactKey == 3) {
            k.interactKey = 0; // fall back to the template's raw default key
        } else {
            k = null;
        }
        if (open) template.openKey = k; else template.closeKey = k;
        rebuildAll();
    }

    private void buildGuiKeyRows(com.lootmatrix.customui.hud.HudGuiKey k) {
        rightRows.add(new Row(" 默认键名", textRow(k.key, s -> k.key = s)));
        rightRows.add(new Row(" 翻译键", textRow(k.translation, s -> k.translation = s)));
        rightRows.add(new Row(" 动作名", textRow(k.name, s -> k.name = s)));
        rightRows.add(new Row(" 触发函数", textRow(k.function == null ? "" : k.function,
                s -> k.function = s.isEmpty() ? null : s)));
    }

    /** GUI interaction rows (condition, close button, onClick / onClickFail). */
    private void buildInteractionRows(HudElement e) {
        if (!template.isGui()) return;
        rightRows.add(new Row("== GUI 交互 ==", null));
        rightRows.add(new Row("条件", textRow(e.condition == null ? "" : e.condition,
                s -> e.condition = s.isEmpty() ? null : s)));
        rightRows.add(new Row("关闭按钮", cycleButton(e.closeButton ? "是" : "否", () -> {
            e.closeButton = !e.closeButton;
            rebuildAll();
        })));
        rightRows.add(new Row("成功交互", cycleButton(e.onClick != null ? "已配置" : "无", () -> {
            e.onClick = e.onClick == null ? new com.lootmatrix.customui.hud.HudInteraction() : null;
            rebuildAll();
        })));
        if (e.onClick != null) {
            buildInteractionDetailRows(e.onClick, "成.");
        }
        rightRows.add(new Row("失败交互", cycleButton(e.onClickFail != null ? "已配置" : "无", () -> {
            e.onClickFail = e.onClickFail == null ? new com.lootmatrix.customui.hud.HudInteraction() : null;
            rebuildAll();
        })));
        if (e.onClickFail != null) {
            buildInteractionDetailRows(e.onClickFail, "败.");
        }
    }

    private void buildInteractionDetailRows(com.lootmatrix.customui.hud.HudInteraction a, String prefix) {
        rightRows.add(new Row(prefix + "函数", textRow(a.function == null ? "" : a.function,
                s -> a.function = s.isEmpty() ? null : s)));
        rightRows.add(new Row(prefix + "音效", textRow(a.sound == null ? "" : a.sound,
                s -> a.sound = s.isEmpty() ? null : s)));
        rightRows.add(new Row(prefix + "动画元素id", textRow(a.anim == null ? "" : a.anim,
                s -> a.anim = s.isEmpty() ? null : s)));
        rightRows.add(new Row(prefix + "打开模板", textRow(a.openTemplate == null ? "" : a.openTemplate,
                s -> a.openTemplate = s.isEmpty() ? null : s)));
        rightRows.add(new Row(prefix + "点击后关闭", cycleButton(a.close ? "是" : "否", () -> {
            a.close = !a.close;
            rebuildAll();
        })));
        rightRows.add(new Row(prefix + "改色目标id", textRow(
                a.setColor.isEmpty() ? "" : a.setColor.get(0).target, s -> {
                    if (s.isEmpty()) {
                        a.setColor.clear();
                    } else if (a.setColor.isEmpty()) {
                        com.lootmatrix.customui.hud.HudInteraction.SetColor sc =
                                new com.lootmatrix.customui.hud.HudInteraction.SetColor();
                        sc.target = s;
                        a.setColor.add(sc);
                    } else {
                        a.setColor.get(0).target = s;
                    }
                })));
        if (!a.setColor.isEmpty()) {
            com.lootmatrix.customui.hud.HudInteraction.SetColor sc = a.setColor.get(0);
            rightRows.add(new Row(prefix + "改色fill", colorRow(sc.fill != null ? sc.fill : 0xFFFFFFFF,
                    v -> sc.fill = v)));
            rightRows.add(new Row(prefix + "改色文本色", colorRow(sc.textColor != null ? sc.textColor : 0xFFFFFFFF,
                    v -> sc.textColor = v)));
        }
    }

    private void buildElementRows(HudElement e) {
        int index = selectedElement;
        rightRows.add(new Row("元素 " + (index + 1) + "/" + editList().size() + " [" + e.type + "]",
                smallButton("切换", () -> {
                    selectedElement = (selectedElement + 1) % Math.max(1, editList().size());
                    selectedKeyframe = -1;
                    extraSelected.clear();
                    rightScroll = 0;
                    rebuildAll();
                })));
        rightRows.add(new Row("删除元素", smallButton("删除", () -> {
            pushUndo(null);
            editList().remove(e);
            markEditOrderDirty();
            selectedElement = -1;
            selectedKeyframe = -1;
            extraSelected.clear();
            rightScroll = 0;
            rebuildAll();
        })));
        rightRows.add(new Row("复制元素", smallButton("复制", this::duplicateSelected)));
        rightRows.add(new Row("id", textRow(e.id, s -> e.id = s)));
        rightRows.add(new Row("x", floatRow(e.x, v -> e.x = v)));
        rightRows.add(new Row("y", floatRow(e.y, v -> e.y = v)));
        rightRows.add(new Row("宽", floatRow(e.w, v -> e.w = v)));
        rightRows.add(new Row("高", floatRow(e.h, v -> e.h = v)));
        rightRows.add(new Row("缩放", floatRow(e.scale, v -> e.scale = v)));
        rightRows.add(new Row("旋转", floatRow(e.rotation, v -> e.rotation = v)));
        rightRows.add(new Row("透明度", floatRow(e.opacity, v -> e.opacity = v)));
        rightRows.add(new Row("层级z", intRow(e.zIndex, v -> {
            e.zIndex = v;
            markEditOrderDirty();
        })));
        rightRows.add(new Row("锚点", cycleButton(e.anchor.toJsonName(), () -> {
            e.anchor = HudAnchor.byId((e.anchor.ordinal() + 1) % HudAnchor.values().length);
            rebuildAll();
        })));
        rightRows.add(new Row("原点", cycleButton(e.origin.toJsonName(), () -> {
            e.origin = HudAnchor.byId((e.origin.ordinal() + 1) % HudAnchor.values().length);
            rebuildAll();
        })));
        rightRows.add(new Row("可见", cycleButton(e.visible ? "显示" : "隐藏", () -> {
            e.visible = !e.visible;
            rebuildAll();
        })));
        if (styleCount(e.type) > 0) {
            rightRows.add(new Row("样式预设", cycleButton(styleLabel(e), () -> cycleStylePreset(e))));
        }

        switch (e.type) {
            case TEXT -> {
                rightRows.add(new Row("文本/JSON", textRow(e.text == null ? "" : e.text, s -> {
                    e.text = s.isEmpty() ? null : s;
                    HudElementRuntime.invalidate(e);
                })));
                rightRows.add(new Row("字号", intRow(e.fontSize, v -> e.fontSize = Math.max(1, v))));
                rightRows.add(new Row("颜色", colorRow(e.textColor, v -> e.textColor = v)));
                rightRows.add(new Row("对齐", cycleButton(e.textAlign, () -> {
                    e.textAlign = switch (e.textAlign) {
                        case "left" -> "center";
                        case "center" -> "right";
                        default -> "left";
                    };
                    rebuildAll();
                })));
                rightRows.add(new Row("阴影", cycleButton(e.textShadow ? "开" : "关", () -> {
                    e.textShadow = !e.textShadow;
                    rebuildAll();
                })));
                rightRows.add(new Row("自适应缩放", cycleButton(e.autoFit ? "开" : "关", () -> {
                    e.autoFit = !e.autoFit;
                    rebuildAll();
                })));
                rightRows.add(new Row("数据源", dataSourceRow(e)));
            }
            case IMAGE -> {
                rightRows.add(new Row("纹理", textureRow(e)));
                rightRows.add(new Row("纹理宽", intRow(e.texW, v -> e.texW = Math.max(1, v))));
                rightRows.add(new Row("纹理高", intRow(e.texH, v -> e.texH = Math.max(1, v))));
                rightRows.add(new Row("u0", floatRow(e.u0, v -> e.u0 = v)));
                rightRows.add(new Row("v0", floatRow(e.v0, v -> e.v0 = v)));
                rightRows.add(new Row("u1", floatRow(e.u1, v -> e.u1 = v)));
                rightRows.add(new Row("v1", floatRow(e.v1, v -> e.v1 = v)));
            }
            case RECT -> rightRows.add(new Row("填充色", colorRow(e.fillColor, v -> e.fillColor = v)));
            case CIRCLE -> {
                rightRows.add(new Row("填充色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("环厚度(0实心)", floatRow(e.ringThickness, v -> e.ringThickness = Math.max(0f, v))));
            }
            case TRIANGLE -> {
                rightRows.add(new Row("填充色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("朝向", cycleButton(e.pointDirection, () -> {
                    e.pointDirection = switch (e.pointDirection) {
                        case "up" -> "down";
                        case "down" -> "left";
                        case "left" -> "right";
                        default -> "up";
                    };
                    rebuildAll();
                })));
            }
            case BORDER -> {
                rightRows.add(new Row("边框色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("线厚度", floatRow(e.borderThickness, v -> e.borderThickness = Math.max(0.5f, v))));
            }
            case LINE -> {
                rightRows.add(new Row("颜色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("线厚度", floatRow(e.lineThickness, v -> e.lineThickness = Math.max(0.5f, v))));
            }
            case ROUNDED_RECT -> {
                rightRows.add(new Row("填充色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("圆角半径", floatRow(e.cornerRadius, v -> e.cornerRadius = Math.max(0f, v))));
            }
            case GRADIENT_RECT -> {
                rightRows.add(new Row("起始色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("结束色", colorRow(e.fillColor2, v -> e.fillColor2 = v)));
                rightRows.add(new Row("方向", cycleButton(e.direction, () -> {
                    e.direction = "vertical".equals(e.direction) ? "horizontal" : "vertical";
                    rebuildAll();
                })));
            }
            case ARC -> {
                rightRows.add(new Row("填充色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("起始角°", floatRow(e.arcStart, v -> e.arcStart = v)));
                rightRows.add(new Row("扫过角°", floatRow(e.arcSweep, v -> e.arcSweep = v)));
                rightRows.add(new Row("环厚度(0实心)", floatRow(e.ringThickness, v -> e.ringThickness = Math.max(0f, v))));
            }
            case PROGRESS -> {
                rightRows.add(new Row("值", floatRow(e.value, v -> e.value = v)));
                rightRows.add(new Row("最大值", floatRow(e.max, v -> e.max = v)));
                rightRows.add(new Row("条颜色", colorRow(e.barColor, v -> e.barColor = v)));
                rightRows.add(new Row("底颜色", colorRow(e.bgColor, v -> e.bgColor = v)));
                rightRows.add(new Row("方向", cycleButton(progressDirLabel(e.direction), () -> {
                    e.direction = switch (e.direction) {
                        case "horizontal" -> "vertical";
                        case "vertical" -> "pie";
                        case "pie" -> "ring";
                        default -> "horizontal";
                    };
                    rebuildAll();
                })));
                if ("ring".equals(e.direction)) {
                    rightRows.add(new Row("环厚度(0自动)", floatRow(e.ringThickness,
                            v -> e.ringThickness = Math.max(0f, v))));
                }
                rightRows.add(new Row("数据源", dataSourceRow(e)));
                rightRows.add(new Row("平滑时间(s)", floatRow(e.progressSmoothSpeed,
                        v -> e.progressSmoothSpeed = Math.max(0f, Math.min(10f, v)))));
            }
            case TEMPLATE -> rightRows.add(new Row("引用模板", textRow(
                    e.templateRef == null ? "" : e.templateRef, s -> {
                        e.templateRef = s.isEmpty() ? null : s;
                        HudElementRuntime.invalidate(e);
                    })));
            case GROUP -> {
                rightRows.add(new Row("子元素 ×" + e.children.size(), smallButton("进入编辑", () -> enterGroup(e))));
                rightRows.add(new Row("解组", smallButton("解组(U)", this::ungroupSelected)));
            }
            case STAT -> {
                rightRows.add(new Row("指标", cycleButton(statSourceLabel(e.statSource), () -> {
                    e.statSource = switch (e.statSource) {
                        case "fps" -> "frame_time";
                        case "frame_time" -> "ping";
                        case "ping" -> "packet_loss";
                        case "packet_loss" -> "jitter";
                        case "jitter" -> "tps";
                        default -> "fps";
                    };
                    HudElementRuntime.invalidate(e);
                    rebuildAll();
                })));
                rightRows.add(new Row("显示", cycleButton(statDisplayLabel(e.statDisplay), () -> {
                    e.statDisplay = switch (e.statDisplay) {
                        case "both" -> "value";
                        case "value" -> "chart";
                        default -> "both";
                    };
                    rebuildAll();
                })));
                rightRows.add(new Row("采样窗口", intRow(e.statWindow,
                        v -> e.statWindow = Math.max(2, Math.min(240, v)))));
                rightRows.add(new Row("折线颜色", colorRow(e.fillColor, v -> e.fillColor = v)));
                rightRows.add(new Row("底色", colorRow(e.bgColor, v -> e.bgColor = v)));
                rightRows.add(new Row("线宽", floatRow(e.lineThickness,
                        v -> e.lineThickness = Math.max(0.5f, v))));
                rightRows.add(new Row("数值颜色", colorRow(e.textColor, v -> e.textColor = v)));
                rightRows.add(new Row("字号", intRow(e.fontSize,
                        v -> e.fontSize = Math.max(4, Math.min(32, v)))));
                rightRows.add(new Row("数值对齐", cycleButton(e.textAlign, () -> {
                    e.textAlign = switch (e.textAlign) {
                        case "left" -> "center";
                        case "center" -> "right";
                        default -> "left";
                    };
                    rebuildAll();
                })));
            }
        }

        buildInteractionRows(e);

        // ---- 3D projection ----
        rightRows.add(new Row("== 3D 投影 ==", cycleButton(e.hasProjection ? "启用" : "禁用", () -> {
            e.hasProjection = !e.hasProjection;
            rebuildAll();
        })));
        if (e.hasProjection) {
            rightRows.add(new Row("世界X", doubleRow(e.worldX, v -> setStaticWorldCoordinate(e, 0, v))));
            rightRows.add(new Row("世界Y", doubleRow(e.worldY, v -> setStaticWorldCoordinate(e, 1, v))));
            rightRows.add(new Row("世界Z", doubleRow(e.worldZ, v -> setStaticWorldCoordinate(e, 2, v))));
            rightRows.add(new Row("动态锚点", textRow(e.worldAnchor == null ? "" : e.worldAnchor, s -> {
                e.worldAnchor = s.isEmpty() ? null : s;
                HudElementRuntime.invalidate(e);
            })));
            rightRows.add(new Row("隔墙可见", cycleButton(e.throughWalls ? "是" : "否", () -> {
                e.throughWalls = !e.throughWalls;
                rebuildAll();
            })));
            rightRows.add(new Row("边缘钳制", cycleButton(e.edgeClamp ? "是" : "否", () -> {
                e.edgeClamp = !e.edgeClamp;
                rebuildAll();
            })));
            if (e.edgeClamp) {
                rightRows.add(new Row("屏外边距", floatRow(e.edgeClampPadding,
                        v -> e.edgeClampPadding = Math.max(0f, v))));
                rightRows.add(new Row("屏外形状", cycleButton(clampShapeLabel(e.edgeClampShape), () -> {
                    e.edgeClampShape = switch (e.edgeClampShape) {
                        case "rect" -> "ellipse";
                        case "ellipse" -> "circle";
                        default -> "rect";
                    };
                    rebuildAll();
                })));
                rightRows.add(new Row("指向箭头", cycleButton(e.edgeArrowEnabled ? "开" : "关", () -> {
                    e.edgeArrowEnabled = !e.edgeArrowEnabled;
                    rebuildAll();
                })));
                if (e.edgeArrowEnabled) {
                    rightRows.add(new Row("箭头大小", floatRow(e.edgeArrowSize,
                            v -> e.edgeArrowSize = Math.max(2f, v))));
                    rightRows.add(new Row("箭头颜色", colorRow(e.edgeArrowColor, v -> e.edgeArrowColor = v)));
                }
            }
            rightRows.add(new Row("距离缩放", cycleButton(e.distanceScaleEnabled ? "开" : "关", () -> {
                e.distanceScaleEnabled = !e.distanceScaleEnabled;
                rebuildAll();
            })));
            if (e.distanceScaleEnabled) {
                rightRows.add(new Row("基准距离", floatRow(e.distanceRef, v -> e.distanceRef = v)));
                rightRows.add(new Row("最小缩放", floatRow(e.distanceMinScale, v -> e.distanceMinScale = v)));
                rightRows.add(new Row("最大缩放", floatRow(e.distanceMaxScale, v -> e.distanceMaxScale = v)));
            }
            rightRows.add(new Row("瞄准淡出", cycleButton(e.aimFadeEnabled ? "开" : "关", () -> {
                e.aimFadeEnabled = !e.aimFadeEnabled;
                rebuildAll();
            })));
            if (e.aimFadeEnabled) {
                rightRows.add(new Row("内角°", floatRow(e.aimInnerAngle, v -> e.aimInnerAngle = v)));
                rightRows.add(new Row("外角°", floatRow(e.aimOuterAngle, v -> e.aimOuterAngle = v)));
                rightRows.add(new Row("最低不透明", floatRow(e.aimMinOpacity, v -> e.aimMinOpacity = v)));
            }
        }
    }

    private void buildKeyframeRows(HudElement e, HudKeyframe kf) {
        rightRows.add(new Row("关键帧 @" + kf.tick + "t", smallButton("返回元素", () -> {
            selectedKeyframe = -1;
            rightScroll = 0;
            rebuildAll();
        })));
        rightRows.add(new Row("刻", intRow(kf.tick, v -> {
            kf.tick = Math.max(0, v);
            e.compileTracks();
        })));
        rightRows.add(new Row("默认曲线", cycleButton(kf.easing.toJsonName(), () -> {
            kf.easing = HudEasing.byId((kf.easing.ordinal() + 1) % HudEasing.values().length);
            e.compileTracks();
            rebuildAll();
        })));
        rightRows.add(new Row("空间2D/3D", cycleButton(spaceLabel(kf), () -> {
            Float v = kf.get(HudKeyframe.PROP_SPACE);
            if (v == null) kf.set(HudKeyframe.PROP_SPACE, 0f);
            else if (v < 0.5f) kf.set(HudKeyframe.PROP_SPACE, 1f);
            else kf.set(HudKeyframe.PROP_SPACE, null);
            e.compileTracks();
            rebuildAll();
        })));
        for (int p = 0; p < HudKeyframe.PROP_COUNT; p++) {
            if (p == HudKeyframe.PROP_SPACE) continue; // handled by the toggle above
            final int prop = p;
            rightRows.add(new Row(HudKeyframe.PROP_NAMES[p], keyframeValueRow(e, kf, prop)));
            rightRows.add(new Row("  ↳曲线", cycleButton(easingOverrideLabel(kf, prop), () -> {
                HudEasing current = kf.easingOverrides[prop];
                if (current == null) {
                    kf.easingOverrides[prop] = HudEasing.LINEAR;
                } else {
                    int next = current.ordinal() + 1;
                    kf.easingOverrides[prop] = next >= HudEasing.values().length ? null : HudEasing.byId(next);
                }
                e.compileTracks();
                rebuildAll();
            })));
        }
    }

    private static String spaceLabel(HudKeyframe kf) {
        Float v = kf.get(HudKeyframe.PROP_SPACE);
        if (v == null) return "未设置";
        return v >= 0.5f ? "3D" : "2D";
    }

    private static String easingOverrideLabel(HudKeyframe kf, int prop) {
        HudEasing e = kf.easingOverrides[prop];
        return e == null ? "默认" : e.toJsonName();
    }

    static void setStaticWorldCoordinate(HudElement e, int axis, double value) {
        switch (axis) {
            case 0 -> e.worldX = value;
            case 1 -> e.worldY = value;
            case 2 -> e.worldZ = value;
            default -> throw new IllegalArgumentException("axis");
        }
        if (e.worldAnchor != null && !e.worldAnchor.isEmpty()) {
            e.worldAnchor = null;
            HudElementRuntime.invalidate(e);
        }
    }

    // ---- row widget factories ----

    private int rightX() {
        return width - RIGHT_W;
    }

    /** Label column width; grows when the panel is dragged wider. */
    private int rightLabelWidth() {
        return Math.max(RIGHT_LABEL_MIN, (RIGHT_W - RIGHT_PAD * 2) * 15 / 32);
    }

    private int rightFieldX() {
        return rightX() + RIGHT_PAD + rightLabelWidth() + RIGHT_FIELD_GAP;
    }

    private int rightFieldWidth() {
        return Math.max(32, RIGHT_W - RIGHT_PAD * 2 - rightLabelWidth() - RIGHT_FIELD_GAP);
    }

    private static String boxTag(EditBox box) {
        return "box:" + System.identityHashCode(box);
    }

    private EditBox dataSourceRow(HudElement e) {
        EditBox box = textRow(e.dataSource == null ? "" : e.dataSource, s -> {
            e.dataSource = s.isEmpty() ? null : s;
            HudElementRuntime.invalidate(e);
        });
        box.setHint(Component.literal("entity:@e[tag=boss,limit=1]:name"));
        return box;
    }

    private EditBox textRow(String value, Consumer<String> setter) {
        EditBox box = new EditBox(font, rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H, Component.empty());
        box.setMaxLength(4096);
        box.setValue(value);
        box.setResponder(s -> {
            pushUndo(boxTag(box));
            setter.accept(s);
        });
        bigEditableBoxes.add(box);
        return box;
    }

    /** Texture path row with resource-pack autocomplete popup. */
    private EditBox textureRow(HudElement e) {
        EditBox box = textRow(e.texture == null ? "" : e.texture, s -> {
            e.texture = s.isEmpty() ? null : s;
            HudElementRuntime.invalidate(e);
            updateTextureSuggestions(s);
        });
        textureBox = box;
        return box;
    }

    private EditBox floatRow(float value, Consumer<Float> setter) {
        EditBox box = new EditBox(font, rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H, Component.empty());
        box.setMaxLength(32);
        box.setValue(trimFloat(value));
        box.setResponder(s -> {
            try {
                float parsed = Float.parseFloat(s.trim());
                pushUndo(boxTag(box));
                setter.accept(parsed);
            } catch (NumberFormatException ignored) { }
        });
        return box;
    }

    private EditBox doubleRow(double value, Consumer<Double> setter) {
        EditBox box = new EditBox(font, rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H, Component.empty());
        box.setMaxLength(32);
        box.setValue(trimFloat((float) value));
        box.setResponder(s -> {
            try {
                double parsed = Double.parseDouble(s.trim());
                pushUndo(boxTag(box));
                setter.accept(parsed);
            } catch (NumberFormatException ignored) { }
        });
        return box;
    }

    private EditBox intRow(int value, Consumer<Integer> setter) {
        EditBox box = new EditBox(font, rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H, Component.empty());
        box.setMaxLength(16);
        box.setValue(Integer.toString(value));
        box.setResponder(s -> {
            try {
                int parsed = Integer.parseInt(s.trim());
                pushUndo(boxTag(box));
                setter.accept(parsed);
            } catch (NumberFormatException ignored) { }
        });
        return box;
    }

    private EditBox colorRow(int argb, Consumer<Integer> setter) {
        EditBox box = new EditBox(font, rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H, Component.empty());
        box.setMaxLength(16);
        box.setValue(HudElement.hexColor(argb));
        box.setResponder(s -> {
            Integer parsed = parseHexColor(s);
            if (parsed != null) {
                pushUndo(boxTag(box));
                setter.accept(parsed);
            }
        });
        return box;
    }

    private EditBox keyframeValueRow(HudElement e, HudKeyframe kf, int prop) {
        Float value = kf.get(prop);
        EditBox box = new EditBox(font, rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H, Component.empty());
        box.setMaxLength(32);
        box.setValue(value == null ? "" : trimFloat(value));
        box.setResponder(s -> {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                pushUndo(boxTag(box));
                kf.set(prop, null);
            } else {
                try {
                    float parsed = Float.parseFloat(trimmed);
                    pushUndo(boxTag(box));
                    kf.set(prop, parsed);
                } catch (NumberFormatException ignored) {
                    return;
                }
            }
            e.compileTracks();
        });
        return box;
    }

    private Button smallButton(String label, Runnable action) {
        return Button.builder(Component.literal(label), b -> action.run())
                .bounds(rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H).build();
    }

    /** Cycle buttons always mutate the template; each click is one undo step. */
    private Button cycleButton(String value, Runnable action) {
        return Button.builder(Component.literal(value), b -> {
            pushUndo(null);
            action.run();
        }).bounds(rightFieldX(), 0, rightFieldWidth(), RIGHT_FIELD_H).build();
    }

    private void layoutRightRows() {
        int baseY = TOP_H + 4;
        int fieldW = rightFieldWidth();
        int fieldX = rightFieldX();
        for (int i = 0; i < rightRows.size(); i++) {
            AbstractWidget widget = rightRows.get(i).widget;
            if (widget == null) continue;
            int y = baseY + (i - rightScroll) * ROW_H;
            widget.setX(fieldX);
            widget.setY(y);
            widget.setWidth(fieldW);
            widget.setHeight(RIGHT_FIELD_H);
            boolean visible = i >= rightScroll && y + RIGHT_FIELD_H <= height - TL_H - 2;
            widget.visible = visible;
            widget.active = visible;
            addRenderableWidget(widget);
        }
    }

    // ==================== Texture autocomplete ====================

    private void updateTextureSuggestions(String query) {
        textureSuggestions.clear();
        textureSuggestionSelected = -1;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        for (int i = 0; i < textureIndex.size(); i++) {
            String path = textureIndex.get(i);
            if (path.contains(q) && !path.equals(q)) {
                textureSuggestions.add(path);
                if (textureSuggestions.size() >= SUGGESTION_LIMIT) break;
            }
        }
    }

    private boolean textureSuggestionsVisible() {
        return textureBox != null && textureBox.isFocused() && !textureSuggestions.isEmpty();
    }

    private int suggestionPopupWidth() {
        int w = 60;
        for (int i = 0; i < textureSuggestions.size(); i++) {
            w = Math.max(w, font.width(textureSuggestions.get(i)));
        }
        return w + 8;
    }

    private int suggestionPopupX() {
        return Math.max(2, Math.min(textureBox.getX(), width - suggestionPopupWidth() - 2));
    }

    private int suggestionPopupY() {
        return textureBox.getY() + 15;
    }

    private void applyTextureSuggestion(String path) {
        if (textureBox != null) {
            textureBox.setValue(path); // responder updates the element
        }
        textureSuggestions.clear();
        textureSuggestionSelected = -1;
    }

    private boolean textureSuggestionKeyPressed(int keyCode) {
        if (!textureSuggestionsVisible()) return false;
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN -> {
                textureSuggestionSelected = (textureSuggestionSelected + 1) % textureSuggestions.size();
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                textureSuggestionSelected =
                        (textureSuggestionSelected - 1 + textureSuggestions.size()) % textureSuggestions.size();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> {
                applyTextureSuggestion(textureSuggestions.get(Math.max(0, textureSuggestionSelected)));
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                textureSuggestions.clear();
                textureSuggestionSelected = -1;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean handleTextureSuggestionClick(double mouseX, double mouseY, int button) {
        if (button != 0 || !textureSuggestionsVisible()) return false;
        int x = suggestionPopupX();
        int y = suggestionPopupY();
        int w = suggestionPopupWidth();
        int h = textureSuggestions.size() * SUGGESTION_ROW_H + 2;
        if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) return false;
        int idx = (int) ((mouseY - y - 1) / SUGGESTION_ROW_H);
        if (idx >= 0 && idx < textureSuggestions.size()) {
            applyTextureSuggestion(textureSuggestions.get(idx));
        }
        return true;
    }

    private void renderTextureSuggestions(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!textureSuggestionsVisible()) return;
        int x = suggestionPopupX();
        int y = suggestionPopupY();
        int w = suggestionPopupWidth();
        int h = textureSuggestions.size() * SUGGESTION_ROW_H + 2;
        graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, PANEL_EDGE);
        graphics.fill(x, y, x + w, y + h, 0xF8101820);
        for (int i = 0; i < textureSuggestions.size(); i++) {
            int rowY = y + 1 + i * SUGGESTION_ROW_H;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + SUGGESTION_ROW_H;
            if (i == textureSuggestionSelected || hovered) {
                graphics.fill(x, rowY, x + w, rowY + SUGGESTION_ROW_H, 0x6033B5E5);
            }
            graphics.drawString(font, font.plainSubstrByWidth(textureSuggestions.get(i), w - 6),
                    x + 3, rowY + 1, 0xFFD8E1E8);
        }
    }

    // ==================== Big text edit overlay ====================

    private void openBigEdit(EditBox target) {
        bigEditTarget = target;
        int w = Math.min(width - 40, 380);
        int h = Math.min(height - 90, 150);
        MultiLineEditBox box = new MultiLineEditBox(font, (width - w) / 2, (height - h) / 2, w, h,
                Component.literal("在此编辑长文本"), Component.literal("大文本编辑"));
        box.setCharacterLimit(4096);
        box.setValue(target.getValue());
        box.setFocused(true);
        bigEditBox = box;
    }

    private void closeBigEdit(boolean apply) {
        if (apply && bigEditBox != null && bigEditTarget != null) {
            bigEditTarget.setValue(bigEditBox.getValue()); // responder updates the element
        }
        bigEditBox = null;
        bigEditTarget = null;
    }

    private int bigEditButtonY() {
        return bigEditBox.getY() + bigEditBox.getHeight() + 4;
    }

    private int bigEditConfirmX() {
        return bigEditBox.getX() + bigEditBox.getWidth() - 92;
    }

    private int bigEditCancelX() {
        return bigEditBox.getX() + bigEditBox.getWidth() - 44;
    }

    private boolean handleBigEditClick(double mouseX, double mouseY, int button) {
        if (bigEditBox == null) return false;
        if (button == 0) {
            int by = bigEditButtonY();
            if (mouseY >= by && mouseY < by + 14) {
                if (mouseX >= bigEditConfirmX() && mouseX < bigEditConfirmX() + 44) {
                    closeBigEdit(true);
                    return true;
                }
                if (mouseX >= bigEditCancelX() && mouseX < bigEditCancelX() + 44) {
                    closeBigEdit(false);
                    return true;
                }
            }
        }
        bigEditBox.mouseClicked(mouseX, mouseY, button);
        return true;
    }

    private void renderBigEdit(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (bigEditBox == null) return;
        graphics.fill(0, 0, width, height, 0xA0000008);
        int x = bigEditBox.getX();
        int y = bigEditBox.getY();
        int w = bigEditBox.getWidth();
        int h = bigEditBox.getHeight();
        graphics.fill(x - 6, y - 18, x + w + 6, y + h + 22, PANEL_BG);
        graphics.fill(x - 6, y - 18, x + w + 6, y - 17, PANEL_EDGE);
        graphics.drawString(font, "长文本编辑  (Ctrl+Enter 确认 / Esc 取消)", x, y - 12, TEXT_DIM);
        bigEditBox.render(graphics, mouseX, mouseY, partialTick);
        renderOverlayButton(graphics, bigEditConfirmX(), bigEditButtonY(), 44, "确认", mouseX, mouseY);
        renderOverlayButton(graphics, bigEditCancelX(), bigEditButtonY(), 44, "取消", mouseX, mouseY);
    }

    private void renderOverlayButton(GuiGraphics graphics, int x, int y, int w, String label,
                                     int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14;
        graphics.fill(x, y, x + w, y + 14, hovered ? 0xFF2E4A5C : 0xFF22303A);
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, 0xFFE6EDF3);
    }

    // ==================== Actions ====================

    private void newTemplate() {
        template = new HudTemplate();
        idText = "customui:new_template";
        template.id = idText;
        selectedElement = -1;
        selectedKeyframe = -1;
        groupPath.clear();
        extraSelected.clear();
        stylePresetIndex.clear();
        playhead = 0f;
        playing = false;
        rightScroll = 0;
        markCurrentTemplateSnapshotSaved();
        rebuildAll();
        setStatus("已创建空白模板");
    }

    private void loadSelectedLibrary() {
        if (libSelected < 0 || libSelected >= libraryIds.size()) {
            setStatus("先在左侧选择一个模板");
            return;
        }
        loadTemplate(libraryIds.get(libSelected));
    }

    /** Delete the selected library template after an explicit confirm dialog. */
    private void deleteSelectedLibrary() {
        if (libSelected < 0 || libSelected >= libraryIds.size()) {
            setStatus("先在左侧选择一个模板");
            return;
        }
        String id = libraryIds.get(libSelected);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                ModNetworkHandler.INSTANCE.sendToServer(new HudTemplateDeletePacket(id));
                setStatus("已请求删除模板: " + id);
            }
            minecraft.setScreen(this);
        }, Component.literal("删除模板"),
                Component.literal("确定要删除模板 \"" + id + "\" 吗？此操作不可恢复。")));
    }

    private void loadTemplate(String id) {
        HudTemplate source = HudClientTemplateCache.get(id);
        if (source == null) {
            setStatus("模板不存在: " + id);
            return;
        }
        template = source.copy();
        idText = template.id;
        selectedElement = -1;
        selectedKeyframe = -1;
        groupPath.clear();
        extraSelected.clear();
        stylePresetIndex.clear();
        playhead = 0f;
        playing = false;
        rightScroll = 0;
        markCurrentTemplateSnapshotSaved();
        rebuildAll();
        setStatus("已载入 " + id);
    }

    private void addElement(HudElement.Type type) {
        pushUndo(null);
        List<HudElement> list = editList();
        HudElement e = new HudElement();
        e.type = type;
        e.id = type.name().toLowerCase() + "_" + (list.size() + 1);
        switch (type) {
            case TEXT -> {
                e.text = "新文本";
                e.w = 60;
                e.h = 12;
            }
            case IMAGE -> {
                e.texture = "customui:textures/overlay/generic.png";
                e.w = 32;
                e.h = 32;
            }
            case RECT -> {
                e.w = 60;
                e.h = 20;
            }
            case CIRCLE -> {
                e.w = 32;
                e.h = 32;
                e.fillColor = 0xC0FFFFFF;
            }
            case TRIANGLE -> {
                e.w = 24;
                e.h = 24;
                e.fillColor = 0xC0FFFFFF;
            }
            case BORDER -> {
                e.w = 60;
                e.h = 30;
                e.fillColor = 0xFFFFFFFF;
                e.borderThickness = 1.5f;
            }
            case LINE -> {
                e.w = 60;
                e.h = 8;
                e.fillColor = 0xFFFFFFFF;
            }
            case ROUNDED_RECT -> {
                e.w = 60;
                e.h = 24;
                e.cornerRadius = 6f;
            }
            case GRADIENT_RECT -> {
                e.w = 60;
                e.h = 24;
                e.fillColor = 0xC04FC3F7;
                e.fillColor2 = 0x204FC3F7;
            }
            case ARC -> {
                e.w = 32;
                e.h = 32;
                e.fillColor = 0xC0FFFFFF;
                e.arcSweep = 270f;
                e.ringThickness = 4f;
            }
            case PROGRESS -> {
                e.value = 50;
                e.w = 80;
                e.h = 6;
            }
            case TEMPLATE -> {
                e.w = 100;
                e.h = 60;
            }
            case GROUP -> {
                e.w = 100;
                e.h = 60;
            }
            case STAT -> {
                e.w = 90;
                e.h = 36;
            }
        }
        e.compileTracks();
        list.add(e);
        markEditOrderDirty();
        selectedElement = list.size() - 1;
        selectedKeyframe = -1;
        extraSelected.clear();
        rightScroll = 0;
        rebuildAll();
    }

    // ==================== Prefab controls ====================

    /** Insert a prefab GROUP built in code and select it. */
    private void insertPreset(HudElement group, String status) {
        pushUndo(null);
        group.compileTracks();
        for (HudElement child : group.children) child.compileTracks();
        List<HudElement> list = editList();
        list.add(group);
        markEditOrderDirty();
        selectedElement = list.size() - 1;
        selectedKeyframe = -1;
        extraSelected.clear();
        rightScroll = 0;
        rebuildAll();
        setStatus(status);
    }

    private static HudElement presetChild(HudElement.Type type, String id,
                                          HudAnchor anchor, HudAnchor origin,
                                          float x, float y, float w, float h, int z) {
        HudElement e = new HudElement();
        e.type = type;
        e.id = id;
        e.anchor = anchor;
        e.origin = origin;
        e.x = x; e.y = y; e.w = w; e.h = h;
        e.zIndex = z;
        return e;
    }

    /**
     * 占领点进度面板：左侧为与原版占领 HUD 一致的扇形遮罩圆形进度
     * （12 点钟顺时针），右侧名称 + 细进度条（改 dataSource 换占领点）。
     */
    private void addPresetCaptureProgress() {
        int n = editList().size() + 1;
        HudElement g = presetChild(HudElement.Type.GROUP, "capture_panel_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 24, 112, 44, 0);

        HudElement bg = presetChild(HudElement.Type.ROUNDED_RECT, "cp_bg_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 0, 0, 112, 44, 0);
        bg.fillColor = 0xC8101820;
        bg.cornerRadius = 6f;
        g.children.add(bg);

        HudElement accent = presetChild(HudElement.Type.GRADIENT_RECT, "cp_accent_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 0, 0, 112, 2, 1);
        accent.fillColor = 0xE04FC3F7;
        accent.fillColor2 = 0x004FC3F7;
        accent.direction = "horizontal";
        g.children.add(accent);

        // 圆形扇形遮罩进度（与原版占领点显示一致）
        HudElement pie = presetChild(HudElement.Type.PROGRESS, "cp_pie_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 8, 9, 28, 28, 1);
        pie.direction = "pie";
        pie.dataSource = "capture_zone:zone1";
        pie.barColor = 0x904FC3F7;
        pie.bgColor = 0x60222C33;
        g.children.add(pie);

        HudElement ring = presetChild(HudElement.Type.CIRCLE, "cp_ring_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 7, 8, 30, 30, 2);
        ring.fillColor = 0x90E6EDF3;
        ring.ringThickness = 1.5f;
        g.children.add(ring);

        HudElement letter = presetChild(HudElement.Type.TEXT, "cp_letter_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 8, 9, 28, 28, 3);
        letter.text = "A";
        letter.fontSize = 11;
        letter.textAlign = "center";
        g.children.add(letter);

        HudElement name = presetChild(HudElement.Type.TEXT, "cp_name_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 42, 9, 62, 10, 2);
        name.text = "占领点 A";
        name.fontSize = 8;
        name.textAlign = "left";
        g.children.add(name);

        HudElement bar = presetChild(HudElement.Type.PROGRESS, "cp_bar_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 42, 26, 62, 5, 2);
        bar.dataSource = "capture_zone:zone1";
        bar.barColor = 0xFF4FC3F7;
        bar.bgColor = 0x80222C33;
        g.children.add(bar);

        insertPreset(g, "已添加占领点进度（扇形遮罩与原版一致，数据源 capture_zone:zone1）");
    }

    /** 占领点 3D 标记：圆形扇形遮罩进度 + 指向箭头，worldAnchor 跟随占领区，随距离缩放。 */
    private void addPresetCaptureMarker3D() {
        int n = editList().size() + 1;
        HudElement g = presetChild(HudElement.Type.GROUP, "capture_marker_" + n,
                HudAnchor.CENTER, HudAnchor.CENTER, 0, 0, 44, 58, 0);
        g.hasProjection = true;
        g.worldAnchor = "capture_zone:zone1";
        g.distanceScaleEnabled = true;
        g.distanceRef = 12f;
        g.distanceMinScale = 0.4f;
        g.distanceMaxScale = 1.6f;
        g.edgeClamp = true;

        // 底层暗色圆 + 扇形遮罩进度 + 描边环（原版占领点同款结构）
        HudElement pie = presetChild(HudElement.Type.PROGRESS, "cm_pie_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 2, 32, 32, 1);
        pie.direction = "pie";
        pie.dataSource = "capture_zone:zone1";
        pie.barColor = 0x904FC3F7;
        pie.bgColor = 0x90101820;
        g.children.add(pie);

        HudElement ring = presetChild(HudElement.Type.CIRCLE, "cm_ring_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 0, 36, 36, 2);
        ring.fillColor = 0xC0E6EDF3;
        ring.ringThickness = 2f;
        g.children.add(ring);

        HudElement label = presetChild(HudElement.Type.TEXT, "cm_label_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 2, 32, 32, 3);
        label.text = "A";
        label.fontSize = 12;
        label.textAlign = "center";
        g.children.add(label);

        HudElement pointer = presetChild(HudElement.Type.TRIANGLE, "cm_ptr_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 38, 10, 6, 2);
        pointer.fillColor = 0xC0E6EDF3;
        pointer.pointDirection = "down";
        g.children.add(pointer);

        HudElement bar = presetChild(HudElement.Type.PROGRESS, "cm_bar_" + n,
                HudAnchor.BOTTOM_CENTER, HudAnchor.BOTTOM_CENTER, 0, -2, 40, 4, 1);
        bar.dataSource = "capture_zone:zone1";
        bar.barColor = 0xFF4FC3F7;
        bar.bgColor = 0x90222222;
        g.children.add(bar);

        insertPreset(g, "已添加占领点3D标记（worldAnchor=capture_zone:zone1）");
    }

    /** 双方计分栏：渐变侧翼 + 队伍色底线 + 中央 VS 牌 + 计分板绑定大号分数。 */
    private void addPresetTeamScore() {
        int n = editList().size() + 1;
        HudElement g = presetChild(HudElement.Type.GROUP, "team_score_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 6, 180, 26, 0);

        HudElement red = presetChild(HudElement.Type.GRADIENT_RECT, "ts_red_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 0, 3, 82, 20, 0);
        red.fillColor = 0x10B71C1C;
        red.fillColor2 = 0xD0B71C1C;
        red.direction = "horizontal";
        g.children.add(red);

        HudElement blue = presetChild(HudElement.Type.GRADIENT_RECT, "ts_blue_" + n,
                HudAnchor.TOP_RIGHT, HudAnchor.TOP_RIGHT, 0, 3, 82, 20, 0);
        blue.fillColor = 0xD01565C0;
        blue.fillColor2 = 0x101565C0;
        blue.direction = "horizontal";
        g.children.add(blue);

        // 队伍色底线（亮色描边，强化阵营分界）
        HudElement redLine = presetChild(HudElement.Type.RECT, "ts_red_line_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 0, 23, 82, 2, 1);
        redLine.fillColor = 0xFFFF5252;
        g.children.add(redLine);

        HudElement blueLine = presetChild(HudElement.Type.RECT, "ts_blue_line_" + n,
                HudAnchor.TOP_RIGHT, HudAnchor.TOP_RIGHT, 0, 23, 82, 2, 1);
        blueLine.fillColor = 0xFF448AFF;
        g.children.add(blueLine);

        HudElement redName = presetChild(HudElement.Type.TEXT, "ts_red_name_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 6, 9, 26, 8, 2);
        redName.text = "RED";
        redName.fontSize = 6;
        redName.textAlign = "left";
        redName.textColor = 0xFFFF8A80;
        g.children.add(redName);

        HudElement blueName = presetChild(HudElement.Type.TEXT, "ts_blue_name_" + n,
                HudAnchor.TOP_RIGHT, HudAnchor.TOP_RIGHT, -6, 9, 26, 8, 2);
        blueName.text = "BLUE";
        blueName.fontSize = 6;
        blueName.textAlign = "right";
        blueName.textColor = 0xFF82B1FF;
        g.children.add(blueName);

        HudElement redScore = presetChild(HudElement.Type.TEXT, "ts_red_score_" + n,
                HudAnchor.TOP_LEFT, HudAnchor.TOP_LEFT, 34, 5, 44, 16, 2);
        redScore.text = "{value}";
        redScore.dataSource = "scoreboard:score_red:red";
        redScore.fontSize = 12;
        redScore.textAlign = "center";
        g.children.add(redScore);

        HudElement blueScore = presetChild(HudElement.Type.TEXT, "ts_blue_score_" + n,
                HudAnchor.TOP_RIGHT, HudAnchor.TOP_RIGHT, -34, 5, 44, 16, 2);
        blueScore.text = "{value}";
        blueScore.dataSource = "scoreboard:score_blue:blue";
        blueScore.fontSize = 12;
        blueScore.textAlign = "center";
        g.children.add(blueScore);

        // 中央 VS 牌（圆角深色板 + 高亮文字）
        HudElement mid = presetChild(HudElement.Type.ROUNDED_RECT, "ts_mid_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 0, 28, 26, 3);
        mid.fillColor = 0xF0101820;
        mid.cornerRadius = 4f;
        g.children.add(mid);

        HudElement midEdge = presetChild(HudElement.Type.BORDER, "ts_mid_edge_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 0, 28, 26, 4);
        midEdge.fillColor = 0x60FFC640;
        midEdge.borderThickness = 1f;
        g.children.add(midEdge);

        HudElement vs = presetChild(HudElement.Type.TEXT, "ts_vs_" + n,
                HudAnchor.TOP_CENTER, HudAnchor.TOP_CENTER, 0, 0, 28, 26, 5);
        vs.text = "VS";
        vs.fontSize = 10;
        vs.textAlign = "center";
        vs.textColor = 0xFFFFC640;
        g.children.add(vs);

        insertPreset(g, "已添加双方计分栏（绑定 scoreboard:score_red/score_blue，可改数据源）");
    }

    // ==================== Style presets ====================

    private static String progressDirLabel(String direction) {
        return switch (direction) {
            case "vertical" -> "垂直";
            case "pie" -> "扇形遮罩";
            case "ring" -> "圆环";
            default -> "水平";
        };
    }

    private static String statSourceLabel(String source) {
        return switch (source) {
            case "frame_time" -> "帧生成时间";
            case "ping" -> "网络延迟";
            case "packet_loss" -> "丢包率";
            case "jitter" -> "网络波动";
            case "tps" -> "TPS";
            default -> "FPS";
        };
    }

    private static String statDisplayLabel(String display) {
        return switch (display) {
            case "value" -> "仅数值";
            case "chart" -> "仅图表";
            default -> "数值+图表";
        };
    }

    private static String clampShapeLabel(String shape) {
        return switch (shape) {
            case "ellipse" -> "椭圆";
            case "circle" -> "圆形";
            default -> "矩形";
        };
    }

    private static final String[] STYLE_NAMES_PANEL = {"深色面板", "亮色卡片", "警示红", "霓虹青"};
    private static final String[] STYLE_NAMES_TEXT = {"白色正文", "金色标题", "科技青", "次要灰"};
    private static final String[] STYLE_NAMES_CIRCLE = {"实心白", "细环白", "粗环青", "实心金"};
    private static final String[] STYLE_NAMES_SHAPE = {"白", "金", "红", "青"};
    private static final String[] STYLE_NAMES_BORDER = {"细白", "金色", "粗青", "警示红"};
    private static final String[] STYLE_NAMES_GRADIENT = {"青色渐隐", "金色渐隐", "红色渐隐", "暗幕压底"};
    private static final String[] STYLE_NAMES_ARC = {"白环", "金环", "粗青环", "红色扇形"};
    private static final String[] STYLE_NAMES_PROGRESS = {"经典绿", "科技青", "荣耀金", "警示红"};

    private static String[] styleNames(HudElement.Type type) {
        return switch (type) {
            case RECT, ROUNDED_RECT -> STYLE_NAMES_PANEL;
            case TEXT -> STYLE_NAMES_TEXT;
            case CIRCLE -> STYLE_NAMES_CIRCLE;
            case TRIANGLE, LINE -> STYLE_NAMES_SHAPE;
            case BORDER -> STYLE_NAMES_BORDER;
            case GRADIENT_RECT -> STYLE_NAMES_GRADIENT;
            case ARC -> STYLE_NAMES_ARC;
            case PROGRESS -> STYLE_NAMES_PROGRESS;
            default -> EMPTY_STYLES; // IMAGE/TEMPLATE/GROUP: nothing meaningful to recolor
        };
    }

    private static final String[] EMPTY_STYLES = new String[0];

    private static int styleCount(HudElement.Type type) {
        return styleNames(type).length;
    }

    private String styleLabel(HudElement e) {
        Integer index = stylePresetIndex.get(e);
        return index == null ? "默认(点击切换)" : styleNames(e.type)[index];
    }

    private void cycleStylePreset(HudElement e) {
        int count = styleCount(e.type);
        if (count == 0) return;
        Integer current = stylePresetIndex.get(e);
        int next = current == null ? 0 : (current + 1) % count;
        stylePresetIndex.put(e, next);
        applyStylePreset(e, next);
        HudElementRuntime.invalidate(e);
        rebuildAll();
        setStatus("样式预设: " + styleNames(e.type)[next]);
    }

    /** Curated color/shape-parameter sets; never touches layout or bindings. */
    private static void applyStylePreset(HudElement e, int index) {
        switch (e.type) {
            case RECT, ROUNDED_RECT -> {
                e.fillColor = switch (index) {
                    case 1 -> 0xE0ECEFF1;
                    case 2 -> 0xA0B71C1C;
                    case 3 -> 0x804FC3F7;
                    default -> 0xC8101820;
                };
                if (e.type == HudElement.Type.ROUNDED_RECT) {
                    e.cornerRadius = switch (index) {
                        case 1 -> 6f;
                        case 2 -> 4f;
                        case 3 -> 8f;
                        default -> 5f;
                    };
                }
            }
            case TEXT -> {
                e.textColor = switch (index) {
                    case 1 -> 0xFFFFC640;
                    case 2 -> 0xFF4FC3F7;
                    case 3 -> 0xFF9AA7B0;
                    default -> 0xFFFFFFFF;
                };
                e.textShadow = index != 3;
            }
            case CIRCLE -> {
                switch (index) {
                    case 1 -> { e.fillColor = 0xC0E6EDF3; e.ringThickness = 1.5f; }
                    case 2 -> { e.fillColor = 0xC04FC3F7; e.ringThickness = 4f; }
                    case 3 -> { e.fillColor = 0xC0FFC640; e.ringThickness = 0f; }
                    default -> { e.fillColor = 0xC0E6EDF3; e.ringThickness = 0f; }
                }
            }
            case TRIANGLE, LINE -> {
                int color = switch (index) {
                    case 1 -> 0xC0FFC640;
                    case 2 -> 0xC0FF5252;
                    case 3 -> 0xC04FC3F7;
                    default -> 0xC0E6EDF3;
                };
                e.fillColor = color;
                if (e.type == HudElement.Type.LINE) {
                    e.lineThickness = switch (index) {
                        case 1 -> 1f;
                        case 2 -> 2f;
                        case 3 -> 3f;
                        default -> 2f;
                    };
                }
            }
            case BORDER -> {
                switch (index) {
                    case 1 -> { e.fillColor = 0xFFFFC640; e.borderThickness = 2f; }
                    case 2 -> { e.fillColor = 0xC04FC3F7; e.borderThickness = 3f; }
                    case 3 -> { e.fillColor = 0xE0FF5252; e.borderThickness = 1.5f; }
                    default -> { e.fillColor = 0xFFFFFFFF; e.borderThickness = 1f; }
                }
            }
            case GRADIENT_RECT -> {
                switch (index) {
                    case 1 -> { e.fillColor = 0xE0FFC640; e.fillColor2 = 0x10FFC640; e.direction = "horizontal"; }
                    case 2 -> { e.fillColor = 0xD0B71C1C; e.fillColor2 = 0x10B71C1C; e.direction = "horizontal"; }
                    case 3 -> { e.fillColor = 0x00101820; e.fillColor2 = 0xE0101820; e.direction = "vertical"; }
                    default -> { e.fillColor = 0xE04FC3F7; e.fillColor2 = 0x104FC3F7; e.direction = "horizontal"; }
                }
            }
            case ARC -> {
                switch (index) {
                    case 1 -> { e.fillColor = 0xE0FFC640; e.ringThickness = 2f; }
                    case 2 -> { e.fillColor = 0xC04FC3F7; e.ringThickness = 5f; }
                    case 3 -> { e.fillColor = 0xA0FF5252; e.ringThickness = 0f; }
                    default -> { e.fillColor = 0xC0E6EDF3; e.ringThickness = 3f; }
                }
            }
            case PROGRESS -> {
                switch (index) {
                    case 1 -> { e.barColor = 0xFF4FC3F7; e.bgColor = 0x60162026; }
                    case 2 -> { e.barColor = 0xFFFFC640; e.bgColor = 0x60332B11; }
                    case 3 -> { e.barColor = 0xFFFF5252; e.bgColor = 0x60331111; }
                    default -> { e.barColor = 0xFF44FF44; e.bgColor = 0x80333333; }
                }
            }
            default -> { }
        }
    }

    private void addTemplateElementAt(String refId, double mouseX, double mouseY) {
        pushUndo(null);
        List<HudElement> list = editList();
        float[] rect = editRectScratch;
        editRect(rect);
        HudElement e = new HudElement();
        e.type = HudElement.Type.TEMPLATE;
        e.templateRef = refId;
        e.id = "template_" + (list.size() + 1);
        e.w = 100;
        e.h = 60;
        e.x = (float) (mouseX - rect[0] - rect[2] * e.anchor.fx);
        e.y = (float) (mouseY - rect[1] - rect[3] * e.anchor.fy);
        e.compileTracks();
        list.add(e);
        markEditOrderDirty();
        selectedElement = list.size() - 1;
        selectedKeyframe = -1;
        extraSelected.clear();
        rightScroll = 0;
        rebuildAll();
        setStatus("已嵌入模板 " + refId);
    }

    private void addKeyframeAtPlayhead() {
        HudElement e = selectedElementOrNull();
        if (e == null) {
            setStatus("先选择一个元素");
            return;
        }
        pushUndo(null);
        int tick = Math.round(playhead);
        HudKeyframe target = null;
        for (HudKeyframe kf : e.keyframes) {
            if (kf.tick == tick) {
                target = kf;
                break;
            }
        }
        if (target == null) {
            target = new HudKeyframe();
            target.tick = tick;
            e.keyframes.add(target);
        }
        float time = playhead;
        target.set(HudKeyframe.PROP_X, e.track(HudKeyframe.PROP_X).evaluate(time, e.x));
        target.set(HudKeyframe.PROP_Y, e.track(HudKeyframe.PROP_Y).evaluate(time, e.y));
        target.set(HudKeyframe.PROP_SCALE, e.track(HudKeyframe.PROP_SCALE).evaluate(time, e.scale));
        target.set(HudKeyframe.PROP_ROTATION, e.track(HudKeyframe.PROP_ROTATION).evaluate(time, e.rotation));
        target.set(HudKeyframe.PROP_OPACITY, e.track(HudKeyframe.PROP_OPACITY).evaluate(time, e.opacity));
        e.compileTracks();
        selectedKeyframe = e.keyframes.indexOf(target);
        rightScroll = 0;
        rebuildAll();
        setStatus("关键帧 @" + tick + "t");
    }

    private void deleteSelectedKeyframe() {
        HudElement e = selectedElementOrNull();
        HudKeyframe kf = selectedKeyframeOrNull();
        if (e == null || kf == null) {
            setStatus("先在时间轴选择关键帧");
            return;
        }
        pushUndo(null);
        e.keyframes.remove(kf);
        e.compileTracks();
        selectedKeyframe = -1;
        rightScroll = 0;
        rebuildAll();
    }

    private void saveToServer() {
        String raw = idText.trim();
        if (raw.isEmpty()) {
            setStatus("模板 id 不能为空");
            return;
        }
        if (!raw.contains(":")) raw = "customui:" + raw;
        ResourceLocation rl = ResourceLocation.tryParse(raw);
        if (rl == null) {
            setStatus("非法模板 id: " + raw);
            return;
        }
        template.id = rl.toString();
        idText = template.id;
        String json = GSON.toJson(template.toJson());
        if (json.getBytes(StandardCharsets.UTF_8).length > HudTemplateRegistry.MAX_TEMPLATE_BYTES) {
            setStatus("模板过大，无法上传 (>" + (HudTemplateRegistry.MAX_TEMPLATE_BYTES / 1024) + "KB)");
            return;
        }
        ModNetworkHandler.INSTANCE.sendToServer(new HudTemplateUploadPacket(json));
        markCurrentTemplateSnapshotSaved();
        setStatus("已上传保存: " + template.id);
        if (idBox != null) idBox.setValue(idText);
    }

    private String currentTemplateSnapshot() {
        if (template == null) {
            return "";
        }
        return HudEditorCloseGuard.snapshotJson(template, idText, GSON_COMPACT);
    }

    private void markCurrentTemplateSnapshotSaved() {
        savedTemplateSnapshot = currentTemplateSnapshot();
    }

    private boolean hasUnsavedTemplateChanges() {
        return HudEditorCloseGuard.hasUnsavedChanges(savedTemplateSnapshot, currentTemplateSnapshot());
    }

    private void copyJson() {
        String json = GSON.toJson(template.toJson());
        Minecraft.getInstance().keyboardHandler.setClipboard(json);
        setStatus("JSON 已复制到剪贴板");
    }

    private void setStatus(String message) {
        status = message;
        statusUntilMillis = Util.getMillis() + 4000;
    }

    // ==================== Undo / redo / clipboard ====================

    /** Snapshot the template before a mutation. Non-null tags coalesce rapid same-control edits. */
    private void pushUndo(@Nullable String tag) {
        long now = Util.getMillis();
        if (tag != null && tag.equals(lastUndoTag) && now - lastUndoTagMillis < UNDO_COALESCE_MS) {
            lastUndoTagMillis = now;
            return;
        }
        lastUndoTag = tag == null ? "" : tag;
        lastUndoTagMillis = now;
        undoStack.push(GSON_COMPACT.toJson(template.toJson()));
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            setStatus("没有可撤销的操作");
            return;
        }
        redoStack.push(GSON_COMPACT.toJson(template.toJson()));
        restoreSnapshot(undoStack.pop());
        setStatus("已撤销 (Ctrl+Y 重做)");
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            setStatus("没有可重做的操作");
            return;
        }
        undoStack.push(GSON_COMPACT.toJson(template.toJson()));
        restoreSnapshot(redoStack.pop());
        setStatus("已重做");
    }

    private void restoreSnapshot(String json) {
        try {
            template = HudTemplate.fromJson(template.id,
                    GSON_COMPACT.fromJson(json, com.google.gson.JsonObject.class));
            idText = template.id;
        } catch (Exception e) {
            setStatus("恢复快照失败");
            return;
        }
        lastUndoTag = "";
        // Snapshots recreate every element, so the entered-group chain is stale
        groupPath.clear();
        extraSelected.clear();
        stylePresetIndex.clear();
        if (selectedElement >= template.elements.size()) {
            selectedElement = template.elements.size() - 1;
        }
        selectedKeyframe = -1;
        rightScroll = 0;
        rebuildAll();
    }

    private void copySelectedElement() {
        HudElement e = selectedElementOrNull();
        if (e == null) {
            setStatus("先选择一个元素");
            return;
        }
        elementClipboard = GSON_COMPACT.toJson(e.toJson());
        Minecraft.getInstance().keyboardHandler.setClipboard(elementClipboard);
        setStatus("已复制元素 (Ctrl+V 粘贴)");
    }

    private void pasteElement() {
        if (elementClipboard == null) {
            setStatus("剪贴板里没有元素");
            return;
        }
        try {
            HudElement clone = HudElement.fromJson(
                    GSON_COMPACT.fromJson(elementClipboard, com.google.gson.JsonObject.class));
            pushUndo(null);
            clone.x += 6;
            clone.y += 6;
            List<HudElement> list = editList();
            list.add(clone);
            markEditOrderDirty();
            selectedElement = list.size() - 1;
            selectedKeyframe = -1;
            extraSelected.clear();
            rightScroll = 0;
            rebuildAll();
            setStatus("已粘贴元素");
        } catch (Exception ex) {
            setStatus("粘贴失败：非法元素数据");
        }
    }

    private void cutSelectedElement() {
        HudElement e = selectedElementOrNull();
        if (e == null) {
            setStatus("先选择一个元素");
            return;
        }
        elementClipboard = GSON_COMPACT.toJson(e.toJson());
        Minecraft.getInstance().keyboardHandler.setClipboard(elementClipboard);
        pushUndo(null);
        editList().remove(e);
        markEditOrderDirty();
        selectedElement = -1;
        selectedKeyframe = -1;
        extraSelected.clear();
        rightScroll = 0;
        rebuildAll();
        setStatus("已剪切元素 (Ctrl+V 粘贴)");
    }

    private void duplicateSelected() {
        HudElement e = selectedElementOrNull();
        if (e == null) {
            setStatus("先选择一个元素");
            return;
        }
        pushUndo(null);
        HudElement clone = e.copy();
        clone.id = e.id.isEmpty() ? "copy" : e.id + "_copy";
        clone.x += 5;
        clone.y += 5;
        List<HudElement> list = editList();
        list.add(clone);
        markEditOrderDirty();
        selectedElement = list.size() - 1;
        selectedKeyframe = -1;
        extraSelected.clear();
        rightScroll = 0;
        rebuildAll();
    }

    // ==================== Group / ungroup ====================

    /** Base (non-animated) box of an element in the current edit-rect space. */
    private static void baseBox(HudElement e, float[] rect, float[] out) {
        float w = e.w * e.scale;
        float h = e.h * e.scale;
        out[0] = rect[0] + e.anchor.fx * rect[2] + e.x - e.origin.fx * w;
        out[1] = rect[1] + e.anchor.fy * rect[3] + e.y - e.origin.fy * h;
        out[2] = w;
        out[3] = h;
    }

    /** Does the element animate its position (any keyframe with x or y)? */
    private static boolean hasPositionTrack(HudElement e) {
        for (HudKeyframe kf : e.keyframes) {
            if (kf.get(HudKeyframe.PROP_X) != null || kf.get(HudKeyframe.PROP_Y) != null) return true;
        }
        return false;
    }

    /** Shift base x/y and every keyframe x/y of an element by a fixed delta. */
    private static void shiftElement(HudElement e, float dx, float dy) {
        e.x += dx;
        e.y += dy;
        for (HudKeyframe kf : e.keyframes) {
            Float kx = kf.get(HudKeyframe.PROP_X);
            if (kx != null) kf.set(HudKeyframe.PROP_X, kx + dx);
            Float ky = kf.get(HudKeyframe.PROP_Y);
            if (ky != null) kf.set(HudKeyframe.PROP_Y, ky + dy);
        }
        e.compileTracks();
    }

    /** G: wrap the selected elements into a GROUP, preserving their layout. */
    private void groupSelected() {
        List<HudElement> selected = selectedElements();
        if (selected.isEmpty()) {
            setStatus("先选择要成组的元素（Ctrl/Shift+点击可多选）");
            return;
        }
        pushUndo(null);
        float[] rect = editRectScratch;
        editRect(rect);

        // Bounding box of all base boxes
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean hadProjection = false;
        float[] box = new float[4];
        for (HudElement e : selected) {
            baseBox(e, rect, box);
            minX = Math.min(minX, box[0]);
            minY = Math.min(minY, box[1]);
            maxX = Math.max(maxX, box[0] + box[2]);
            maxY = Math.max(maxY, box[1] + box[3]);
            hadProjection |= e.hasProjection;
        }

        List<HudElement> list = editList();
        HudElement group = new HudElement();
        group.type = HudElement.Type.GROUP;
        group.id = "group_" + (list.size() + 1);
        group.anchor = HudAnchor.TOP_LEFT;
        group.origin = HudAnchor.TOP_LEFT;
        group.x = minX - rect[0];
        group.y = minY - rect[1];
        group.w = Math.max(1f, maxX - minX);
        group.h = Math.max(1f, maxY - minY);
        int minZ = Integer.MAX_VALUE;
        for (HudElement e : selected) minZ = Math.min(minZ, e.zIndex);
        group.zIndex = minZ == Integer.MAX_VALUE ? 0 : minZ;

        // Re-anchor children inside the group box (base + keyframe tracks)
        for (HudElement e : selected) {
            float dx = rect[0] + e.anchor.fx * rect[2] - minX - e.anchor.fx * group.w;
            float dy = rect[1] + e.anchor.fy * rect[3] - minY - e.anchor.fy * group.h;
            shiftElement(e, dx, dy);
            group.children.add(e);
        }
        int insertAt = list.indexOf(selected.get(0));
        list.removeAll(selected);
        list.add(Math.min(Math.max(insertAt, 0), list.size()), group);
        group.compileTracks();
        markEditOrderDirty();

        extraSelected.clear();
        selectedElement = list.indexOf(group);
        selectedKeyframe = -1;
        rightScroll = 0;
        rebuildAll();
        setStatus(hadProjection
                ? "已成组（注意：子元素 3D 投影失效，请在组合上重新设置投影）"
                : "已成组 " + selected.size() + " 个元素（U 键解组）");
    }

    /** U: dissolve the selected GROUP back into the edit list. */
    private void ungroupSelected() {
        HudElement group = selectedElementOrNull();
        if (group == null || group.type != HudElement.Type.GROUP) {
            setStatus("先选择一个组合元素");
            return;
        }
        pushUndo(null);
        float[] rect = editRectScratch;
        editRect(rect);
        float[] box = new float[4];
        baseBox(group, rect, box);
        boolean lossy = group.scale != 1f || group.rotation != 0f || !group.keyframes.isEmpty();

        List<HudElement> list = editList();
        int at = list.indexOf(group);
        list.remove(group);
        for (int i = 0; i < group.children.size(); i++) {
            HudElement e = group.children.get(i);
            float dx = box[0] + e.anchor.fx * box[2] - rect[0] - e.anchor.fx * rect[2];
            float dy = box[1] + e.anchor.fy * box[3] - rect[1] - e.anchor.fy * rect[3];
            shiftElement(e, dx, dy);
            list.add(at + i, e);
        }
        group.children.clear();
        markEditOrderDirty();

        extraSelected.clear();
        selectedElement = -1;
        selectedKeyframe = -1;
        rightScroll = 0;
        rebuildAll();
        setStatus(lossy ? "已解组（组合带缩放/旋转/动画，位置可能有偏差）" : "已解组");
    }

    // ==================== Group editing context ====================

    /** The element list currently being edited (top level or an entered group's children). */
    private List<HudElement> editList() {
        return groupPath.isEmpty() ? template.elements : groupPath.get(groupPath.size() - 1).children;
    }

    /** Render-order view of the edit list. */
    private List<HudElement> editRenderOrder() {
        return groupPath.isEmpty() ? template.renderOrder()
                : groupPath.get(groupPath.size() - 1).childRenderOrder();
    }

    private void markEditOrderDirty() {
        if (groupPath.isEmpty()) {
            template.markRenderOrderDirty();
        } else {
            groupPath.get(groupPath.size() - 1).markChildOrderDirty();
        }
    }

    /**
     * Anchor-space rect of the edit context: full screen at top level, or the
     * resolved box of the entered group chain (rotation/scale of parents are
     * ignored for editing purposes). Writes {x, y, w, h} into {@code out}.
     */
    private void editRect(float[] out) {
        out[0] = 0f; out[1] = 0f; out[2] = width; out[3] = height;
        for (int i = 0; i < groupPath.size(); i++) {
            HudElement g = groupPath.get(i);
            HudTemplateOverlayRenderer.resolveLocalBoxInRect(
                    g, renderTime, out[0], out[1], out[2], out[3], out);
        }
    }

    private void enterGroup(HudElement group) {
        groupPath.add(group);
        selectedElement = -1;
        selectedKeyframe = -1;
        extraSelected.clear();
        rightScroll = 0;
        rebuildAll();
        setStatus("已进入组合 " + (group.id.isEmpty() ? "(未命名)" : group.id) + "（Backspace 返回上层）");
    }

    private void exitGroup() {
        if (groupPath.isEmpty()) return;
        HudElement group = groupPath.remove(groupPath.size() - 1);
        selectedElement = editList().indexOf(group);
        selectedKeyframe = -1;
        extraSelected.clear();
        rightScroll = 0;
        rebuildAll();
    }

    /** All selected elements (primary + Shift-extended), valid indices only. */
    private List<HudElement> selectedElements() {
        List<HudElement> list = editList();
        List<HudElement> result = new ArrayList<>();
        if (selectedElement >= 0 && selectedElement < list.size()) {
            result.add(list.get(selectedElement));
        }
        for (int idx : extraSelected) {
            if (idx >= 0 && idx < list.size() && idx != selectedElement) {
                result.add(list.get(idx));
            }
        }
        return result;
    }

    @Nullable
    private HudElement selectedElementOrNull() {
        List<HudElement> list = editList();
        if (selectedElement < 0 || selectedElement >= list.size()) return null;
        return list.get(selectedElement);
    }

    @Nullable
    private HudKeyframe selectedKeyframeOrNull() {
        HudElement e = selectedElementOrNull();
        if (e == null || selectedKeyframe < 0 || selectedKeyframe >= e.keyframes.size()) return null;
        return e.keyframes.get(selectedKeyframe);
    }

    @Nullable
    private static Integer parseHexColor(String s) {
        String cleaned = s.trim().replace("#", "").replace("0x", "");
        if (cleaned.isEmpty()) return null;
        try {
            long v = Long.parseLong(cleaned, 16);
            if (cleaned.length() <= 6) v |= 0xFF000000L;
            return (int) v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimFloat(float v) {
        if (v == Math.floor(v) && !Float.isInfinite(v)) {
            return Integer.toString((int) v);
        }
        return Float.toString(v);
    }

    private int previewLifetime() {
        if (template.isPersistent()) {
            int last = 0;
            for (HudElement e : template.elements) last = Math.max(last, e.lastKeyframeTick());
            return Math.max(last, 100);
        }
        return template.effectiveLifetime();
    }

    // ==================== Tick / render ====================

    /** Starts/stops time-driven playback, anchoring the wall clock on start. */
    private void setPlaying(boolean value) {
        playing = value;
        if (playing) {
            playAnchorMillis = net.minecraft.Util.getMillis();
            playAnchorPlayhead = playhead >= previewLifetime() ? 0f : playhead;
        }
        rebuildAll();
    }

    /** Un-wrapped wall-clock playback time (keyframe ticks). */
    private float currentPlayTime() {
        return playAnchorPlayhead
                + (net.minecraft.Util.getMillis() - playAnchorMillis) / 1000f * playTicksPerSecond;
    }

    private static String formatPlayRate(float rate) {
        int rounded = Math.round(rate);
        return Math.abs(rate - rounded) < 0.001f ? Integer.toString(rounded) : Float.toString(rate);
    }

    @Override
    public void tick() {
        super.tick();
        if (playing) {
            // Time-driven playback: advance by real elapsed time, not tick count
            float time = currentPlayTime();
            int lifetime = previewLifetime();
            if (time >= lifetime) {
                // Preview follows real playback rules: loop wraps, otherwise
                // (one-shot or persistent) freeze at the final state.
                if (template.loop && !template.isPersistent()) {
                    playAnchorPlayhead = lifetime > 0 ? time % lifetime : 0f;
                    playAnchorMillis = net.minecraft.Util.getMillis();
                    playhead = playAnchorPlayhead;
                } else {
                    playhead = lifetime;
                    playing = false;
                    rebuildAll();
                    setStatus(template.isPersistent() ? "持续模式：停在末帧状态" : "播放结束（未开循环）");
                }
            } else {
                playhead = time;
            }
        }
    }

    // ==================== Virtual-screen viewport ====================

    /** Fit the full virtual screen (width x height) into the uncovered center area. */
    private void updateViewport() {
        float availX = LEFT_W + VIEWPORT_PAD;
        float availY = TOP_H + VIEWPORT_PAD;
        float availW = rightX() - VIEWPORT_PAD - availX;
        float availH = (height - TL_H - VIEWPORT_PAD) - availY;
        float s = Math.min(availW / Math.max(1, width), availH / Math.max(1, height));
        viewportScale = Math.max(0.05f, s);
        viewportX = availX + (availW - width * viewportScale) / 2f;
        viewportY = availY + (availH - height * viewportScale) / 2f;
    }

    private double toVirtualX(double screenX) {
        return (screenX - viewportX) / viewportScale;
    }

    private double toVirtualY(double screenY) {
        return (screenY - viewportY) / viewportScale;
    }

    /**
     * Copies the main framebuffer (the untouched 3D world image rendered this
     * frame, before any editor drawing) into a reusable texture so it can be
     * drawn scaled inside the viewport without sampling the bound target.
     */
    private void captureWorldSnapshot(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        com.mojang.blaze3d.pipeline.RenderTarget main = mc.getMainRenderTarget();
        if (worldSnapshot == null
                || worldSnapshot.width != main.width || worldSnapshot.height != main.height) {
            if (worldSnapshot != null) {
                worldSnapshot.destroyBuffers();
            }
            worldSnapshot = new com.mojang.blaze3d.pipeline.TextureTarget(
                    main.width, main.height, false, Minecraft.ON_OSX);
            worldSnapshot.setFilterMode(com.mojang.blaze3d.platform.GlConst.GL_LINEAR);
        }
        graphics.flush();
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                com.mojang.blaze3d.platform.GlConst.GL_READ_FRAMEBUFFER, main.frameBufferId);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(
                com.mojang.blaze3d.platform.GlConst.GL_DRAW_FRAMEBUFFER, worldSnapshot.frameBufferId);
        com.mojang.blaze3d.platform.GlStateManager._glBlitFrameBuffer(
                0, 0, main.width, main.height, 0, 0, main.width, main.height,
                com.mojang.blaze3d.platform.GlConst.GL_COLOR_BUFFER_BIT,
                com.mojang.blaze3d.platform.GlConst.GL_NEAREST);
        main.bindWrite(true);
    }

    /** Draws the captured world image scaled to exactly fill the viewport rect. */
    private void drawWorldBackdrop(GuiGraphics graphics) {
        float x0 = viewportX;
        float y0 = viewportY;
        float x1 = viewportX + width * viewportScale;
        float y1 = viewportY + height * viewportScale;
        if (worldSnapshot == null || Minecraft.getInstance().level == null) {
            graphics.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), 0xFF1A2026);
            return;
        }
        graphics.flush();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, worldSnapshot.getColorTextureId());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        org.joml.Matrix4f matrix = graphics.pose().last().pose();
        com.mojang.blaze3d.vertex.BufferBuilder builder =
                com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        builder.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);
        // FBO textures are bottom-up: screen top samples v=1
        builder.vertex(matrix, x0, y0, 0f).uv(0f, 1f).endVertex();
        builder.vertex(matrix, x0, y1, 0f).uv(0f, 0f).endVertex();
        builder.vertex(matrix, x1, y1, 0f).uv(1f, 0f).endVertex();
        builder.vertex(matrix, x1, y0, 0f).uv(1f, 1f).endVertex();
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(builder.end());
    }

    /** Prominent banner while editing inside a GROUP (drawn over the viewport, never covered). */
    private void renderGroupBanner(GuiGraphics graphics) {
        if (groupPath.isEmpty()) return;
        StringBuilder crumb = new StringBuilder("组合编辑: ");
        for (int i = 0; i < groupPath.size(); i++) {
            if (i > 0) crumb.append(" > ");
            String gid = groupPath.get(i).id;
            crumb.append(gid.isEmpty() ? "(未命名)" : gid);
        }
        crumb.append("   [Backspace 返回上层]");
        String text = font.plainSubstrByWidth(crumb.toString(), Math.round(width * 0.6f));
        int w = font.width(text);
        int cx = Math.round(viewportX + width * viewportScale / 2f);
        int x = cx - w / 2;
        int y = Math.round(viewportY) + 5;
        graphics.fill(x - 9, y - 5, x + w + 9, y + 13, 0xE82A2008);
        graphics.fill(x - 9, y - 6, x + w + 9, y - 5, 0xFFE0B341);
        graphics.fill(x - 9, y + 13, x + w + 9, y + 14, 0xFFE0B341);
        graphics.fill(x - 10, y - 6, x - 9, y + 14, 0xFFE0B341);
        graphics.fill(x + w + 9, y - 6, x + w + 10, y + 14, 0xFFE0B341);
        graphics.drawString(font, text, x, y, 0xFFFFD873);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        applyTemplateCacheRefresh();
        // Frame-smooth wall-clock playback time (loop-wrapped for the preview)
        if (playing) {
            renderTime = currentPlayTime();
            int lifetime = previewLifetime();
            if (lifetime > 0 && template.loop && !template.isPersistent()) {
                renderTime %= lifetime;
            } else {
                renderTime = Math.min(renderTime, lifetime);
            }
        } else {
            renderTime = playhead;
        }
        updateViewport();
        captureWorldSnapshot(graphics);

        // Workspace base: opaque backdrop everywhere (panels redraw on top)
        graphics.fill(0, 0, width, height, 0xFF0C1014);
        // Scaled world image inside the viewport (true game proportions)
        drawWorldBackdrop(graphics);

        // Viewport frame
        int vl = Math.round(viewportX);
        int vt = Math.round(viewportY);
        int vr = Math.round(viewportX + width * viewportScale);
        int vb = Math.round(viewportY + height * viewportScale);
        graphics.fill(vl - 1, vt - 1, vr + 1, vt, 0xFF3A4754);
        graphics.fill(vl - 1, vb, vr + 1, vb + 1, 0xFF3A4754);
        graphics.fill(vl - 1, vt, vl, vb, 0xFF3A4754);
        graphics.fill(vr, vt, vr + 1, vb, 0xFF3A4754);

        // Canvas preview through the real overlay pipeline (3D flattened to 2D),
        // rendered in virtual full-screen coordinates scaled into the viewport.
        graphics.enableScissor(vl, vt, vr, vb);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(viewportX, viewportY, 0f);
        pose.scale(viewportScale, viewportScale, 1f);
        HudTemplateOverlayRenderer.renderTemplatePreview(graphics, template, renderTime, width, height);

        // Data-source binding status: gold border = source resolves, red = missing
        List<HudElement> bindCheck = editList();
        for (int i = 0; i < bindCheck.size(); i++) {
            HudElement e = bindCheck.get(i);
            int status = dataBindingStatus(e);
            if (status != 0) {
                drawElementOutline(graphics, e, status > 0 ? 0xFFFFC640 : 0x90FF5252);
            }
        }

        updateHover(mouseX, mouseY);

        // Hover hint outline (below the selection outline)
        if (hoveredElement >= 0 && hoveredElement != selectedElement
                && hoveredElement < editList().size()) {
            drawElementOutline(graphics, editList().get(hoveredElement), 0x70FFFFFF);
        }

        // Multi-selection outlines (dimmer than the primary)
        List<HudElement> list = editList();
        for (int idx : extraSelected) {
            if (idx >= 0 && idx < list.size() && idx != selectedElement) {
                drawElementOutline(graphics, list.get(idx), 0xB04FC3F7);
            }
        }

        // Selected element outline
        HudElement selected = selectedElementOrNull();
        if (selected != null) {
            drawElementOutline(graphics, selected, ACCENT);
        }

        // Entered-group bounds hint
        if (!groupPath.isEmpty()) {
            editRect(editRectScratch);
            int gl = Math.round(editRectScratch[0]);
            int gt = Math.round(editRectScratch[1]);
            int gr = Math.round(editRectScratch[0] + editRectScratch[2]);
            int gb = Math.round(editRectScratch[1] + editRectScratch[3]);
            graphics.fill(gl - 2, gt - 2, gr + 2, gt - 1, 0x80E0B341);
            graphics.fill(gl - 2, gb + 1, gr + 2, gb + 2, 0x80E0B341);
            graphics.fill(gl - 2, gt - 1, gl - 1, gb + 1, 0x80E0B341);
            graphics.fill(gr + 1, gt - 1, gr + 2, gb + 1, 0x80E0B341);
        }

        // Center snap guides while dragging
        if (draggingElement && (snapActiveX || snapActiveY)) {
            editRect(editRectScratch);
            float rcx = editRectScratch[0] + editRectScratch[2] / 2f;
            float rcy = editRectScratch[1] + editRectScratch[3] / 2f;
            if (snapActiveX) {
                graphics.fill(Math.round(rcx), Math.round(editRectScratch[1]),
                        Math.round(rcx) + 1, Math.round(editRectScratch[1] + editRectScratch[3]), 0xCC4FC3F7);
            }
            if (snapActiveY) {
                graphics.fill(Math.round(editRectScratch[0]), Math.round(rcy),
                        Math.round(editRectScratch[0] + editRectScratch[2]), Math.round(rcy) + 1, 0xCC4FC3F7);
            }
        }
        pose.popPose();
        graphics.disableScissor();

        renderPanels(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderGroupBanner(graphics);
        renderOverlays(graphics, mouseX, mouseY);
        renderTextureSuggestions(graphics, mouseX, mouseY);
        renderBigEdit(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Binding status of an element: 0 = no data binding, 1 = every binding
     * (dataSource / worldAnchor) resolves against live synced data, -1 = at
     * least one binding cannot be resolved right now.
     */
    private int dataBindingStatus(HudElement e) {
        boolean any = false;
        boolean ok = true;
        HudElementRuntime rt = HudElementRuntime.of(e);
        if (e.dataSource != null && !e.dataSource.isEmpty()) {
            any = true;
            String zoneId = rt.captureZoneDataSource(e);
            String sbKey = zoneId == null ? rt.scoreboardKey(e) : null;
            String entityKey = zoneId == null && sbKey == null ? rt.entityKey(e) : null;
            if (zoneId != null) {
                ok = com.lootmatrix.customui.client.CaptureZoneHudRenderer.getInstance()
                        .getZoneState(zoneId) != null;
            } else if (sbKey != null) {
                ok = HudScoreboardClientCache.has(sbKey);
            } else if (entityKey != null) {
                ok = HudEntityClientCache.has(entityKey);
            } else {
                ok = false; // unrecognized binding scheme
            }
        }
        if (e.worldAnchor != null && !e.worldAnchor.isEmpty()) {
            any = true;
            String anchorZone = rt.captureZoneWorldAnchor(e);
            ok &= anchorZone != null
                    && com.lootmatrix.customui.client.CaptureZoneBoundaryRenderer.getInstance()
                            .getZoneWorldCenter(anchorZone) != null;
        }
        if (!any) return 0;
        return ok ? 1 : -1;
    }

    private void drawElementOutline(GuiGraphics graphics, HudElement e, int color) {
        editRect(editRectScratch);
        HudTemplateOverlayRenderer.resolveLocalBoxInRect(e, renderTime,
                editRectScratch[0], editRectScratch[1], editRectScratch[2], editRectScratch[3], boxScratch);
        int l = Math.round(boxScratch[0]);
        int t = Math.round(boxScratch[1]);
        int r = Math.round(boxScratch[0] + boxScratch[2]);
        int b = Math.round(boxScratch[1] + boxScratch[3]);
        graphics.fill(l - 1, t - 1, r + 1, t, color);
        graphics.fill(l - 1, b, r + 1, b + 1, color);
        graphics.fill(l - 1, t, l, b, color);
        graphics.fill(r, t, r + 1, b, color);
    }

    private void renderPanels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Top bar
        graphics.fill(0, 0, width, TOP_H - 2, PANEL_BG);
        graphics.fill(0, TOP_H - 2, width, TOP_H, PANEL_EDGE);
        graphics.drawString(font, "ID", 12, 7, TEXT_DIM);
        graphics.drawString(font, "t=" + Math.round(playhead) + "/" + previewLifetime(), width - 70, 7, TEXT_DIM);
        // Group breadcrumb moved to the viewport banner (renderGroupBanner)
        if (template.isGui()) {
            graphics.drawString(font, "GUI", width - 100, 7, 0xFF7CD67C);
        }

        // Left library panel
        graphics.fill(0, TOP_H, LEFT_W, height - TL_H, PANEL_BG);
        graphics.fill(LEFT_W - 2, TOP_H, LEFT_W, height - TL_H, PANEL_EDGE);
        graphics.drawString(font, "模板库(双击载入/拖入)", 4, TOP_H + 4, TEXT_DIM);
        int listTop = TOP_H + 16;
        int visibleRows = Math.max(0, (libraryBottom() - listTop) / LIB_ROW_H);
        for (int i = 0; i < visibleRows; i++) {
            int idx = libScroll + i;
            if (idx >= libraryIds.size()) break;
            int y = listTop + i * LIB_ROW_H;
            boolean hovered = mouseX >= 2 && mouseX < LEFT_W - 4 && mouseY >= y && mouseY < y + LIB_ROW_H;
            if (idx == libSelected) {
                graphics.fill(2, y, LEFT_W - 4, y + LIB_ROW_H, 0x6033B5E5);
            } else if (hovered) {
                graphics.fill(2, y, LEFT_W - 4, y + LIB_ROW_H, 0x30FFFFFF);
            }
            String id = libraryIds.get(idx);
            graphics.drawString(font, font.plainSubstrByWidth(id, LEFT_W - 10), 4, y + 1,
                    idx == libSelected ? 0xFFFFFFFF : 0xFFB8C4CC);
        }

        renderPalette(graphics, mouseX, mouseY);

        // Right property panel
        graphics.fill(rightX(), TOP_H, width, height - TL_H, PANEL_BG);
        graphics.fill(rightX(), TOP_H, rightX() + 2, height - TL_H, PANEL_EDGE);
        int baseY = TOP_H + 4;
        for (int i = rightScroll; i < rightRows.size(); i++) {
            int y = baseY + (i - rightScroll) * ROW_H;
            if (y + 13 > height - TL_H - 2) break;
            Row row = rightRows.get(i);
            int labelWidth = row.widget == null ? RIGHT_W - RIGHT_PAD * 2 : rightLabelWidth();
            graphics.drawString(font, font.plainSubstrByWidth(row.label, labelWidth), rightX() + RIGHT_PAD, y + 3, TEXT_DIM);
        }

        renderTimeline(graphics, mouseX, mouseY);

        // Divider hover / drag highlight (panels are user-resizable)
        int divider = resizingDivider != 0 ? resizingDivider : hoveredDivider(mouseX, mouseY);
        if (divider == 1) {
            graphics.fill(LEFT_W - 1, TOP_H, LEFT_W + 1, height - TL_H, 0xCC4FC3F7);
        } else if (divider == 2) {
            graphics.fill(rightX() - 1, TOP_H, rightX() + 1, height - TL_H, 0xCC4FC3F7);
        } else if (divider == 3) {
            graphics.fill(LEFT_W, height - TL_H - 1, rightX(), height - TL_H + 1, 0xCC4FC3F7);
        }
    }

    /** Control palette: scrollable rows of [style preview | name]. */
    private void renderPalette(GuiGraphics graphics, int mouseX, int mouseY) {
        int top = paletteTop();
        graphics.fill(0, top - 2, LEFT_W - 2, top - 1, PANEL_EDGE);
        graphics.drawString(font, "控件库(滚轮翻页)", 4, top + 2, TEXT_DIM);
        int rowsTop = paletteRowsTop();
        int visible = paletteVisibleRows();
        for (int i = 0; i < visible; i++) {
            int idx = paletteScroll + i;
            if (idx >= PALETTE_ENTRIES.length) break;
            int y = rowsTop + i * PALETTE_ROW_H;
            boolean hovered = mouseX >= 2 && mouseX < LEFT_W - 4 && mouseY >= y && mouseY < y + PALETTE_ROW_H;
            if (hovered) {
                graphics.fill(2, y, LEFT_W - 4, y + PALETTE_ROW_H, 0x3033B5E5);
            }
            drawPaletteGlyph(graphics, PALETTE_ENTRIES[idx][1], 4, y + 2);
            graphics.drawString(font, font.plainSubstrByWidth(PALETTE_ENTRIES[idx][0], LEFT_W - 32),
                    26, y + 3, hovered ? 0xFFFFFFFF : 0xFFB8C4CC);
        }
        // Scrollbar hint
        if (PALETTE_ENTRIES.length > visible) {
            int trackH = visible * PALETTE_ROW_H;
            int barH = Math.max(8, trackH * visible / PALETTE_ENTRIES.length);
            int barY = rowsTop + (trackH - barH) * paletteScroll
                    / Math.max(1, PALETTE_ENTRIES.length - visible);
            graphics.fill(LEFT_W - 4, rowsTop, LEFT_W - 3, rowsTop + trackH, 0x40FFFFFF);
            graphics.fill(LEFT_W - 4, barY, LEFT_W - 3, barY + barH, 0xA04FC3F7);
        }
    }

    /** 18x9 style preview for one palette row. */
    private void drawPaletteGlyph(GuiGraphics g, String kind, int x, int y) {
        switch (kind) {
            case "TEXT" -> g.drawString(font, "Tt", x + 3, y, 0xFFE6EDF3);
            case "IMAGE" -> {
                g.fill(x, y, x + 9, y + 5, 0xFF7E57C2);
                g.fill(x + 9, y, x + 18, y + 5, 0xFF4FC3F7);
                g.fill(x, y + 5, x + 9, y + 9, 0xFF4FC3F7);
                g.fill(x + 9, y + 5, x + 18, y + 9, 0xFF7E57C2);
            }
            case "RECT" -> g.fill(x, y, x + 18, y + 9, 0xC0E6EDF3);
            case "ROUNDED_RECT" -> {
                g.fill(x + 1, y, x + 17, y + 9, 0xC0E6EDF3);
                g.fill(x, y + 1, x + 18, y + 8, 0xC0E6EDF3);
            }
            case "CIRCLE" -> {
                g.fill(x + 6, y, x + 12, y + 9, 0xC0E6EDF3);
                g.fill(x + 4, y + 1, x + 14, y + 8, 0xC0E6EDF3);
                g.fill(x + 3, y + 3, x + 15, y + 6, 0xC0E6EDF3);
            }
            case "TRIANGLE" -> {
                g.fill(x + 8, y + 1, x + 10, y + 3, 0xC0E6EDF3);
                g.fill(x + 6, y + 3, x + 12, y + 6, 0xC0E6EDF3);
                g.fill(x + 4, y + 6, x + 14, y + 9, 0xC0E6EDF3);
            }
            case "BORDER" -> {
                g.fill(x, y, x + 18, y + 1, 0xC0E6EDF3);
                g.fill(x, y + 8, x + 18, y + 9, 0xC0E6EDF3);
                g.fill(x, y + 1, x + 1, y + 8, 0xC0E6EDF3);
                g.fill(x + 17, y + 1, x + 18, y + 8, 0xC0E6EDF3);
            }
            case "LINE" -> g.fill(x, y + 4, x + 18, y + 6, 0xC0E6EDF3);
            case "GRADIENT_RECT" -> {
                g.fill(x, y, x + 6, y + 9, 0xF04FC3F7);
                g.fill(x + 6, y, x + 12, y + 9, 0x904FC3F7);
                g.fill(x + 12, y, x + 18, y + 9, 0x304FC3F7);
            }
            case "ARC" -> {
                g.fill(x + 4, y, x + 14, y + 2, 0xC0E6EDF3);
                g.fill(x + 2, y + 1, x + 5, y + 7, 0xC0E6EDF3);
                g.fill(x + 13, y + 1, x + 16, y + 4, 0xC0E6EDF3);
            }
            case "PROGRESS" -> {
                g.fill(x, y + 2, x + 18, y + 7, 0xFF333333);
                g.fill(x, y + 2, x + 12, y + 7, 0xFF44FF44);
            }
            case "TEMPLATE" -> g.drawString(font, "{}", x + 4, y, 0xFFE0B341);
            case "STAT" -> {
                g.fill(x, y, x + 18, y + 9, 0x60101418);
                // 折线示意
                g.fill(x + 1, y + 6, x + 4, y + 7, 0xFF4FC3F7);
                g.fill(x + 4, y + 4, x + 7, y + 5, 0xFF4FC3F7);
                g.fill(x + 7, y + 5, x + 10, y + 6, 0xFF4FC3F7);
                g.fill(x + 10, y + 2, x + 13, y + 3, 0xFF4FC3F7);
                g.fill(x + 13, y + 3, x + 17, y + 4, 0xFF4FC3F7);
            }
            case "GROUP" -> {
                g.fill(x, y, x + 18, y + 1, 0xC0E0B341);
                g.fill(x, y + 8, x + 18, y + 9, 0xC0E0B341);
                g.fill(x, y + 1, x + 1, y + 8, 0xC0E0B341);
                g.fill(x + 17, y + 1, x + 18, y + 8, 0xC0E0B341);
                g.fill(x + 3, y + 3, x + 8, y + 6, 0x90E6EDF3);
                g.fill(x + 10, y + 3, x + 15, y + 6, 0x90E6EDF3);
            }
            case "PRESET_CAPTURE" -> {
                g.fill(x, y, x + 18, y + 9, 0x80101820);
                // 左侧圆形扇形进度示意
                g.fill(x + 2, y + 2, x + 8, y + 7, 0x60222C33);
                g.fill(x + 5, y + 2, x + 8, y + 4, 0xC04FC3F7);
                g.fill(x + 5, y + 4, x + 7, y + 5, 0xC04FC3F7);
                // 右侧名称 + 细进度条
                g.fill(x + 10, y + 2, x + 16, y + 4, 0xC0E6EDF3);
                g.fill(x + 10, y + 6, x + 16, y + 8, 0xFF333333);
                g.fill(x + 10, y + 6, x + 14, y + 8, 0xFF4FC3F7);
            }
            case "PRESET_CAPTURE_3D" -> {
                g.fill(x + 6, y, x + 12, y + 9, 0xC04FC3F7);
                g.fill(x + 4, y + 1, x + 14, y + 8, 0xC04FC3F7);
                g.fill(x + 7, y + 3, x + 11, y + 6, 0xFF101820);
            }
            case "PRESET_TEAM_SCORE" -> {
                g.fill(x, y + 1, x + 8, y + 8, 0xE0B71C1C);
                g.fill(x + 10, y + 1, x + 18, y + 8, 0xE01565C0);
                g.fill(x + 8, y + 1, x + 10, y + 8, 0xFF101820);
            }
            default -> g.fill(x, y, x + 18, y + 9, 0x60FFFFFF);
        }
    }

    private void addPaletteEntry(String kind) {
        switch (kind) {
            case "PRESET_CAPTURE" -> addPresetCaptureProgress();
            case "PRESET_CAPTURE_3D" -> addPresetCaptureMarker3D();
            case "PRESET_TEAM_SCORE" -> addPresetTeamScore();
            default -> addElement(HudElement.Type.valueOf(kind));
        }
    }

    private void renderTimeline(GuiGraphics graphics, int mouseX, int mouseY) {
        int y0 = height - TL_H;
        graphics.fill(0, y0, width, height, PANEL_BG);
        graphics.fill(0, y0, width, y0 + 2, PANEL_EDGE);

        int rulerLeft = timelineLeft();
        int rulerRight = timelineRight();
        int rulerY = y0 + 8;
        int lifetime = previewLifetime();
        graphics.fill(rulerLeft, rulerY + 8, rulerRight, rulerY + 10, 0xFF37444F);

        // Tick marks every 20t
        for (int t = 0; t <= lifetime; t += 20) {
            int x = tickToX(t, lifetime);
            graphics.fill(x, rulerY + 5, x + 1, rulerY + 13, 0xFF55656F);
        }

        // Keyframe diamonds of the selected element
        HudElement e = selectedElementOrNull();
        if (e != null) {
            for (int i = 0; i < e.keyframes.size(); i++) {
                HudKeyframe kf = e.keyframes.get(i);
                int x = tickToX(kf.tick, lifetime);
                int color = i == selectedKeyframe ? ACCENT : 0xFFE0B341;
                graphics.fill(x - 2, rulerY + 6, x + 3, rulerY + 12, color);
            }
        }

        // Playhead
        int px = tickToX(Math.round(playhead), lifetime);
        graphics.fill(px, y0 + 4, px + 1, y0 + 22, 0xFFFF5252);
        graphics.drawString(font, "时间轴", 6, rulerY + 2, TEXT_DIM);
        graphics.drawString(font, "存活", LEFT_W + 218, y0 + 39, TEXT_DIM);
        graphics.drawString(font, "速率t/s", LEFT_W + 296, y0 + 39, TEXT_DIM);
    }

    private void renderOverlays(GuiGraphics graphics, int mouseX, int mouseY) {
        if (libDragging && libDragIndex >= 0 && libDragIndex < libraryIds.size()) {
            String id = libraryIds.get(libDragIndex);
            graphics.fill(mouseX + 6, mouseY - 4, mouseX + 14 + font.width(id), mouseY + 8, 0xC0202830);
            graphics.drawString(font, id, mouseX + 10, mouseY - 2, 0xFFFFFFFF);
        }
        if (!hoverInfo.isEmpty() && hoverStackCount > 1) {
            int x = Math.min(mouseX + 10, width - RIGHT_W - hoverInfoWidth - 8);
            int y = mouseY + 12;
            graphics.fill(x - 3, y - 2, x + hoverInfoWidth + 3, y + 10, 0xC0101820);
            graphics.drawString(font, hoverInfo, x, y, 0xFFD8E1E8);
        }
        if (!status.isEmpty() && Util.getMillis() < statusUntilMillis) {
            int w = font.width(status);
            int x = (width - w) / 2;
            int y = height - TL_H - 16;
            graphics.fill(x - 4, y - 2, x + w + 4, y + 10, 0xC0000000);
            graphics.drawString(font, status, x, y, 0xFFE6EDF3);
        }
    }

    private int timelineLeft() {
        return LEFT_W + 4;
    }

    private int timelineRight() {
        return width - RIGHT_W - 4;
    }

    private int tickToX(int tick, int lifetime) {
        float t = lifetime <= 0 ? 0f : (float) tick / lifetime;
        return timelineLeft() + Math.round(t * (timelineRight() - timelineLeft()));
    }

    private int xToTick(double x, int lifetime) {
        float t = (float) ((x - timelineLeft()) / Math.max(1, timelineRight() - timelineLeft()));
        return Math.max(0, Math.min(lifetime, Math.round(t * lifetime)));
    }

    // ==================== Mouse ====================

    private boolean inCanvas(double x, double y) {
        return x >= LEFT_W && x < rightX() && y >= TOP_H && y < height - TL_H;
    }

    /** Which panel divider the cursor grabs: 0 none, 1 left, 2 right, 3 timeline. */
    private int hoveredDivider(double x, double y) {
        if (y >= TOP_H && y < height - TL_H) {
            if (Math.abs(x - LEFT_W) <= DIVIDER_GRAB) return 1;
            if (Math.abs(x - rightX()) <= DIVIDER_GRAB) return 2;
        }
        if (x >= LEFT_W && x < rightX() && Math.abs(y - (height - TL_H)) <= DIVIDER_GRAB) return 3;
        return 0;
    }

    /** Apply a divider drag, clamped so the viewport always keeps a usable area. */
    private void resizeDividerTo(double mouseX, double mouseY) {
        int before = LEFT_W ^ (RIGHT_W << 11) ^ (TL_H << 22);
        switch (resizingDivider) {
            case 1 -> LEFT_W = Math.max(LEFT_W_MIN,
                    Math.min((int) mouseX, width - RIGHT_W - 160));
            case 2 -> RIGHT_W = Math.max(RIGHT_W_MIN,
                    Math.min(width - (int) mouseX, width - LEFT_W - 160));
            case 3 -> TL_H = Math.max(TL_H_MIN,
                    Math.min(height - (int) mouseY, height - TOP_H - 120));
        }
        if (before != (LEFT_W ^ (RIGHT_W << 11) ^ (TL_H << 22))) {
            updateViewport();
            rebuildAll();
        }
    }

    private boolean inLibraryList(double x, double y) {
        return x >= 0 && x < LEFT_W && y >= TOP_H + 16 && y < libraryBottom();
    }

    private boolean inRightPanel(double x, double y) {
        return x >= rightX() && y >= TOP_H && y < height - TL_H;
    }

    private boolean inTimelineRuler(double x, double y) {
        int y0 = height - TL_H;
        return x >= timelineLeft() - 4 && x <= timelineRight() + 4 && y >= y0 + 4 && y <= y0 + 24;
    }

    /**
     * Rotation-aware hit test against an element's box at the current playhead.
     * Tiny/collapsed boxes are padded to a minimum clickable size so animated
     * (scale→0) or zero-sized elements stay selectable.
     */
    private boolean hitElement(HudElement e, double mouseX, double mouseY) {
        editRect(editRectScratch);
        HudTemplateOverlayRenderer.resolveLocalBoxInRect(e, renderTime,
                editRectScratch[0], editRectScratch[1], editRectScratch[2], editRectScratch[3], boxScratch);
        float rotation = e.track(HudKeyframe.PROP_ROTATION).evaluate(renderTime, e.rotation);
        double px = mouseX;
        double py = mouseY;
        if (rotation != 0f) {
            // Inverse-rotate the cursor around the element pivot (anchor point)
            double pivotX = boxScratch[0] + e.origin.fx * boxScratch[2];
            double pivotY = boxScratch[1] + e.origin.fy * boxScratch[3];
            double rad = -Math.toRadians(rotation);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double dx = mouseX - pivotX;
            double dy = mouseY - pivotY;
            px = pivotX + dx * cos - dy * sin;
            py = pivotY + dx * sin + dy * cos;
        }
        float minSize = 4f;
        float left = boxScratch[0];
        float top = boxScratch[1];
        float w = boxScratch[2];
        float h = boxScratch[3];
        if (w < minSize) { left -= (minSize - w) * 0.5f; w = minSize; }
        if (h < minSize) { top -= (minSize - h) * 0.5f; h = minSize; }
        return px >= left && px <= left + w && py >= top && py <= top + h;
    }

    /** Refresh the hovered element + overlap hint (strings rebuilt only when the target changes). */
    private void updateHover(int mouseX, int mouseY) {
        int hovered = -1;
        int count = 0;
        boolean interacting = draggingElement || draggingPlayhead || draggingKeyframe >= 0 || libDragIndex >= 0;
        if (!interacting && (inCanvas(mouseX, mouseY) || inPanelOverCanvas(mouseX, mouseY))) {
            double vx = toVirtualX(mouseX);
            double vy = toVirtualY(mouseY);
            List<HudElement> order = editRenderOrder();
            for (int i = order.size() - 1; i >= 0; i--) {
                HudElement e = order.get(i);
                if (!hitElement(e, vx, vy)) continue;
                if (count == 0) hovered = editList().indexOf(e);
                count++;
            }
        }
        hoveredElement = hovered;
        hoverStackCount = count;
        int signature = hovered * 131 + count;
        if (signature != hoverInfoSignature) {
            hoverInfoSignature = signature;
            if (hovered >= 0 && count > 1) {
                HudElement e = editList().get(hovered);
                String label = e.id.isEmpty() ? e.type.name() : e.id;
                hoverInfo = label + "  重叠×" + count + " (Alt+点击切换)";
                hoverInfoWidth = font.width(hoverInfo);
            } else {
                hoverInfo = "";
                hoverInfoWidth = 0;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (bigEditBox != null) {
            handleBigEditClick(mouseX, mouseY, button);
            return true;
        }
        if (handleTextureSuggestionClick(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            int divider = hoveredDivider(mouseX, mouseY);
            if (divider != 0) {
                resizingDivider = divider;
                return true;
            }
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 1) {
            // Right-click a text row to open the big multi-line editor
            for (int i = 0; i < bigEditableBoxes.size(); i++) {
                EditBox box = bigEditableBoxes.get(i);
                if (box.visible && box.isMouseOver(mouseX, mouseY)) {
                    openBigEdit(box);
                    return true;
                }
            }
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (inPalette(mouseX, mouseY) && button == 0) {
            int idx = paletteScroll + (int) ((mouseY - paletteRowsTop()) / PALETTE_ROW_H);
            if (idx >= 0 && idx < PALETTE_ENTRIES.length) {
                addPaletteEntry(PALETTE_ENTRIES[idx][1]);
                return true;
            }
            // Empty palette rows fall through to covered-canvas selection
        }

        if (inLibraryList(mouseX, mouseY) && button == 0) {
            int idx = libScroll + (int) ((mouseY - (TOP_H + 16)) / LIB_ROW_H);
            if (idx >= 0 && idx < libraryIds.size()) {
                long now = Util.getMillis();
                if (idx == libLastClickIndex && now - libLastClickMillis < 350) {
                    loadTemplate(libraryIds.get(idx));
                } else {
                    libSelected = idx;
                    libDragIndex = idx;
                    libDragging = false;
                }
                libLastClickIndex = idx;
                libLastClickMillis = now;
                return true;
            }
            // Empty library rows fall through to covered-canvas selection
        }

        if (inTimelineRuler(mouseX, mouseY)) {
            int lifetime = previewLifetime();
            // Keyframe diamond hit?
            HudElement e = selectedElementOrNull();
            if (e != null && button == 0) {
                for (int i = e.keyframes.size() - 1; i >= 0; i--) {
                    int x = tickToX(e.keyframes.get(i).tick, lifetime);
                    if (Math.abs(mouseX - x) <= 3) {
                        selectedKeyframe = i;
                        armKeyframeDragCandidate(i, mouseX, mouseY);
                        playhead = e.keyframes.get(i).tick;
                        rightScroll = 0;
                        rebuildAll();
                        return true;
                    }
                }
            }
            if (button == 1 && e != null) {
                for (int i = e.keyframes.size() - 1; i >= 0; i--) {
                    int x = tickToX(e.keyframes.get(i).tick, lifetime);
                    if (Math.abs(mouseX - x) <= 3) {
                        pushUndo(null);
                        e.keyframes.remove(i);
                        e.compileTracks();
                        if (selectedKeyframe == i) selectedKeyframe = -1;
                        rebuildAll();
                        return true;
                    }
                }
            }
            playhead = xToTick(mouseX, lifetime);
            playing = false;
            draggingPlayhead = true;
            return true;
        }

        if (button == 0 && (inCanvas(mouseX, mouseY) || inPanelOverCanvas(mouseX, mouseY))) {
            // Inside the canvas a miss deselects; under a panel a miss falls
            // back to doing nothing so panel labels stay safe to click.
            boolean insideCanvas = inCanvas(mouseX, mouseY);
            if (handleCanvasSelect(mouseX, mouseY, insideCanvas)) {
                return true;
            }
            return insideCanvas;
        }
        return false;
    }

    /** Side-panel strips that may cover canvas elements (click fall-through). */
    private boolean inPanelOverCanvas(double x, double y) {
        if (y < TOP_H || y >= height - TL_H) return false;
        return x < LEFT_W || x >= rightX();
    }

    /**
     * Element selection / multi-selection / drag start at a screen position.
     * Hit testing happens in virtual full-screen coordinates (viewport mapped).
     * Returns true when an element was hit, or when {@code allowDeselect} and
     * the click cleared the selection.
     */
    private boolean handleCanvasSelect(double mouseX, double mouseY, boolean allowDeselect) {
        double vx = toVirtualX(mouseX);
        double vy = toVirtualY(mouseY);
        // Collect all elements under the cursor, topmost first
        List<HudElement> order = editRenderOrder();
        List<HudElement> hits = new ArrayList<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            HudElement e = order.get(i);
            if (hitElement(e, vx, vy)) {
                hits.add(e);
            }
        }
        if (!hits.isEmpty()) {
            // Plain click: topmost (or keep current selection so it stays draggable).
            // Alt+click on the selected stack: cycle downwards through overlaps.
            // Ctrl+click / Shift+click: toggle multi-selection membership.
            HudElement current = selectedElementOrNull();
            boolean onCurrentStack = current != null && hits.contains(current);
            boolean multiToggle = hasShiftDown() || hasControlDown();
            HudElement pick;
            if (onCurrentStack && !multiToggle) {
                pick = hasAltDown()
                        ? hits.get((hits.indexOf(current) + 1) % hits.size())
                        : current;
            } else {
                pick = hits.get(0);
            }
            int realIndex = editList().indexOf(pick);

            // Double-click a GROUP to enter it
            long now = Util.getMillis();
            boolean doubleClick = realIndex == canvasLastClickElement && now - canvasLastClickMillis < 350;
            canvasLastClickElement = realIndex;
            canvasLastClickMillis = now;
            if (doubleClick && pick.type == HudElement.Type.GROUP && !multiToggle) {
                enterGroup(pick);
                return true;
            }

            if (multiToggle) {
                // Toggle in/out of the multi-selection set
                if (realIndex == selectedElement) {
                    selectedElement = extraSelected.isEmpty() ? -1
                            : extraSelected.iterator().next();
                    if (selectedElement >= 0) extraSelected.remove(selectedElement);
                } else if (!extraSelected.remove(realIndex)) {
                    if (selectedElement < 0) {
                        selectedElement = realIndex;
                    } else {
                        extraSelected.add(realIndex);
                    }
                }
                selectedKeyframe = -1;
                rightScroll = 0;
                rebuildAll();
                int total = (selectedElement >= 0 ? 1 : 0) + extraSelected.size();
                setStatus("已选 " + total + " 个元素（G 键或右侧面板成组）");
                armElementDragCandidate(mouseX, mouseY, vx, vy);
                return true;
            }

            if (hits.size() > 1) {
                String label = pick.id.isEmpty() ? pick.type.name() : pick.id;
                setStatus("选中 " + label + " (" + (hits.indexOf(pick) + 1) + "/" + hits.size() + "，Alt+点击切换)");
            }
            if (realIndex != selectedElement) {
                selectedElement = realIndex;
                selectedKeyframe = -1;
                extraSelected.clear();
                rightScroll = 0;
                rebuildAll();
            }
            // plain click on the primary keeps any multi-selection for dragging
            armElementDragCandidate(mouseX, mouseY, vx, vy);
            return true;
        }
        if (!allowDeselect) {
            return false;
        }
        canvasLastClickElement = -1;
        if (selectedElement != -1 || !extraSelected.isEmpty()) {
            selectedElement = -1;
            selectedKeyframe = -1;
            extraSelected.clear();
            rightScroll = 0;
            rebuildAll();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (bigEditBox != null) {
            bigEditBox.mouseDragged(mouseX, mouseY, button, dragX, dragY);
            return true;
        }
        if (resizingDivider != 0) {
            resizeDividerTo(mouseX, mouseY);
            return true;
        }
        if (libDragIndex >= 0) {
            if (!libDragging && (Math.abs(mouseX - lastMouseX) > 4 || Math.abs(mouseY - lastMouseY) > 4)) {
                libDragging = true;
            }
            return true;
        }
        if (draggingPlayhead) {
            playhead = xToTick(mouseX, previewLifetime());
            return true;
        }
        if (pendingKeyframeDrag >= 0) {
            if (!dragThresholdReached(mouseX, mouseY)) {
                return true;
            }
            draggingKeyframe = pendingKeyframeDrag;
            pendingKeyframeDrag = -1;
        }
        if (draggingKeyframe >= 0) {
            HudElement e = selectedElementOrNull();
            if (e != null && draggingKeyframe < e.keyframes.size()) {
                if (dragUndoArmed) {
                    dragUndoArmed = false;
                    pushUndo(null);
                }
                HudKeyframe kf = e.keyframes.get(draggingKeyframe);
                kf.tick = xToTick(mouseX, previewLifetime());
                playhead = kf.tick;
                e.compileTracks();
            }
            return true;
        }
        if (pendingElementDrag) {
            if (!dragThresholdReached(mouseX, mouseY)) {
                return true;
            }
            pendingElementDrag = false;
            draggingElement = true;
        }
        if (draggingElement) {
            HudElement e = selectedElementOrNull();
            if (e != null) {
                if (dragUndoArmed) {
                    dragUndoArmed = false;
                    pushUndo(null);
                }
                float dx;
                float dy;
                if (dragGrabValid) {
                    // Idempotent placement: the element center chases the mouse
                    // (plus grab offset) in virtual coordinates, with center-line
                    // snapping (hold Alt to disable). No drift accumulates.
                    float targetCx = (float) (toVirtualX(mouseX) + dragGrabOffsetX);
                    float targetCy = (float) (toVirtualY(mouseY) + dragGrabOffsetY);
                    editRect(editRectScratch);
                    float rectCx = editRectScratch[0] + editRectScratch[2] / 2f;
                    float rectCy = editRectScratch[1] + editRectScratch[3] / 2f;
                    boolean snapEnabled = !hasAltDown();
                    snapActiveX = snapEnabled && Math.abs(targetCx - rectCx) <= SNAP_DISTANCE;
                    snapActiveY = snapEnabled && Math.abs(targetCy - rectCy) <= SNAP_DISTANCE;
                    if (snapActiveX) targetCx = rectCx;
                    if (snapActiveY) targetCy = rectCy;
                    HudTemplateOverlayRenderer.resolveLocalBoxInRect(e, renderTime,
                            editRectScratch[0], editRectScratch[1], editRectScratch[2], editRectScratch[3],
                            boxScratch);
                    dx = targetCx - (boxScratch[0] + boxScratch[2] / 2f);
                    dy = targetCy - (boxScratch[1] + boxScratch[3] / 2f);
                } else {
                    dx = (float) (dragX / viewportScale);
                    dy = (float) (dragY / viewportScale);
                }
                if (dx != 0f || dy != 0f) {
                    dragElementBy(e, dx, dy);
                    // Multi-selection drags as one unit
                    List<HudElement> list = editList();
                    for (int idx : extraSelected) {
                        if (idx >= 0 && idx < list.size() && list.get(idx) != e) {
                            dragElementBy(list.get(idx), dx, dy);
                        }
                    }
                }
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void armPendingDrag(double mouseX, double mouseY) {
        pendingDragStartMouseX = mouseX;
        pendingDragStartMouseY = mouseY;
    }

    private boolean dragThresholdReached(double mouseX, double mouseY) {
        return HudEditorDragThreshold.exceedsDragSlop(
                pendingDragStartMouseX, pendingDragStartMouseY, mouseX, mouseY, DRAG_START_SLOP);
    }

    private void armElementDragCandidate(double mouseX, double mouseY, double vx, double vy) {
        pendingKeyframeDrag = -1;
        draggingKeyframe = -1;
        draggingElement = false;
        pendingElementDrag = selectedElement >= 0;
        dragUndoArmed = pendingElementDrag;
        armPendingDrag(mouseX, mouseY);
        armElementDrag(vx, vy);
    }

    private void armKeyframeDragCandidate(int keyframeIndex, double mouseX, double mouseY) {
        pendingElementDrag = false;
        draggingElement = false;
        pendingKeyframeDrag = keyframeIndex;
        draggingKeyframe = -1;
        dragUndoArmed = true;
        armPendingDrag(mouseX, mouseY);
    }

    /** Record the grab offset (element box center minus virtual mouse) at drag start. */
    private void armElementDrag(double vx, double vy) {
        dragGrabValid = false;
        HudElement e = selectedElementOrNull();
        if (e == null) return;
        editRect(editRectScratch);
        HudTemplateOverlayRenderer.resolveLocalBoxInRect(e, renderTime,
                editRectScratch[0], editRectScratch[1], editRectScratch[2], editRectScratch[3], boxScratch);
        dragGrabOffsetX = (float) (boxScratch[0] + boxScratch[2] / 2f - vx);
        dragGrabOffsetY = (float) (boxScratch[1] + boxScratch[3] / 2f - vy);
        dragGrabValid = true;
    }

    /**
     * Canvas drag policy: with the playhead parked on one of the element's
     * keyframes the drag edits that keyframe only (no need to click its
     * diamond first); otherwise a position-animated element shifts its whole
     * motion path, and a static element just moves its base x/y.
     */
    private void dragElementBy(HudElement e, float dx, float dy) {
        HudKeyframe kf = keyframeAt(e, Math.round(playhead));
        if (kf != null) {
            Float kx = kf.get(HudKeyframe.PROP_X);
            Float ky = kf.get(HudKeyframe.PROP_Y);
            float baseX = kx != null ? kx : e.track(HudKeyframe.PROP_X).evaluate(playhead, e.x);
            float baseY = ky != null ? ky : e.track(HudKeyframe.PROP_Y).evaluate(playhead, e.y);
            kf.set(HudKeyframe.PROP_X, baseX + dx);
            kf.set(HudKeyframe.PROP_Y, baseY + dy);
            e.compileTracks();
        } else if (hasPositionTrack(e)) {
            shiftElement(e, dx, dy);
        } else {
            e.x += dx;
            e.y += dy;
        }
    }

    @Nullable
    private static HudKeyframe keyframeAt(HudElement e, int tick) {
        for (int i = 0; i < e.keyframes.size(); i++) {
            if (e.keyframes.get(i).tick == tick) return e.keyframes.get(i);
        }
        return null;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (bigEditBox != null) {
            bigEditBox.mouseReleased(mouseX, mouseY, button);
            return true;
        }
        if (libDragIndex >= 0) {
            if (libDragging && inCanvas(mouseX, mouseY)) {
                double vx = toVirtualX(mouseX);
                double vy = toVirtualY(mouseY);
                if (vx >= 0 && vx <= width && vy >= 0 && vy <= height) {
                    addTemplateElementAt(libraryIds.get(libDragIndex), vx, vy);
                }
            }
            libDragIndex = -1;
            libDragging = false;
            return true;
        }
        boolean wasInteracting = pendingElementDrag || draggingElement || draggingPlayhead
                || pendingKeyframeDrag >= 0 || draggingKeyframe >= 0 || resizingDivider != 0;
        if (draggingElement) {
            rebuildAll(); // refresh property boxes after canvas drag
        }
        resizingDivider = 0;
        pendingElementDrag = false;
        draggingElement = false;
        draggingPlayhead = false;
        pendingKeyframeDrag = -1;
        draggingKeyframe = -1;
        snapActiveX = false;
        snapActiveY = false;
        dragGrabValid = false;
        dragUndoArmed = false;
        if (wasInteracting) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (bigEditBox != null) {
            bigEditBox.mouseScrolled(mouseX, mouseY, delta);
            return true;
        }
        if (inRightPanel(mouseX, mouseY)) {
            int visible = Math.max(1, (height - TL_H - TOP_H - 6) / ROW_H);
            int max = Math.max(0, rightRows.size() - visible);
            rightScroll = Math.max(0, Math.min(max, rightScroll - (int) Math.signum(delta)));
            // Re-layout widgets at their new scroll offsets
            rebuildAllPreservingFocus();
            return true;
        }
        if (inPalette(mouseX, mouseY) || (mouseX < LEFT_W && mouseY >= paletteTop() && mouseY < height - TL_H)) {
            int max = Math.max(0, PALETTE_ENTRIES.length - paletteVisibleRows());
            paletteScroll = Math.max(0, Math.min(max, paletteScroll - (int) Math.signum(delta)));
            return true;
        }
        if (inLibraryList(mouseX, mouseY) || (mouseX < LEFT_W && mouseY >= TOP_H && mouseY < height - TL_H)) {
            int visible = Math.max(1, (libraryBottom() - (TOP_H + 16)) / LIB_ROW_H);
            int max = Math.max(0, libraryIds.size() - visible);
            libScroll = Math.max(0, Math.min(max, libScroll - (int) Math.signum(delta)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void rebuildAllPreservingFocus() {
        rebuildAll();
    }

    // ==================== Keyboard ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (bigEditBox != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeBigEdit(false);
                return true;
            }
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && hasControlDown()) {
                closeBigEdit(true);
                return true;
            }
            bigEditBox.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        if (textureSuggestionKeyPressed(keyCode)) {
            return true;
        }
        if (super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (getFocused() instanceof EditBox box && box.isFocused()) {
            return false;
        }
        if (hasControlDown()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_Z -> {
                    if (hasShiftDown()) redo(); else undo();
                    return true;
                }
                case GLFW.GLFW_KEY_Y -> {
                    redo();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    copySelectedElement();
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    pasteElement();
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    cutSelectedElement();
                    return true;
                }
                case GLFW.GLFW_KEY_D -> {
                    duplicateSelected();
                    return true;
                }
                case GLFW.GLFW_KEY_S -> {
                    saveToServer();
                    return true;
                }
            }
        }
        HudElement e = selectedElementOrNull();
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> {
                setPlaying(!playing);
                return true;
            }
            case GLFW.GLFW_KEY_K -> {
                addKeyframeAtPlayhead();
                return true;
            }
            // Bare keys: Ctrl+Shift+G triggers ModernUI's glyph-atlas dump
            // (log spam), so grouping lives on plain G / U instead.
            case GLFW.GLFW_KEY_G -> {
                if (!hasControlDown() && !hasAltDown()) {
                    groupSelected();
                    return true;
                }
            }
            case GLFW.GLFW_KEY_U -> {
                if (!hasControlDown() && !hasAltDown()) {
                    ungroupSelected();
                    return true;
                }
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (!groupPath.isEmpty()) {
                    exitGroup();
                    return true;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (selectedKeyframeOrNull() != null) {
                    deleteSelectedKeyframe();
                } else if (e != null || !extraSelected.isEmpty()) {
                    pushUndo(null);
                    List<HudElement> doomed = selectedElements();
                    editList().removeAll(doomed);
                    markEditOrderDirty();
                    selectedElement = -1;
                    selectedKeyframe = -1;
                    extraSelected.clear();
                    rebuildAll();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (e != null) { nudgeSelected(e, -1, 0); return true; }
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (e != null) { nudgeSelected(e, 1, 0); return true; }
            }
            case GLFW.GLFW_KEY_UP -> {
                if (e != null) { nudgeSelected(e, 0, -1); return true; }
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (e != null) { nudgeSelected(e, 0, 1); return true; }
            }
        }
        return false;
    }

    /** Arrow-key move; Shift = 10 px step. Rapid presses coalesce into one undo step. */
    private void nudgeSelected(HudElement e, float dx, float dy) {
        pushUndo("nudge:" + selectedElement);
        float step = hasShiftDown() ? 10f : 1f;
        e.x += dx * step;
        e.y += dy * step;
        rebuildAll();
    }

    @Override
    public void onClose() {
        if (!hasUnsavedTemplateChanges()) {
            super.onClose();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                HudEditorScreen.super.onClose();
            } else {
                minecraft.setScreen(this);
            }
        }, Component.literal("未保存的修改"),
                Component.literal("当前模板有未保存的修改，确定直接退出编辑器吗？")));
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (bigEditBox != null) {
            bigEditBox.charTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }
}
