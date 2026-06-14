package com.lootmatrix.customui.client;

import net.minecraft.client.gui.Font;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side data for objective points and custom titles.
 * Stores data received from server for rendering.
 */
@OnlyIn(Dist.CLIENT)
public class ObjectiveOverlayClientData {

    private static final ObjectiveOverlayClientData INSTANCE = new ObjectiveOverlayClientData();

    public static ObjectiveOverlayClientData getInstance() {
        return INSTANCE;
    }

    // ==================== Custom Titles ====================

    /** Active title displays */
    private final List<TitleDisplay> titles = new ArrayList<>();

    /** Active image title displays */
    private final List<ImageTitleDisplay> imageTitles = new ArrayList<>();

    // ==================== Title Methods ====================

    public void addTitle(TitleDisplay title) {
        titles.add(title);
    }

    public void addImageTitle(ImageTitleDisplay imageTitle) {
        imageTitles.add(imageTitle);
    }

    public void tickTitles(float deltaTimeMs) {
        float clampedDelta = Math.max(0f, Math.min(deltaTimeMs, 200f));
        titles.forEach(title -> title.update(clampedDelta));
        imageTitles.forEach(imageTitle -> imageTitle.update(clampedDelta));
        titles.removeIf(TitleDisplay::isExpired);
        imageTitles.removeIf(ImageTitleDisplay::isExpired);
    }

    public List<TitleDisplay> getTitles() {
        return titles;
    }

    public List<ImageTitleDisplay> getImageTitles() {
        return imageTitles;
    }

    public void clearTitles() {
        titles.clear();
        imageTitles.clear();
    }

    public void reset() {
        titles.clear();
        imageTitles.clear();
    }

    // ==================== Inner Classes ====================

    /**
     * A title/text display with animation.
     */
    public static class TitleDisplay {
        public final String text;
        public int color;
        public float alpha;
        public float scale;
        public float offsetX;
        public float offsetY;
        public final long fadeInMs;
        public final long stayMs;
        public final long fadeOutMs;
        public final int line; // Line number for multi-line support
        /** 9-grid screen anchor ordinal; -1 = legacy top-center layout. */
        public final int anchorId;
        /** 9-grid text origin ordinal; -1 = legacy centered text. */
        public final int originId;
        private float timerMs = 0f;
        private int cachedTextWidth = -1;

        public TitleDisplay(String text, int color, float alpha, float scale,
                           float offsetX, float offsetY,
                           long fadeInMs, long stayMs, long fadeOutMs, int line,
                           int anchorId, int originId) {
            this.text = text;
            this.color = color;
            this.alpha = alpha;
            this.scale = scale;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.fadeInMs = fadeInMs;
            this.stayMs = stayMs;
            this.fadeOutMs = fadeOutMs;
            this.line = line;
            this.anchorId = anchorId;
            this.originId = originId;
        }

        public void update(float deltaTimeMs) {
            timerMs += deltaTimeMs;
        }

        public int getTextWidth(Font font) {
            if (cachedTextWidth < 0) {
                cachedTextWidth = font.width(text);
            }
            return cachedTextWidth;
        }

        public boolean isExpired() {
            return timerMs > getTotalDuration();
        }

        public long getTotalDuration() {
            return Math.max(0L, fadeInMs) + Math.max(0L, stayMs) + Math.max(0L, fadeOutMs);
        }

        public float getCurrentAlpha() {
            float fadeIn = Math.max(0f, fadeInMs);
            float stay = Math.max(0f, stayMs);
            float fadeOut = Math.max(0f, fadeOutMs);
            float elapsed = timerMs;

            if (fadeIn > 0f && elapsed < fadeIn) {
                return alpha * (elapsed / fadeIn);
            }
            if (elapsed < fadeIn + stay) {
                return alpha;
            }
            if (fadeOut > 0f) {
                float fadeOutElapsed = elapsed - fadeIn - stay;
                return alpha * Math.max(0f, 1f - fadeOutElapsed / fadeOut);
            }
            return 0f;
        }
    }

    /**
     * An image title display with animation.
     */
    public static class ImageTitleDisplay {
        public final String iconPath;
        public final int size;
        public final float alpha;
        public final float offsetX;
        public final float offsetY;
        public final long fadeInMs;
        public final long stayMs;
        public final long fadeOutMs;
        /** 9-grid screen anchor ordinal; -1 = legacy top-center layout. */
        public final int anchorId;
        /** 9-grid image origin ordinal; -1 = legacy (horizontal center, top aligned). */
        public final int originId;
        private float timerMs = 0f;

        public ImageTitleDisplay(String iconPath, int size, float alpha,
                                  float offsetX, float offsetY,
                                  long fadeInMs, long stayMs, long fadeOutMs,
                                  int anchorId, int originId) {
            this.iconPath = iconPath;
            this.size = size;
            this.alpha = alpha;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.fadeInMs = fadeInMs;
            this.stayMs = stayMs;
            this.fadeOutMs = fadeOutMs;
            this.anchorId = anchorId;
            this.originId = originId;
        }

        public void update(float deltaTimeMs) {
            timerMs += deltaTimeMs;
        }

        public boolean isExpired() {
            return timerMs > getTotalDuration();
        }

        public long getTotalDuration() {
            return Math.max(0L, fadeInMs) + Math.max(0L, stayMs) + Math.max(0L, fadeOutMs);
        }

        public float getCurrentAlpha() {
            float fadeIn = Math.max(0f, fadeInMs);
            float stay = Math.max(0f, stayMs);
            float fadeOut = Math.max(0f, fadeOutMs);
            float elapsed = timerMs;

            if (fadeIn > 0f && elapsed < fadeIn) {
                return alpha * (elapsed / fadeIn);
            }
            if (elapsed < fadeIn + stay) {
                return alpha;
            }
            if (fadeOut > 0f) {
                float fadeOutElapsed = elapsed - fadeIn - stay;
                return alpha * Math.max(0f, 1f - fadeOutElapsed / fadeOut);
            }
            return 0f;
        }
    }
}
