package com.lootmatrix.customui.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-featured visual editor for UI templates.
 * Features: drag-and-drop, right-click context menu, complete property editing,
 * widget annotations, widget market, beautified UI.
 */
public class TemplateEditor extends Screen {

    private final UITemplate template;
    @Nullable private UIWidget selectedWidget;
    private boolean dragging = false;
    private float dragOffsetX, dragOffsetY;

    // Layout constants
    private static final int PALETTE_WIDTH = 90;
    private static final int PROPS_WIDTH = 180;
    private static final int TOOLBAR_HEIGHT = 22;

    // Market state
    private boolean marketOpen = false;
    private int marketScroll = 0;
    private String marketFilter = "All";

    // Context menu state
    private boolean contextMenuOpen = false;
    private int contextMenuX, contextMenuY;
    @Nullable private UIWidget contextMenuWidget;

    // Property panel scroll
    private int propsScroll = 0;
    private int propsContentHeight = 0;

    // Property edit boxes (dynamic based on widget type)
    private final List<EditBox> editBoxes = new ArrayList<>();
    private final List<PropField> propFields = new ArrayList<>();

    // Cached canvas transformation
    private float cachedScale, cachedOffsetX, cachedOffsetY;

    public TemplateEditor(UITemplate template) {
        super(Component.literal("UI Template Editor"));
        this.template = template;
    }

    private static class PropField {
        final String label;
        final String key;
        final EditBox box;
        PropField(String label, String key, EditBox box) {
            this.label = label;
            this.key = key;
            this.box = box;
        }
    }

    // ==================== Initialization ====================

    @Override
    protected void init() {
        editBoxes.clear();
        propFields.clear();
        propsScroll = 0;
        rebuildPropertyPanel();

        int bx = PALETTE_WIDTH + 6;
        addRenderableWidget(Button.builder(Component.literal("\u2714 Save"), b -> saveTemplate())
                .pos(bx, 3).size(48, 16).build());
        bx += 52;
        addRenderableWidget(Button.builder(Component.literal("\u2716 Del"), b -> deleteWidget())
                .pos(bx, 3).size(42, 16).build());
        bx += 46;
        addRenderableWidget(Button.builder(Component.literal("\u2726 Market"), b -> toggleMarket())
                .pos(bx, 3).size(58, 16).build());
        bx += 62;
        addRenderableWidget(Button.builder(Component.literal("\u25B2"), b -> moveWidgetUp())
                .pos(bx, 3).size(16, 16).build());
        bx += 18;
        addRenderableWidget(Button.builder(Component.literal("\u25BC"), b -> moveWidgetDown())
                .pos(bx, 3).size(16, 16).build());
    }

    // ==================== Property Panel Build ====================

