package com.appvolume.xposed;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;

/**
 * Small reflection helper.
 *
 * <p>The release build of the system ui was shrunk, so the data class of the
 * slider state has no constructor left and the fields of the view model are
 * final. Both are handled here: first the normal reflection is tried, and if
 * the runtime refuses it, the fields are written with {@code sun.misc.Unsafe}.
 */
final class Reflect {
    private static final String TAG = "AppVolumeReflect: ";

    private static final Object sUnsafe;
    private static final java.lang.reflect.Method sObjectFieldOffset;
    private static final java.lang.reflect.Method sPutObject;
    private static final java.lang.reflect.Method sPutInt;
    private static final java.lang.reflect.Method sPutFloat;
    private static final java.lang.reflect.Method sPutBoolean;
    private static final java.lang.reflect.Method sAllocateInstance;

    static {
        Object unsafe = null;
        java.lang.reflect.Method objectFieldOffset = null;
        java.lang.reflect.Method putObject = null;
        java.lang.reflect.Method putInt = null;
        java.lang.reflect.Method putFloat = null;
        java.lang.reflect.Method putBoolean = null;
        java.lang.reflect.Method allocateInstance = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            putObject = unsafeClass.getMethod("putObject", Object.class, long.class,
                    Object.class);
            putInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
            putFloat = unsafeClass.getMethod("putFloat", Object.class, long.class,
                    float.class);
            putBoolean = unsafeClass.getMethod("putBoolean", Object.class, long.class,
                    boolean.class);
            allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "unsafe not available: " + t);
        }
        sUnsafe = unsafe;
        sObjectFieldOffset = objectFieldOffset;
        sPutObject = putObject;
        sPutInt = putInt;
        sPutFloat = putFloat;
        sPutBoolean = putBoolean;
        sAllocateInstance = allocateInstance;
    }

    private Reflect() {
    }

    /** Creates an instance without calling a constructor. */
    static Object allocate(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (Throwable ignored) {
        }
        if (sUnsafe != null) {
            try {
                return sAllocateInstance.invoke(sUnsafe, type);
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
        return null;
    }

    /** Reads a field, also when it is private or final. */
    static Object get(Object target, String name) {
        try {
            Field field = field(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "get " + name + " failed: " + t);
            return null;
        }
    }

    /** Writes a field, also when it is private or final. */
    static boolean set(Object target, String name, Object value) {
        try {
            Field field = field(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
        }
        if (sUnsafe == null) {
            return false;
        }
        try {
            Field field = field(target.getClass(), name);
            long offset = (Long) sObjectFieldOffset.invoke(sUnsafe, field);
            Class<?> type = field.getType();
            if (type == int.class || type == Integer.class) {
                sPutInt.invoke(sUnsafe, target, offset,
                        value instanceof Number ? ((Number) value).intValue() : 0);
            } else if (type == float.class || type == Float.class) {
                sPutFloat.invoke(sUnsafe, target, offset,
                        value instanceof Number ? ((Number) value).floatValue() : 0f);
            } else if (type == boolean.class || type == Boolean.class) {
                sPutBoolean.invoke(sUnsafe, target, offset,
                        value instanceof Boolean && (Boolean) value);
            } else {
                sPutObject.invoke(sUnsafe, target, offset, value);
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "set " + name + " failed: " + t);
            return false;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
