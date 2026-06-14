package com.lootmatrix.customui.client.loading;

import com.lootmatrix.customui.StartupProgress;
import com.lootmatrix.customui.mixin.ForgeLoadingOverlayAccessor;
import com.lootmatrix.customui.mixin.LoadingOverlayAccessor;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.fml.ModList;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Themed replacement visuals for the Forge/vanilla loading overlay.
 *
 * The original overlay (usually ForgeLoadingOverlay) is kept as a delegate and
 * rendered first each frame so it continues to drive mod loading, the resource
 * reload state machine, fade timing and the early-window lifecycle. This class
 * then paints the CustomUI theme on top: dark background, accent progress bar,
 * percentage / elapsed time / memory readouts and a scrolling detailed load
 * log fed by {@link StartupProgress}.
 *
 * Note: the very first seconds of startup (before the Minecraft window exists)
 * are drawn by the FML early window on the boot layer and cannot be restyled
 * from mod code; our detailed messages still appear there via
 * StartupNotificationManager.
 */
@OnlyIn(Dist.CLIENT)
public class CustomLoadingOverlay extends LoadingOverlay {

    private static final int BACKGROUND_RGB = 0x10141A;
    private static final int ACCENT_RGB = 0x4FC3F7;
    private static final int BAR_BG_RGB = 0x1D262E;
    private static final int TEXT_MAIN_RGB = 0xE6EDF3;
    private static final int TEXT_DIM_RGB = 0x8FA1AC;

    private static final int LOG_LINES = 12;
    private static final long LOG_FADE_START_MS = 6000;
    /** Minimum delay between two "printed" log lines (typewriter pacing). */
    private static final long REVEAL_INTERVAL_MS = 90;

    private final Minecraft minecraft;
    private final LoadingOverlay delegate;
    private final ReloadInstance reload;
    private final long openedMillis = Util.getMillis();
    private final String versionLine;

    private float smoothedProgress;

    // Signature-cached readout strings (avoid per-frame formatting)
    private int cachedPercent = -1;
    private String percentText = "0%";
    private long cachedElapsedSeconds = -1;
    private String elapsedText = "";
    private long cachedMemMb = -1;
    private String memoryText = "";

    private final String[] logMessages = new String[LOG_LINES];
    private final long[] logTimestamps = new long[LOG_LINES];

    // Progress-driven log printing: messages buffered before the overlay opened
    // are revealed as the real load progress advances; later messages print as
    // they arrive, both paced like a terminal printout.
    private long revealBaselineTotal = -1;
    private long revealedTotal = 0;
    private long lastRevealMillis = 0;

    /** One-shot trigger for the title scene entrance flight (LabyMod-style). */
    private boolean fadeOutNotified = false;

    /** Wraps any plain loading overlay; passes through null and already-wrapped overlays. */
    @Nullable
    public static Overlay wrap(@Nullable Overlay overlay) {
        if (!(overlay instanceof LoadingOverlay loading) || overlay instanceof CustomLoadingOverlay) {
            return overlay;
        }
        return new CustomLoadingOverlay(Minecraft.getInstance(), loading);
    }

    private CustomLoadingOverlay(Minecraft minecraft, LoadingOverlay delegate) {
        super(minecraft,
                ((LoadingOverlayAccessor) delegate).customui$getReload(),
                ((LoadingOverlayAccessor) delegate).customui$getOnFinish(),
                ((LoadingOverlayAccessor) delegate).customui$getFadeIn());
        this.minecraft = minecraft;
        this.delegate = delegate;
        this.reload = ((LoadingOverlayAccessor) delegate).customui$getReload();
        this.versionLine = "Custom UI " + ModList.get().getModContainerById("customui")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("dev");
    }

    private static final Matrix4f GUI_PROJECTION = new Matrix4f();

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Delegate first: drives loading, reload completion, fades and overlay removal.
        delegate.render(graphics, mouseX, mouseY, partialTick);

