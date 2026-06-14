package com.lootmatrix.customui.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Invokes client-only packet handlers reflectively so packet classes can stay
 * free of direct client-side class references and still be shipped in the
 * dedicated server JAR. Resolved methods are cached so repeated packets skip
 * the Class.forName/getDeclaredMethod lookup.
 */
public final class PacketReflectionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PacketReflectionExecutor.class);
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    private PacketReflectionExecutor() {}

    public static void invokeStatic(String className, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            String key = className + '#' + methodName;
            Method method = METHOD_CACHE.get(key);
            if (method == null) {
                Class<?> handlerClass = Class.forName(className);
                method = handlerClass.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);
                METHOD_CACHE.put(key, method);
            }
            method.invoke(null, args);
        } catch (Throwable t) {
            LOGGER.error("Failed to invoke client packet bridge {}#{}", className, methodName, t);
        }
    }
}
