package com.lootmatrix.customui.client;

import com.lootmatrix.customui.Main;
import com.lootmatrix.customui.config.WindowIconConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles custom window icon functionality.
 * Sets the game window icon to built-in mod icons or user-specified PNG images.
 * If no valid icons are found, falls back to the default Minecraft icon.
 *
 * <h2>Built-in Icon Paths (in mod resources):</h2>
 * <ul>
 *   <li>assets/customui/textures/icons/window/icon_32x32.png</li>
 *   <li>assets/customui/textures/icons/window/icon_64x64.png</li>
 * </ul>
 *
 * <h2>Custom Icon Path (in config folder):</h2>
 * <ul>
 *   <li>config/customui/icons/</li>
 * </ul>
 *
 * <h2>Supported Sizes:</h2>
 * <ul>
 *   <li>32x32 - Standard taskbar, window title bar</li>
 *   <li>64x64 - Windows 10/11 taskbar, ALT+TAB preview</li>
 * </ul>
 *
 * <h2>Performance Considerations:</h2>
 * <ul>
 *   <li>Icon loading is done only once during client initialization</li>
 *   <li>Uses lazy initialization pattern</li>
 *   <li>Memory is properly freed after icon is set</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class WindowIconHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ICONS_FOLDER = "customui/icons";

    // Built-in icon resource locations
    private static final ResourceLocation BUILTIN_ICON_32 = ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/icons/window/icon_32x32.png");
    private static final ResourceLocation BUILTIN_ICON_64 = ResourceLocation.fromNamespaceAndPath(Main.MODID, "textures/icons/window/icon_64x64.png");

    // Initialization flag to prevent multiple calls
    private static boolean initialized = false;

    /**
     * Initialize and apply custom window icon if enabled.
     * Called during client setup phase.
     * This method is idempotent - calling it multiple times has no additional effect.
     */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            applyCustomIcon();
        } catch (Exception e) {
            LOGGER.warn("[CustomUI] Failed to initialize window icon: {}", e.getMessage());
        }
    }

    /**
     * Apply custom window icon based on configuration.
     * If no valid icons are found, the default Minecraft icon is preserved.
     */
    public static void applyCustomIcon() {
        if (!WindowIconConfig.INSTANCE.enabled.get()) {
            LOGGER.debug("[CustomUI] Custom window icon is disabled");
            return;
        }

        List<IconData> icons = new ArrayList<>();

        if (WindowIconConfig.INSTANCE.useBuiltIn.get()) {
            // Load built-in icons from mod resources
            loadBuiltInIcons(icons);
        } else {
            // Load custom icons from config folder
            loadCustomIcons(icons);
        }

        if (icons.isEmpty()) {
            LOGGER.info("[CustomUI] No valid icon files found, keeping default Minecraft icon");
            return;
        }

        // Apply icons to window
        setWindowIcons(icons);
    }

    /**
     * Load built-in icons from mod resources.
     */
    private static void loadBuiltInIcons(List<IconData> icons) {
        Minecraft mc = Minecraft.getInstance();

        loadBuiltInIcon(mc, icons, BUILTIN_ICON_32, 32);
        loadBuiltInIcon(mc, icons, BUILTIN_ICON_64, 64);

        if (icons.isEmpty()) {
            LOGGER.info("[CustomUI] No built-in icons found. Default Minecraft icon will be used.");
            LOGGER.info("[CustomUI] Expected icon locations:");
            LOGGER.info("[CustomUI]   - {}", BUILTIN_ICON_32);
            LOGGER.info("[CustomUI]   - {}", BUILTIN_ICON_64);
        }
    }

    /**
     * Load a single built-in icon from mod resources.
     */
    private static void loadBuiltInIcon(Minecraft mc, List<IconData> icons, ResourceLocation location, int expectedSize) {
        try {
            Optional<Resource> resourceOpt = mc.getResourceManager().getResource(location);
            if (resourceOpt.isEmpty()) {
                LOGGER.debug("[CustomUI] Built-in icon not found: {}", location);
                return;
            }

            Resource resource = resourceOpt.get();
            try (InputStream is = resource.open()) {
                IconData iconData = loadIconFromStream(is);
                if (iconData != null) {
                    if (iconData.width != expectedSize || iconData.height != expectedSize) {
                        LOGGER.warn("[CustomUI] Built-in icon {} has unexpected size {}x{} (expected {}x{})",
                                location, iconData.width, iconData.height, expectedSize, expectedSize);
                    }
                    icons.add(iconData);
                    LOGGER.debug("[CustomUI] Loaded built-in icon: {} ({}x{})", location, iconData.width, iconData.height);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[CustomUI] Failed to load built-in icon {}: {}", location, e.getMessage());
        }
    }

    /**
     * Load custom icons from config folder.
     */
    private static void loadCustomIcons(List<IconData> icons) {
        Path iconsDir = getIconsDirectory();

        if (!Files.exists(iconsDir)) {
            try {
                Files.createDirectories(iconsDir);
                LOGGER.info("[CustomUI] Created icons directory: {}", iconsDir);
                LOGGER.info("[CustomUI] Please add your custom icon PNG files to this folder");
            } catch (IOException e) {
                LOGGER.error("[CustomUI] Failed to create icons directory: {}", e.getMessage());
            }
            return;
        }

        // Load custom icons (32, 64)
        loadCustomIcon(icons, WindowIconConfig.INSTANCE.customIcon32Path.get(), 32);
        loadCustomIcon(icons, WindowIconConfig.INSTANCE.customIcon64Path.get(), 64);

        if (icons.isEmpty()) {
            LOGGER.info("[CustomUI] No custom icon files found. Default Minecraft icon will be used.");
            LOGGER.info("[CustomUI] Place your icons in: {}", iconsDir.toAbsolutePath());
        }
    }

    /**
     * Load a single custom icon from the config folder.
     */
    private static void loadCustomIcon(List<IconData> icons, String filename, int expectedSize) {
        if (filename == null || filename.isEmpty()) {
            return;
        }

        Path iconPath = getIconsDirectory().resolve(filename);
        if (!Files.exists(iconPath)) {
            LOGGER.debug("[CustomUI] Custom icon not found: {}", iconPath);
            return;
        }

        try {
            IconData iconData = loadIconFromFile(iconPath);
            if (iconData != null) {
                if (iconData.width != expectedSize || iconData.height != expectedSize) {
                    LOGGER.warn("[CustomUI] Custom icon {} has unexpected size {}x{} (expected {}x{})",
                            filename, iconData.width, iconData.height, expectedSize, expectedSize);
                }
                icons.add(iconData);
                LOGGER.debug("[CustomUI] Loaded custom icon: {} ({}x{})", filename, iconData.width, iconData.height);
            }
        } catch (Exception e) {
            LOGGER.warn("[CustomUI] Failed to load custom icon {}: {}", filename, e.getMessage());
        }
    }

    /**
     * Get the icons directory path.
     */
    private static Path getIconsDirectory() {
        return FMLPaths.CONFIGDIR.get().resolve(ICONS_FOLDER);
    }

    /**
     * Load icon data from an input stream.
     */
    private static IconData loadIconFromStream(InputStream is) throws IOException {
        byte[] bytes = is.readAllBytes();
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes);
        buffer.flip();

        return loadIconFromBuffer(buffer);
    }

    /**
     * Load icon data from a file path.
     */
    private static IconData loadIconFromFile(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes);
        buffer.flip();

        return loadIconFromBuffer(buffer);
    }

    /**
     * Load icon data from a ByteBuffer.
     */
    private static IconData loadIconFromBuffer(ByteBuffer buffer) throws IOException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuf = stack.mallocInt(1);
            IntBuffer heightBuf = stack.mallocInt(1);
            IntBuffer channelsBuf = stack.mallocInt(1);

            // Load image with RGBA format (4 channels)
            ByteBuffer imageData = STBImage.stbi_load_from_memory(buffer, widthBuf, heightBuf, channelsBuf, 4);
            MemoryUtil.memFree(buffer);

            if (imageData == null) {
                String error = STBImage.stbi_failure_reason();
                throw new IOException("Failed to load image: " + error);
            }

            int width = widthBuf.get(0);
            int height = heightBuf.get(0);

            return new IconData(width, height, imageData);
        }
    }

    /**
     * Set window icons using GLFW.
     */
    private static void setWindowIcons(List<IconData> icons) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            LOGGER.warn("[CustomUI] Cannot set window icon: Window not available");
            freeIcons(icons);
            return;
        }

        long windowHandle = mc.getWindow().getWindow();
        if (windowHandle == 0) {
            LOGGER.warn("[CustomUI] Cannot set window icon: Invalid window handle");
            freeIcons(icons);
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer imageBuffer = GLFWImage.malloc(icons.size(), stack);

            for (int i = 0; i < icons.size(); i++) {
                IconData icon = icons.get(i);
                imageBuffer.position(i);
                imageBuffer.width(icon.width);
                imageBuffer.height(icon.height);
                imageBuffer.pixels(icon.data);
            }

            imageBuffer.position(0);
            GLFW.glfwSetWindowIcon(windowHandle, imageBuffer);
            LOGGER.info("[CustomUI] Successfully applied {} window icon(s)", icons.size());

        } catch (Exception e) {
            LOGGER.error("[CustomUI] Failed to set window icon: {}", e.getMessage());
        } finally {
            freeIcons(icons);
        }
    }

    /**
     * Free icon data buffers to prevent memory leaks.
     */
    private static void freeIcons(List<IconData> icons) {
        for (IconData icon : icons) {
            if (icon.data != null) {
                STBImage.stbi_image_free(icon.data);
            }
        }
        icons.clear();
    }

    /**
     * Internal class to hold icon data.
     */
    private static class IconData {
        final int width;
        final int height;
        final ByteBuffer data;

        IconData(int width, int height, ByteBuffer data) {
            this.width = width;
            this.height = height;
            this.data = data;
        }
    }
}