        // ForgeLoadingOverlay blits the early-window framebuffer with its own
        // pixel-space, y-flipped ortho projection and leaves that state behind.
        // Re-assert the standard GUI matrices or our layer renders inverted and
        // only covers a corner of the screen.
        Window window = minecraft.getWindow();
        GUI_PROJECTION.setOrtho(0.0F,
                (float) (window.getWidth() / window.getGuiScale()),
                (float) (window.getHeight() / window.getGuiScale()),
                0.0F, 1000.0F, ForgeHooksClient.getGuiFarPlane());
        RenderSystem.setProjectionMatrix(GUI_PROJECTION, VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0.0D, 0.0D, 1000.0F - ForgeHooksClient.getGuiFarPlane());
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        try {
            renderThemedLayer(graphics);
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private void renderThemedLayer(GuiGraphics graphics) {
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        long now = Util.getMillis();

        LoadingOverlayAccessor accessor = (LoadingOverlayAccessor) delegate;
        // ForgeLoadingOverlay shadows the vanilla fadeOutStart field; read the
        // one the delegate actually updates or the fade-out is never detected
        // and the overlay hard-cuts to the title screen instead of fading.
        long fadeOutStart = delegate instanceof ForgeLoadingOverlayAccessor forge
                ? forge.customui$getForgeFadeOutStart()
                : accessor.customui$getFadeOutStart();
        long fadeInStart = accessor.customui$getFadeInStart();
        if (fadeOutStart > -1L && !fadeOutNotified) {
            // Bake + start the 3D entrance flight while the cover is still opaque
            fadeOutNotified = true;
            com.lootmatrix.customui.client.title.TitleSceneManager.onLoadingFadeOutStarted();
        }
        float fadeOutTime = fadeOutStart > -1L ? (now - fadeOutStart) / 1000f : -1f;
        float fadeInTime = fadeInStart > -1L ? (now - fadeInStart) / 500f : -1f;

        float fillAlpha;
        float contentAlpha;
        if (fadeOutTime >= 1f) {
            // Delegate renders the title screen below during [1s, 2s] and removes
            // itself at 2s; ease our cover away in the same window so the menu
            // fades in smoothly instead of popping.
            float reveal = smoothstep(Mth.clamp(fadeOutTime - 1f, 0f, 1f));
            // Text/bar dissolve slightly ahead of the backdrop for a layered fade
            float contentReveal = smoothstep(Mth.clamp((fadeOutTime - 1f) / 0.7f, 0f, 1f));
            fillAlpha = 1f - reveal;
            contentAlpha = 1f - contentReveal;
        } else if (accessor.customui$getFadeIn() && fadeInStart > -1L) {
            fillAlpha = Mth.clamp(fadeInTime, 0.15f, 1f);
            contentAlpha = Mth.clamp(fadeInTime, 0f, 1f);
        } else {
            fillAlpha = 1f;
            contentAlpha = 1f;
        }

        int fillAlphaInt = Mth.ceil(fillAlpha * 255f);
        if (fillAlphaInt >= 4) {
            graphics.fill(RenderType.guiOverlay(), 0, 0, width, height,
                    (fillAlphaInt << 24) | BACKGROUND_RGB);
        }
        if (contentAlpha < 0.02f) {
            return;
        }

        float actual = reload.getActualProgress();
        smoothedProgress = Mth.clamp(smoothedProgress * 0.95f + actual * 0.05f, 0f, 1f);

        drawContent(graphics, width, height, now, contentAlpha);
    }

    /** Hermite smoothstep for fade easing. */
    private static float smoothstep(float t) {
        return t * t * (3f - 2f * t);
    }

    private void drawContent(GuiGraphics graphics, int width, int height, long now, float contentAlpha) {
        Font font = minecraft.font;
        int alphaInt = Math.max(4, Math.round(contentAlpha * 255f));
        int main = (alphaInt << 24) | TEXT_MAIN_RGB;
        int dim = (alphaInt << 24) | TEXT_DIM_RGB;
        int accent = (alphaInt << 24) | ACCENT_RGB;

        int centerX = width / 2;
        int centerY = height / 2;

        // Title (2x scale)
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY - 44f, 0f);
        graphics.pose().scale(2f, 2f, 1f);
        String title = "C U S T O M  U I";
        graphics.drawString(font, title, -font.width(title) / 2, 0, main, false);
        graphics.pose().popPose();
        graphics.drawString(font, versionLine, centerX - font.width(versionLine) / 2, centerY - 22, dim, false);

        // Progress bar
        int barWidth = Math.min(width - 80, 360);
        int barLeft = centerX - barWidth / 2;
        int barTop = centerY;
        int barHeight = 8;
        graphics.fill(barLeft - 2, barTop - 2, barLeft + barWidth + 2, barTop + barHeight + 2,
                (alphaInt << 24) | BAR_BG_RGB);
        // 1px accent frame
        graphics.fill(barLeft - 2, barTop - 2, barLeft + barWidth + 2, barTop - 1, accent);
        graphics.fill(barLeft - 2, barTop + barHeight + 1, barLeft + barWidth + 2, barTop + barHeight + 2, accent);
        graphics.fill(barLeft - 2, barTop - 1, barLeft - 1, barTop + barHeight + 1, accent);
        graphics.fill(barLeft + barWidth + 1, barTop - 1, barLeft + barWidth + 2, barTop + barHeight + 1, accent);
        int fillWidth = Math.round(barWidth * smoothedProgress);
        if (fillWidth > 0) {
            graphics.fill(barLeft, barTop, barLeft + fillWidth, barTop + barHeight, accent);
        }

        // Readouts (signature-cached strings)
        int percent = Math.round(smoothedProgress * 100f);
        if (percent != cachedPercent) {
            cachedPercent = percent;
            percentText = percent + "%";
        }
        long elapsedSeconds = (now - openedMillis) / 1000L;
        if (elapsedSeconds != cachedElapsedSeconds) {
            cachedElapsedSeconds = elapsedSeconds;
            elapsedText = "Elapsed: " + elapsedSeconds + "s";
        }
        long memMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        if (memMb != cachedMemMb) {
            cachedMemMb = memMb;
            memoryText = "Heap: " + memMb + " MB / " + (Runtime.getRuntime().maxMemory() >> 20) + " MB";
        }
        graphics.drawString(font, percentText, centerX - font.width(percentText) / 2, barTop + barHeight + 8, main, false);
        graphics.drawString(font, elapsedText, centerX - font.width(elapsedText) / 2, barTop + barHeight + 20, dim, false);
        graphics.drawString(font, memoryText, centerX - font.width(memoryText) / 2, barTop + barHeight + 30, dim, false);

        String phase = reload.isDone() ? "Finalizing..." : "Loading mods & resources...";
        graphics.drawString(font, phase, centerX - font.width(phase) / 2, barTop + barHeight + 44, dim, false);

        // Detailed startup log (bottom-left, newest at the bottom, age fade).
        // Lines "print" progressively: messages buffered before this overlay
        // opened unlock as the real load progress advances, live messages print
        // as they arrive, both paced like a terminal printout.
        long totalLogged = StartupProgress.totalLogged();
        if (revealBaselineTotal < 0) {
            revealBaselineTotal = totalLogged;
        }
        long unlockedBaseline = Math.min(revealBaselineTotal,
                (long) Math.ceil(revealBaselineTotal * Mth.clamp(smoothedProgress * 1.12f, 0f, 1f)));
        long revealTarget = unlockedBaseline + Math.max(0L, totalLogged - revealBaselineTotal);
        if (revealedTotal < revealTarget && now - lastRevealMillis >= REVEAL_INTERVAL_MS) {
            // One line per interval, catching up faster when far behind
            revealedTotal = Math.min(revealTarget, revealedTotal + 1 + (revealTarget - revealedTotal) / 6);
            lastRevealMillis = now;
        }

        int count = StartupProgress.copyRecent(logMessages, logTimestamps);
        // logMessages[count-1] is global line (totalLogged-1); hide lines not printed yet
        int visible = (int) Math.max(0L, count - Math.max(0L, totalLogged - revealedTotal));
        int y = height - 14;
        int minY = centerY + barHeight + 58;
        for (int i = visible - 1; i >= 0; i--) {
            if (y < minY) break;
            long age = now - logTimestamps[i];
            float ageFade = 1f - Mth.clamp((age - LOG_FADE_START_MS) / 4000f, 0f, 0.65f);
            int lineAlpha = Math.max(4, Math.round(alphaInt * ageFade));
            graphics.drawString(font, logMessages[i], 8, y, (lineAlpha << 24) | TEXT_DIM_RGB, false);
            if (i == visible - 1 && (now / 450L) % 2L == 0L) {
                // Blinking caret after the newest printed line
                graphics.drawString(font, "_", 8 + font.width(logMessages[i]) + 2, y,
                        (alphaInt << 24) | ACCENT_RGB, false);
            }
            y -= 10;
        }
        if (visible > 0) {
            graphics.drawString(font, "Startup log (details in latest.log):", 8, y - 2, (alphaInt << 24) | ACCENT_RGB, false);
        }
    }
}