    private void rebuildPropertyPanel() {
        for (PropField f : propFields) removeWidget(f.box);
        editBoxes.clear();
        propFields.clear();
        if (selectedWidget == null) return;

        int px = this.width - PROPS_WIDTH + 4;
        int bw = PROPS_WIDTH - 12;
        int hw = (bw - 4) / 2;
        int py = TOOLBAR_HEIGHT + 30 - propsScroll;

        // Core
        py = addField(px, py, bw, "ID", "id", selectedWidget.id);
        py = addPair(px, py, hw, "X", "x", ff(selectedWidget.x), "Y", "y", ff(selectedWidget.y));
        py = addPair(px, py, hw, "W", "w", ff(selectedWidget.w), "H", "h", ff(selectedWidget.h));
        py = addPair(px, py, hw, "OriginX", "originX", ff(selectedWidget.originX),
                "OriginY", "originY", ff(selectedWidget.originY));
        py = addField(px, py, bw, "visible [true/false]", "visible", String.valueOf(selectedWidget.visible));
        py = addField(px, py, bw, "Tooltip", "tooltip", selectedWidget.tooltip != null ? selectedWidget.tooltip : "");

        switch (selectedWidget.type) {
            case LABEL:
                py += 4;
                py = addField(px, py, bw, "Text", "text", selectedWidget.text != null ? selectedWidget.text : "");
                py = addField(px, py, bw, "Font Size", "fontSize", String.valueOf(selectedWidget.fontSize));
                py = addField(px, py, bw, "Text Color (hex)", "textColor", hc(selectedWidget.textColor));
                py = addField(px, py, bw, "Align [left/center/right]", "textAlign", selectedWidget.textAlign);
                py = addField(px, py, bw, "Shadow [true/false]", "shadow", String.valueOf(selectedWidget.textShadow));
                break;
            case IMAGE:
                py += 4;
                py = addField(px, py, bw, "Texture", "texture", selectedWidget.texture != null ? selectedWidget.texture : "");
                py = addPair(px, py, hw, "TexW", "texW", String.valueOf(selectedWidget.texW),
                        "TexH", "texH", String.valueOf(selectedWidget.texH));
                py = addField(px, py, bw, "UV [u0,v0,u1,v1]", "uv",
                        ff(selectedWidget.u0)+","+ff(selectedWidget.v0)+","+ff(selectedWidget.u1)+","+ff(selectedWidget.v1));
                py = addField(px, py, bw, "Scale Mode", "scaleMode", selectedWidget.scaleMode);
                break;
            case BUTTON:
                py += 4;
                py = addField(px, py, bw, "Label", "label", selectedWidget.label != null ? selectedWidget.label : "");
                py = addField(px, py, bw, "Texture", "texture", selectedWidget.texture != null ? selectedWidget.texture : "");
                py = addField(px, py, bw, "Normal Color", "normalColor", hc(selectedWidget.normalColor));
                py = addField(px, py, bw, "Hover Color", "hoverColor", hc(selectedWidget.hoverColor));
                py = addField(px, py, bw, "Press Color", "pressColor", hc(selectedWidget.pressColor));
                py += 4;
                String cmd = selectedWidget.onClick != null ? selectedWidget.onClick.command : "";
                String exe = selectedWidget.onClick != null ? selectedWidget.onClick.executor : "@s";
                boolean coe = selectedWidget.onClick != null && selectedWidget.onClick.closeOnExecute;
                int cd = selectedWidget.onClick != null ? selectedWidget.onClick.cooldownTicks : 0;
                py = addField(px, py, bw, "onClick Command", "onClick.command", cmd);
                py = addField(px, py, bw, "onClick Executor", "onClick.executor", exe);
                py = addField(px, py, bw, "closeOnExecute", "onClick.closeOnExecute", String.valueOf(coe));
                py = addField(px, py, bw, "Cooldown (ticks)", "onClick.cooldownTicks", String.valueOf(cd));
                break;
            case PROGRESS:
                py += 4;
                py = addPair(px, py, hw, "Value", "value", ff(selectedWidget.value), "Max", "max", ff(selectedWidget.max));
                py = addField(px, py, bw, "Bar Color", "barColor", hc(selectedWidget.barColor));
                py = addField(px, py, bw, "BG Color", "bgColor", hc(selectedWidget.bgColor));
                py = addField(px, py, bw, "Variable Bind", "variable", selectedWidget.variable != null ? selectedWidget.variable : "");
                break;
            case TOGGLE:
                py += 4;
                py = addField(px, py, bw, "Label", "label", selectedWidget.label != null ? selectedWidget.label : "");
                py = addField(px, py, bw, "State [true/false]", "state", String.valueOf(selectedWidget.toggleState));
                String tc = selectedWidget.onToggle != null ? selectedWidget.onToggle.command : "";
                String te = selectedWidget.onToggle != null ? selectedWidget.onToggle.executor : "@s";
                py = addField(px, py, bw, "onToggle Command", "onToggle.command", tc);
                py = addField(px, py, bw, "onToggle Executor", "onToggle.executor", te);
                break;
            case INPUT:
                py += 4;
                py = addField(px, py, bw, "Placeholder", "placeholder", selectedWidget.placeholder != null ? selectedWidget.placeholder : "");
                py = addField(px, py, bw, "Max Length", "maxLength", String.valueOf(selectedWidget.maxLength));
                String sc = selectedWidget.onSubmit != null ? selectedWidget.onSubmit.command : "";
                String se = selectedWidget.onSubmit != null ? selectedWidget.onSubmit.executor : "@s";
                py = addField(px, py, bw, "onSubmit Command", "onSubmit.command", sc);
                py = addField(px, py, bw, "onSubmit Executor", "onSubmit.executor", se);
                break;
            case ITEM_ICON:
                py += 4;
                py = addField(px, py, bw, "Item ID", "itemId", selectedWidget.itemId != null ? selectedWidget.itemId : "");
                py = addField(px, py, bw, "Count", "itemCount", String.valueOf(selectedWidget.itemCount));
                break;
            case PANEL:
                py += 4;
                py = addField(px, py, bw, "Scrollable [true/false]", "scrollable", String.valueOf(selectedWidget.scrollable));
                break;
            case DIVIDER:
                py += 4;
                py = addField(px, py, bw, "Color", "dividerColor", hc(selectedWidget.dividerColor));
                py = addField(px, py, bw, "Thickness", "dividerThickness", String.valueOf(selectedWidget.dividerThickness));
                break;
        }
        propsContentHeight = py + propsScroll - TOOLBAR_HEIGHT - 30 + 20;
    }

    private int addField(int x, int y, int w, String label, String key, String value) {
        EditBox box = new EditBox(this.font, x, y + 10, w, 14, Component.literal(label));
        box.setHint(Component.literal(label));
        box.setMaxLength(512);
        box.setValue(value);
        box.setResponder(val -> applyProp(key, val));
        addRenderableWidget(box);
        editBoxes.add(box);
        propFields.add(new PropField(label, key, box));
        return y + 28;
    }

    private int addPair(int x, int y, int hw, String l1, String k1, String v1, String l2, String k2, String v2) {
        EditBox b1 = new EditBox(this.font, x, y + 10, hw, 14, Component.literal(l1));
        b1.setHint(Component.literal(l1)); b1.setMaxLength(128); b1.setValue(v1);
        b1.setResponder(val -> applyProp(k1, val));
        addRenderableWidget(b1); editBoxes.add(b1); propFields.add(new PropField(l1, k1, b1));

        EditBox b2 = new EditBox(this.font, x + hw + 4, y + 10, hw, 14, Component.literal(l2));
        b2.setHint(Component.literal(l2)); b2.setMaxLength(128); b2.setValue(v2);
        b2.setResponder(val -> applyProp(k2, val));
        addRenderableWidget(b2); editBoxes.add(b2); propFields.add(new PropField(l2, k2, b2));
        return y + 28;
    }

    // ==================== Live Property Apply ====================

