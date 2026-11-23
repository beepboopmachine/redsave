package dev.jkcarino.extension.reddit.frontpage;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;
import kotlin.jvm.functions.Function0;

public class DownloadActionCallback implements Function0<Object> {
    private static volatile Object sUnitInstance;
    private static volatile boolean sUnitResolved;

    private final Object key;
    private final Runnable action;

    public DownloadActionCallback(Runnable action) {
        this(null, action);
    }

    public DownloadActionCallback(Object key, Runnable action) {
        this.key = key;
        this.action = action;
    }

    public static Object getUnitInstance() {
        if (!sUnitResolved) {
            try {
                Class<?> unitCls = Class.forName("kotlin.Unit");
                for (Field f : unitCls.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && f.getType() == unitCls) {
                        f.setAccessible(true);
                        Object val = f.get(null);
                        if (val != null) {
                            sUnitInstance = val;
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            sUnitResolved = true;
        }
        return sUnitInstance;
    }

    @Override
    public Object invoke() {
        if (action != null) {
            action.run();
        }
        return getUnitInstance();
    }

    @Override
    public int hashCode() {
        return key != null ? key.hashCode() : System.identityHashCode(action);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DownloadActionCallback)) return false;
        DownloadActionCallback other = (DownloadActionCallback) obj;
        if (key != null || other.key != null) {
            return Objects.equals(key, other.key);
        }
        return action == other.action;
    }
}
