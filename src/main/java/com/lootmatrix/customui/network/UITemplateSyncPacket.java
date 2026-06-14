package com.lootmatrix.customui.network;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lootmatrix.customui.ui.UITemplate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Server → Client packet for the UI template system.
 * OPEN:   Sends template + resolved variables, client opens TemplateScreen.
 * CLOSE:  Client closes the active TemplateScreen.
 * EDITOR: Client opens TemplateEditor with the provided template.
 */
public class UITemplateSyncPacket {

    public enum Action { OPEN, CLOSE, EDITOR }

    private final Action action;
    @Nullable private final String templateJson;
    private final Map<String, String> variables;

    /** CLOSE constructor. */
    public UITemplateSyncPacket(Action action) {
        this.action = action;
        this.templateJson = null;
        this.variables = Map.of();
    }

    /** OPEN / EDITOR constructor. */
    public UITemplateSyncPacket(Action action, UITemplate template, Map<String, String> vars) {
        this.action = action;
        this.templateJson = new GsonBuilder().create().toJson(template.toJson());
        this.variables = vars;
    }

    /** Decode constructor. */
    public UITemplateSyncPacket(FriendlyByteBuf buf) {
        this.action = Action.values()[buf.readByte() % Action.values().length];
        boolean hasTemplate = buf.readBoolean();
        this.templateJson = hasTemplate ? buf.readUtf(65536) : null;
        int varCount = buf.readVarInt();
        this.variables = new HashMap<>();
        for (int i = 0; i < varCount; i++) {
            variables.put(buf.readUtf(256), buf.readUtf(1024));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(action.ordinal());
        buf.writeBoolean(templateJson != null);
        if (templateJson != null) buf.writeUtf(templateJson, 65536);
        buf.writeVarInt(variables.size());
        for (var entry : variables.entrySet()) {
            buf.writeUtf(entry.getKey(), 256);
            buf.writeUtf(entry.getValue(), 1024);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context ctx = contextSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            switch (action) {
                case OPEN:
                    if (templateJson != null) {
                        PacketReflectionExecutor.invokeStatic(
                                "com.lootmatrix.customui.network.PacketClientBridge",
                                "handleUITemplateOpen",
                                new Class<?>[]{String.class, java.util.Map.class},
                                templateJson, variables
                        );
                    }
                    break;
                case CLOSE:
                    PacketReflectionExecutor.invokeStatic(
                            "com.lootmatrix.customui.network.PacketClientBridge",
                            "handleUITemplateClose",
                            new Class<?>[]{},
                            new Object[]{}
                    );
                    break;
                case EDITOR:
                    if (templateJson != null) {
                        PacketReflectionExecutor.invokeStatic(
                                "com.lootmatrix.customui.network.PacketClientBridge",
                                "handleUIEditorOpen",
                                new Class<?>[]{String.class},
                                templateJson
                        );
                    }
                    break;
            }
        }));
        ctx.setPacketHandled(true);
    }
}