    private void applyProp(String key, String val) {
        if (selectedWidget == null) return;
        try {
            switch (key) {
                case "id": selectedWidget.id = val; break;
                case "x": if (!val.isEmpty()) selectedWidget.x = Float.parseFloat(val); break;
                case "y": if (!val.isEmpty()) selectedWidget.y = Float.parseFloat(val); break;
                case "w": if (!val.isEmpty()) selectedWidget.w = Float.parseFloat(val); break;
                case "h": if (!val.isEmpty()) selectedWidget.h = Float.parseFloat(val); break;
                case "originX": if (!val.isEmpty()) selectedWidget.originX = Float.parseFloat(val); break;
                case "originY": if (!val.isEmpty()) selectedWidget.originY = Float.parseFloat(val); break;
                case "visible": selectedWidget.visible = Boolean.parseBoolean(val); break;
                case "tooltip": selectedWidget.tooltip = val.isEmpty() ? null : val; break;
                case "text": selectedWidget.text = val.isEmpty() ? null : val; break;
                case "fontSize": if (!val.isEmpty()) selectedWidget.fontSize = Integer.parseInt(val); break;
                case "textColor": selectedWidget.textColor = parseHex(val); break;
                case "textAlign": selectedWidget.textAlign = val; break;
                case "shadow": selectedWidget.textShadow = Boolean.parseBoolean(val); break;
                case "texture": selectedWidget.texture = val.isEmpty() ? null : val; break;
                case "texW": if (!val.isEmpty()) selectedWidget.texW = Integer.parseInt(val); break;
                case "texH": if (!val.isEmpty()) selectedWidget.texH = Integer.parseInt(val); break;
                case "uv": { String[] p = val.split(","); if (p.length==4) {
                    selectedWidget.u0=Float.parseFloat(p[0].trim()); selectedWidget.v0=Float.parseFloat(p[1].trim());
                    selectedWidget.u1=Float.parseFloat(p[2].trim()); selectedWidget.v1=Float.parseFloat(p[3].trim()); } break; }
                case "scaleMode": selectedWidget.scaleMode = val; break;
                case "label": selectedWidget.label = val.isEmpty() ? null : val; break;
                case "normalColor": selectedWidget.normalColor = parseHex(val); break;
                case "hoverColor": selectedWidget.hoverColor = parseHex(val); break;
                case "pressColor": selectedWidget.pressColor = parseHex(val); break;
                case "value": if (!val.isEmpty()) selectedWidget.value = Float.parseFloat(val); break;
                case "max": if (!val.isEmpty()) selectedWidget.max = Float.parseFloat(val); break;
                case "barColor": selectedWidget.barColor = parseHex(val); break;
                case "bgColor": selectedWidget.bgColor = parseHex(val); break;
                case "variable": selectedWidget.variable = val.isEmpty() ? null : val; break;
                case "state": selectedWidget.toggleState = Boolean.parseBoolean(val); break;
                case "placeholder": selectedWidget.placeholder = val.isEmpty() ? null : val; break;
                case "maxLength": if (!val.isEmpty()) selectedWidget.maxLength = Integer.parseInt(val); break;
                case "itemId": selectedWidget.itemId = val.isEmpty() ? null : val; break;
                case "itemCount": if (!val.isEmpty()) selectedWidget.itemCount = Integer.parseInt(val); break;
                case "scrollable": selectedWidget.scrollable = Boolean.parseBoolean(val); break;
                case "dividerColor": selectedWidget.dividerColor = parseHex(val); break;
                case "dividerThickness": if (!val.isEmpty()) selectedWidget.dividerThickness = Integer.parseInt(val); break;
                case "onClick.command": ensureAction("onClick").command = val; break;
                case "onClick.executor": ensureAction("onClick").executor = val; break;
                case "onClick.closeOnExecute": ensureAction("onClick").closeOnExecute = Boolean.parseBoolean(val); break;
                case "onClick.cooldownTicks": if (!val.isEmpty()) ensureAction("onClick").cooldownTicks = Integer.parseInt(val); break;
                case "onToggle.command": ensureAction("onToggle").command = val; break;
                case "onToggle.executor": ensureAction("onToggle").executor = val; break;
                case "onSubmit.command": ensureAction("onSubmit").command = val; break;
                case "onSubmit.executor": ensureAction("onSubmit").executor = val; break;
            }
        } catch (NumberFormatException ignored) {}
    }

    private UITemplate.UIAction ensureAction(String type) {
        if (selectedWidget == null) return new UITemplate.UIAction();
        switch (type) {
            case "onClick":
                if (selectedWidget.onClick == null) selectedWidget.onClick = new UITemplate.UIAction();
                return selectedWidget.onClick;
            case "onToggle":
                if (selectedWidget.onToggle == null) selectedWidget.onToggle = new UITemplate.UIAction();
                return selectedWidget.onToggle;
            case "onSubmit":
                if (selectedWidget.onSubmit == null) selectedWidget.onSubmit = new UITemplate.UIAction();
                return selectedWidget.onSubmit;
        }
        return new UITemplate.UIAction();
    }

    // ==================== Rendering ====================

    @Override
    public void tick() { for (EditBox box : editBoxes) box.tick(); }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Background
        gfx.fill(0, 0, this.width, this.height, 0xFF16162a);
        // Toolbar
        gfx.fill(0, 0, this.width, TOOLBAR_HEIGHT, 0xFF252545);
        gfx.fill(0, TOOLBAR_HEIGHT - 1, this.width, TOOLBAR_HEIGHT, 0xFF3a3a6a);
        // Palette
        gfx.fill(0, TOOLBAR_HEIGHT, PALETTE_WIDTH, this.height, 0xFF1e1e3a);
        gfx.fill(PALETTE_WIDTH - 1, TOOLBAR_HEIGHT, PALETTE_WIDTH, this.height, 0xFF3a3a6a);
        renderPalette(gfx, mouseX, mouseY);
        // Props
        int propsX = this.width - PROPS_WIDTH;
        gfx.fill(propsX, TOOLBAR_HEIGHT, this.width, this.height, 0xFF1e1e3a);
        gfx.fill(propsX, TOOLBAR_HEIGHT, propsX + 1, this.height, 0xFF3a3a6a);
        renderPropsPanel(gfx);

        // Canvas
        updateCanvasTransform();
        int cr = (int)(cachedOffsetX + template.canvasWidth * cachedScale);
        int cb = (int)(cachedOffsetY + template.canvasHeight * cachedScale);
        gfx.fill((int)cachedOffsetX+3, (int)cachedOffsetY+3, cr+3, cb+3, 0x40000000);
        gfx.fill((int)cachedOffsetX, (int)cachedOffsetY, cr, cb, 0xFF2a2a50);
        drawOutline(gfx, (int)cachedOffsetX-1, (int)cachedOffsetY-1, cr+1, cb+1, 0xFF4a4a8a);
        drawGrid(gfx, cachedOffsetX, cachedOffsetY, cachedScale);

        // Widgets
        gfx.pose().pushPose();
        gfx.pose().translate(cachedOffsetX, cachedOffsetY, 0);
        gfx.pose().scale(cachedScale, cachedScale, 1f);
        float cmx = (mouseX - cachedOffsetX) / cachedScale;
        float cmy = (mouseY - cachedOffsetY) / cachedScale;

        for (UIWidget widget : template.widgets) {
            if (!widget.visible) continue;
            WidgetRenderer.render(gfx, widget, cmx, cmy, partialTick, template, this.font);
            float wx = widget.x - widget.w * widget.originX;
            float wy = widget.y - widget.h * widget.originY;
            renderAnnotation(gfx, widget, wx, wy);
            if (widget == selectedWidget) {
                drawOutline(gfx, (int)wx-1, (int)wy-1, (int)(wx+widget.w)+1, (int)(wy+widget.h)+1, 0xFF00FF00);
            }
        }
        gfx.pose().popPose();

        // Status
        String info = template.canvasWidth+"x"+template.canvasHeight+" | "+template.widgets.size()+" widgets";
        gfx.drawString(font, info, (int)cachedOffsetX, cb+4, 0xFF666688, false);

        super.render(gfx, mouseX, mouseY, partialTick);

        if (contextMenuOpen) renderContextMenu(gfx, mouseX, mouseY);
        if (marketOpen) renderMarketPanel(gfx, mouseX, mouseY);
    }

    private void updateCanvasTransform() {
        int csw = this.width - PALETTE_WIDTH - PROPS_WIDTH;
        int csh = this.height - TOOLBAR_HEIGHT;
        cachedScale = Math.min((float)csw / template.canvasWidth, (float)csh / template.canvasHeight) * 0.85f;
        cachedOffsetX = PALETTE_WIDTH + (csw - template.canvasWidth * cachedScale) / 2f;
        cachedOffsetY = TOOLBAR_HEIGHT + (csh - template.canvasHeight * cachedScale) / 2f;
    }

    private void renderPalette(GuiGraphics gfx, int mouseX, int mouseY) {
        int py = TOOLBAR_HEIGHT + 6;
        gfx.drawString(font, "\u00a7l\u00a7eWidgets", 6, py, 0xFFCCCCCC, false);
        py += 14;
        String[][] types = {
            {"LBL","Label","336699"}, {"IMG","Image","996633"}, {"BTN","Button","339966"},
            {"PRG","Progress","993366"}, {"TGL","Toggle","669933"}, {"INP","Input","663399"},
            {"ICO","Item Icon","996600"}, {"PNL","Panel","555577"}, {"DIV","Divider","777755"},
        };
        for (String[] e : types) {
            boolean hover = mouseX >= 4 && mouseX <= PALETTE_WIDTH-4 && mouseY >= py && mouseY < py+18;
            gfx.fill(4, py, PALETTE_WIDTH-4, py+18, hover ? 0x40FFFFFF : 0x18FFFFFF);
            gfx.fill(4, py, 8, py+18, (int)Long.parseLong("FF"+e[2], 16));
            gfx.drawString(font, e[1], 12, py+5, 0xFFDDDDDD, false);
            py += 20;
        }
        py += 6;
        gfx.fill(8, py, PALETTE_WIDTH-8, py+1, 0x30FFFFFF);
        py += 6;
        gfx.drawString(font, "\u00a78Right-click: menu", 4, py, 0xFF666666, false);
    }

    private void renderPropsPanel(GuiGraphics gfx) {
        int px = this.width - PROPS_WIDTH;
        int ty = TOOLBAR_HEIGHT + 4;
        if (selectedWidget == null) {
            gfx.drawString(font, "\u00a77Properties", px+6, ty, 0xFFAAAAAA, false);
            gfx.drawString(font, "\u00a78No widget selected", px+6, ty+14, 0xFF666666, false);
            return;
        }
        String ta = getTypeAbbr(selectedWidget.type);
        int tc = getTypeTagColor(selectedWidget.type);
        gfx.fill(px+4, ty, px+4+font.width("["+ta+"]")+4, ty+12, tc);
        gfx.drawString(font, "["+ta+"]", px+6, ty+2, 0xFFFFFFFF, true);
        gfx.drawString(font, " "+selectedWidget.type.name(), px+6+font.width("["+ta+"]")+4, ty+2, 0xFFCCCCCC, false);

        // Render labels above each field
        int ly = TOOLBAR_HEIGHT + 30 - propsScroll;
        for (PropField f : propFields) {
            if (ly >= TOOLBAR_HEIGHT+14 && ly < this.height)
                gfx.drawString(font, "\u00a77"+f.label, px+4, ly, 0xFF999999, false);
            ly += 28;
        }
    }

    private void renderAnnotation(GuiGraphics gfx, UIWidget w, float wx, float wy) {
        String ta = getTypeAbbr(w.type);
        String id = w.id.isEmpty() ? "" : " "+w.id;
        String txt = "["+ta+"]"+id;
        int tc = getTypeTagColor(w.type);
        gfx.pose().pushPose();
        gfx.pose().translate(wx, wy-7, 100);
        gfx.pose().scale(0.55f, 0.55f, 1f);
        gfx.fill(-2, -1, font.width(txt)+4, 10, tc);
        gfx.drawString(font, txt, 1, 0, 0xFFFFFFFF, true);
        gfx.pose().popPose();
    }

    static String getTypeAbbr(UIWidget.Type t) {
        switch(t) {
            case LABEL:return"LBL";case IMAGE:return"IMG";case BUTTON:return"BTN";
            case PROGRESS:return"PRG";case TOGGLE:return"TGL";case INPUT:return"INP";
            case ITEM_ICON:return"ICO";case PANEL:return"PNL";case DIVIDER:return"DIV";
            default:return"???";
        }
    }

    static int getTypeTagColor(UIWidget.Type t) {
        switch(t) {
            case LABEL:return 0xC0336699;case IMAGE:return 0xC0996633;case BUTTON:return 0xC0339966;
            case PROGRESS:return 0xC0993366;case TOGGLE:return 0xC0669933;case INPUT:return 0xC0663399;
            case ITEM_ICON:return 0xC0996600;case PANEL:return 0xC0555577;case DIVIDER:return 0xC0777755;
            default:return 0xC0555555;
        }
    }

    // ==================== Context Menu ====================

    private void renderContextMenu(GuiGraphics gfx, int mouseX, int mouseY) {
        if (contextMenuWidget == null) return;
        String[] items = {"\u270E Edit", "\u2398 Duplicate", "\u25B2 Forward", "\u25BC Backward", "\u00a7c\u2716 Delete", "\u2199 Copy JSON"};
        int mw = 100, ih = 16, mh = items.length*ih+4;
        int mx = Math.min(contextMenuX, this.width-mw);
        int my = Math.min(contextMenuY, this.height-mh);
        gfx.fill(mx+2, my+2, mx+mw+2, my+mh+2, 0x60000000);
        gfx.fill(mx, my, mx+mw, my+mh, 0xFF1a1a30);
        drawOutline(gfx, mx, my, mx+mw, my+mh, 0xFF4a4a8a);
        for (int i=0; i<items.length; i++) {
            int iy = my+2+i*ih;
            boolean h = mouseX>=mx && mouseX<=mx+mw && mouseY>=iy && mouseY<iy+ih;
            if (h) gfx.fill(mx+1, iy, mx+mw-1, iy+ih, 0x40FFFFFF);
            gfx.drawString(font, items[i], mx+6, iy+4, 0xFFDDDDDD, false);
        }
    }

    private boolean handleContextMenuClick(double mouseX, double mouseY) {
        if (contextMenuWidget == null) { contextMenuOpen = false; return true; }
        String[] acts = {"edit","duplicate","forward","backward","delete","copyJson"};
        int mw=100, ih=16, mh=acts.length*ih+4;
        int mx = Math.min(contextMenuX, this.width-mw);
        int my = Math.min(contextMenuY, this.height-mh);
        for (int i=0; i<acts.length; i++) {
            int iy = my+2+i*ih;
            if (mouseX>=mx && mouseX<=mx+mw && mouseY>=iy && mouseY<iy+ih) {
                execCtx(acts[i]); contextMenuOpen=false; return true;
            }
        }
        contextMenuOpen = false; return true;
    }

    private void execCtx(String a) {
        if (contextMenuWidget == null) return;
        switch(a) {
            case "edit":
                selectedWidget = contextMenuWidget; rebuildPropertyPanel(); break;
            case "duplicate":
                UIWidget c = UIWidget.fromJson(contextMenuWidget.toJson());
                c.id = c.id+"_copy"; c.x += 10; c.y += 10;
                template.widgets.add(c); selectedWidget = c; rebuildPropertyPanel(); break;
            case "forward": moveInList(contextMenuWidget, 1); break;
            case "backward": moveInList(contextMenuWidget, -1); break;
            case "delete":
                template.widgets.remove(contextMenuWidget);
                if (selectedWidget==contextMenuWidget) { selectedWidget=null; rebuildPropertyPanel(); }
                break;
            case "copyJson":
                String j = new GsonBuilder().setPrettyPrinting().create().toJson(contextMenuWidget.toJson());
                Minecraft.getInstance().keyboardHandler.setClipboard(j);
                if (Minecraft.getInstance().player!=null)
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal("\u00a7a[CustomUI] Widget JSON copied"));
                break;
        }
    }

    private void moveInList(UIWidget w, int dir) {
        int i = template.widgets.indexOf(w); if (i<0) return;
        int n = i+dir; if (n<0||n>=template.widgets.size()) return;
        template.widgets.remove(i); template.widgets.add(n, w);
    }

    // ==================== Market Panel ====================

    private void toggleMarket() {
        marketOpen = !marketOpen; contextMenuOpen = false;
        marketScroll = 0; marketFilter = "All";
        if (marketOpen) WidgetMarketRegistry.getInstance().loadIfNeeded();
    }

    private void renderMarketPanel(GuiGraphics gfx, int mouseX, int mouseY) {
        int pw=320, ph=Math.min(this.height-40,400);
        int px=(this.width-pw)/2, py=(this.height-ph)/2;
        gfx.fill(0,0,this.width,this.height, 0x80000000);
        gfx.fill(px+3,py+3,px+pw+3,py+ph+3, 0x60000000);
        drawOutline(gfx, px-1,py-1,px+pw+1,py+ph+1, 0xFF4a4a8a);
        gfx.fill(px,py,px+pw,py+ph, 0xFF1a1a2e);
        gfx.fill(px,py,px+pw,py+20, 0xFF252545);
        gfx.drawString(font, "\u00a7l\u00a76\u2726 Widget Market", px+8, py+6, 0xFFFFCC00, false);

        int cx=px+pw-18;
        boolean ch = mouseX>=cx && mouseX<=px+pw-4 && mouseY>=py+4 && mouseY<=py+18;
        gfx.drawString(font, "\u2715", cx, py+6, ch?0xFFFF6666:0xFFAAAAAA, false);
        int rx=cx-18;
        boolean rh = mouseX>=rx && mouseX<=rx+14 && mouseY>=py+4 && mouseY<=py+18;
        gfx.drawString(font, "\u21BB", rx, py+6, rh?0xFF88FF88:0xFFAAAAAA, false);

        int tabY=py+22, tabX=px+6;
        List<String> cats = new ArrayList<>();
        cats.add("All"); cats.addAll(WidgetMarketRegistry.getInstance().getCategories()); cats.add("Current");
        for (String cat : cats) {
            boolean sel = cat.equals(marketFilter);
            int tw = font.width(cat)+10;
            if (tabX+tw > px+pw-6) { tabX=px+6; tabY+=16; }
            boolean th = mouseX>=tabX && mouseX<=tabX+tw && mouseY>=tabY && mouseY<tabY+14;
            gfx.fill(tabX,tabY,tabX+tw,tabY+14, sel?0xFF3a3a8a:(th?0x50FFFFFF:0x25FFFFFF));
            if (sel) drawOutline(gfx, tabX,tabY,tabX+tw,tabY+14, 0xFF6666CC);
            gfx.drawString(font, cat, tabX+5, tabY+3, sel?0xFFFFFFFF:0xFFAAAAAA, false);
            tabX += tw+3;
        }

        int et=tabY+18, eh=30, eah=py+ph-et-4;
        List<WidgetMarketEntry> entries = getMarketEntries();
        int ms = Math.max(0, entries.size()*eh-eah);
        marketScroll = Math.max(0, Math.min(marketScroll, ms));
        gfx.enableScissor(px+2,et,px+pw-2,py+ph-2);
        for (int i=0; i<entries.size(); i++) {
            int ey=et+i*eh-marketScroll;
            if (ey+eh<et||ey>py+ph) continue;
            WidgetMarketEntry entry = entries.get(i);
            boolean hover = mouseX>=px+6 && mouseX<=px+pw-6 && mouseY>=Math.max(ey,et) && mouseY<ey+eh-2 && mouseY<py+ph-2;
            gfx.fill(px+6,ey,px+pw-6,ey+eh-2, hover?0x40FFFFFF:0x18FFFFFF);
            if (hover) drawOutline(gfx, px+6,ey,px+pw-6,ey+eh-2, 0x60FFFFFF);
            UIWidget.Type tt = entry.widgets.isEmpty()?UIWidget.Type.LABEL:entry.widgets.get(0).type;
            String tag = "["+getTypeAbbr(tt)+"]";
            int tc=getTypeTagColor(tt)|0xFF000000;
            gfx.fill(px+8,ey+2,px+8+font.width(tag)+4,ey+13, tc);
            gfx.drawString(font, tag, px+10, ey+3, 0xFFFFFFFF, true);
            gfx.drawString(font, entry.name, px+14+font.width(tag)+4, ey+3, 0xFFFFFFFF, false);
            if (entry.widgets.size()>1) gfx.drawString(font, "x"+entry.widgets.size(), px+pw-28, ey+3, 0xFF88AAFF, false);
            if (entry.description!=null && !entry.description.isEmpty()) {
                String d = entry.description;
                if (font.width(d)>pw-24) d = font.plainSubstrByWidth(d, pw-30)+"...";
                gfx.drawString(font, d, px+10, ey+16, 0xFF666688, false);
            }
        }
        gfx.disableScissor();
        if (ms>0 && eah>0) {
            int bh=Math.max(12,eah*eah/Math.max(1,entries.size()*eh));
            int by=et+(int)((float)marketScroll/ms*(eah-bh));
            gfx.fill(px+pw-5,by,px+pw-2,by+bh, 0x60FFFFFF);
        }
    }

    private List<WidgetMarketEntry> getMarketEntries() {
        if ("Current".equals(marketFilter)) {
            List<WidgetMarketEntry> r = new ArrayList<>();
            for (UIWidget w : template.widgets) {
                WidgetMarketEntry e = new WidgetMarketEntry();
                e.name = w.id.isEmpty()?w.type.name().toLowerCase():w.id;
                e.category = "Current";
                e.description = w.type.name()+" - click to duplicate";
                e.widgets = new ArrayList<>(); e.widgets.add(w); r.add(e);
            }
            return r;
        }
        if ("All".equals(marketFilter)) return WidgetMarketRegistry.getInstance().getEntries();
        return WidgetMarketRegistry.getInstance().getByCategory(marketFilter);
    }

    private void addFromMarket(WidgetMarketEntry entry) {
        float bx = template.canvasWidth/2f, by = template.canvasHeight/2f;
        for (UIWidget src : entry.widgets) {
            UIWidget cl = UIWidget.fromJson(src.toJson());
            cl.id = cl.id+"_"+template.widgets.size();
            cl.x = bx+cl.x; cl.y = by+cl.y;
            template.widgets.add(cl); selectedWidget = cl;
        }
        rebuildPropertyPanel(); marketOpen = false;
    }

    // ==================== Grid / Outline ====================

    private void drawGrid(GuiGraphics gfx, float ox, float oy, float s) {
        for (int gx=0; gx<=template.canvasWidth; gx+=10) {
            int sx=(int)(ox+gx*s);
            gfx.fill(sx,(int)oy,sx+1,(int)(oy+template.canvasHeight*s), gx%50==0?0x20FFFFFF:0x10FFFFFF);
        }
        for (int gy=0; gy<=template.canvasHeight; gy+=10) {
            int sy=(int)(oy+gy*s);
            gfx.fill((int)ox,sy,(int)(ox+template.canvasWidth*s),sy+1, gy%50==0?0x20FFFFFF:0x10FFFFFF);
        }
    }

    private void drawOutline(GuiGraphics gfx, int x1, int y1, int x2, int y2, int c) {
        gfx.fill(x1,y1,x2,y1+1,c); gfx.fill(x1,y2-1,x2,y2,c);
        gfx.fill(x1,y1,x1+1,y2,c); gfx.fill(x2-1,y1,x2,y2,c);
    }

    // ==================== Input Handling ====================

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (contextMenuOpen) {
            if (btn==0) return handleContextMenuClick(mx,my);
            contextMenuOpen=false; return true;
        }
        if (marketOpen && btn==0) return handleMarketClick(mx,my);
        if (super.mouseClicked(mx,my,btn)) return true;

        if (btn==1) {
            float[] cc = screenToCanvas(mx,my);
            contextMenuWidget = null;
            for (int i=template.widgets.size()-1; i>=0; i--) {
                UIWidget w = template.widgets.get(i);
                float wx=w.x-w.w*w.originX, wy=w.y-w.h*w.originY;
                if (cc[0]>=wx && cc[0]<=wx+w.w && cc[1]>=wy && cc[1]<=wy+w.h) {
                    contextMenuWidget=w; break;
                }
            }
            if (contextMenuWidget!=null) {
                contextMenuX=(int)mx; contextMenuY=(int)my; contextMenuOpen=true; return true;
            }
        }

        if (btn==0) {
            // Palette
            if (mx>=4 && mx<=PALETTE_WIDTH-4) {
                int py = TOOLBAR_HEIGHT+6+14;
                UIWidget.Type[] types = {UIWidget.Type.LABEL, UIWidget.Type.IMAGE, UIWidget.Type.BUTTON,
                    UIWidget.Type.PROGRESS, UIWidget.Type.TOGGLE, UIWidget.Type.INPUT,
                    UIWidget.Type.ITEM_ICON, UIWidget.Type.PANEL, UIWidget.Type.DIVIDER};
                for (UIWidget.Type t : types) {
                    if (my>=py && my<py+18) { addWidget(t); return true; }
                    py += 20;
                }
            }
            // Canvas selection
            float[] cc = screenToCanvas(mx,my);
            UIWidget prev = selectedWidget;
            selectedWidget = null;
            for (int i=template.widgets.size()-1; i>=0; i--) {
                UIWidget w = template.widgets.get(i);
                float wx=w.x-w.w*w.originX, wy=w.y-w.h*w.originY;
                if (cc[0]>=wx && cc[0]<=wx+w.w && cc[1]>=wy && cc[1]<=wy+w.h) {
                    selectedWidget=w; dragging=true;
                    dragOffsetX=cc[0]-w.x; dragOffsetY=cc[1]-w.y; break;
                }
            }
            if (selectedWidget!=prev) rebuildPropertyPanel();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (btn==0) dragging=false;
        return super.mouseReleased(mx,my,btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging && selectedWidget!=null && btn==0) {
            float[] c=screenToCanvas(mx,my);
            selectedWidget.x=Math.round((c[0]-dragOffsetX)/5f)*5;
            selectedWidget.y=Math.round((c[1]-dragOffsetY)/5f)*5;
            for (PropField f : propFields) {
                if("x".equals(f.key)) f.box.setValue(ff(selectedWidget.x));
                if("y".equals(f.key)) f.box.setValue(ff(selectedWidget.y));
            }
            return true;
        }
        return super.mouseDragged(mx,my,btn,dx,dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double d) {
        if (marketOpen) { marketScroll-=(int)(d*24); marketScroll=Math.max(0,marketScroll); return true; }
        if (mx >= this.width-PROPS_WIDTH) {
            propsScroll -= (int)(d*16);
            propsScroll = Math.max(0, Math.min(propsScroll, Math.max(0, propsContentHeight-(this.height-TOOLBAR_HEIGHT-40))));
            rebuildPropertyPanel(); return true;
        }
        return super.mouseScrolled(mx,my,d);
    }

    private float[] screenToCanvas(double sx, double sy) {
        updateCanvasTransform();
        return new float[]{(float)(sx-cachedOffsetX)/cachedScale, (float)(sy-cachedOffsetY)/cachedScale};
    }

    private boolean handleMarketClick(double mx, double my) {
        int pw=320, ph=Math.min(this.height-40,400);
        int px=(this.width-pw)/2, py=(this.height-ph)/2;
        if (mx<px||mx>px+pw||my<py||my>py+ph) { marketOpen=false; return true; }
        // Close
        int cx=px+pw-18;
        if (mx>=cx && mx<=px+pw-4 && my>=py+4 && my<=py+18) { marketOpen=false; return true; }
        // Reload
        int rx=cx-18;
        if (mx>=rx && mx<=rx+14 && my>=py+4 && my<=py+18) { WidgetMarketRegistry.getInstance().reload(); return true; }
        // Tabs
        int tabY=py+22, tabX=px+6;
        List<String> cats = new ArrayList<>();
        cats.add("All"); cats.addAll(WidgetMarketRegistry.getInstance().getCategories()); cats.add("Current");
        for (String cat : cats) {
            int tw=font.width(cat)+10;
            if (tabX+tw>px+pw-6) { tabX=px+6; tabY+=16; }
            if (mx>=tabX && mx<=tabX+tw && my>=tabY && my<tabY+14) { marketFilter=cat; marketScroll=0; return true; }
            tabX += tw+3;
        }
        // Entries
        int et=tabY+18, eh=30;
        List<WidgetMarketEntry> entries = getMarketEntries();
        for (int i=0; i<entries.size(); i++) {
            int ey=et+i*eh-marketScroll;
            if (my>=ey && my<ey+eh-2 && mx>=px+6 && mx<=px+pw-6 && my>=et && my<py+ph-2) {
                addFromMarket(entries.get(i)); return true;
            }
        }
        return true;
    }

    // ==================== Widget Operations ====================

    private void addWidget(UIWidget.Type type) {
        UIWidget w = new UIWidget();
        w.type = type;
        w.id = type.name().toLowerCase()+"_"+template.widgets.size();
        w.x = template.canvasWidth/2f; w.y = template.canvasHeight/2f;
        w.w = 80; w.h = 20;
        switch(type) {
            case LABEL: w.text="Text"; w.textColor=0xFFFFFFFF; break;
            case IMAGE: w.w=64; w.h=64; break;
            case BUTTON: w.label="Button"; w.w=80; w.h=24; break;
            case PROGRESS: w.w=120; w.h=12; w.value=50; w.max=100; break;
            case TOGGLE: w.label="Toggle"; w.w=30; w.h=16; break;
            case INPUT: w.w=120; w.h=20; w.placeholder="Enter text..."; break;
            case ITEM_ICON: w.w=16; w.h=16; w.itemId="minecraft:diamond"; break;
            case PANEL: w.w=100; w.h=80; w.children=new ArrayList<>(); break;
            case DIVIDER: w.w=200; w.h=1; w.dividerThickness=1; break;
        }
        template.widgets.add(w); selectedWidget=w; rebuildPropertyPanel();
    }

    private void deleteWidget() {
        if (selectedWidget!=null) { template.widgets.remove(selectedWidget); selectedWidget=null; rebuildPropertyPanel(); }
    }

    private void moveWidgetUp() { if (selectedWidget!=null) moveInList(selectedWidget,1); }
    private void moveWidgetDown() { if (selectedWidget!=null) moveInList(selectedWidget,-1); }

    private void saveTemplate() {
        String j = new GsonBuilder().setPrettyPrinting().create().toJson(template.toJson());
        Minecraft.getInstance().keyboardHandler.setClipboard(j);
        if (Minecraft.getInstance().player!=null)
            Minecraft.getInstance().player.sendSystemMessage(Component.literal("\u00a7a[CustomUI] Template JSON copied ("+j.length()+" chars)"));
    }

    @Override
    public boolean keyPressed(int k, int s, int m) {
        if (k==83 && (m&2)!=0) { saveTemplate(); return true; }
        if (k==261 && selectedWidget!=null) {
            if (editBoxes.stream().noneMatch(EditBox::isFocused)) { deleteWidget(); return true; }
        }
        if (k==256) {
            if (contextMenuOpen) { contextMenuOpen=false; return true; }
            if (marketOpen) { marketOpen=false; return true; }
        }
        return super.keyPressed(k,s,m);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ==================== Utilities ====================

    private static String hc(int argb) { return String.format("%08X", argb); }
    private static int parseHex(String s) {
        if (s==null||s.isEmpty()) return 0xFFFFFFFF;
        s=s.replace("#","").replace("0x","");
        try { return (int)Long.parseLong(s,16); } catch(NumberFormatException e) { return 0xFFFFFFFF; }
    }
    private static String ff(float v) { return v==(int)v ? String.valueOf((int)v) : String.format("%.1f",v); }
}
