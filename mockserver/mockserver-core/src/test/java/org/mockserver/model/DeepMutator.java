package org.mockserver.model;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Changes everything reachable from an object, as the most thorough extension could: every array element, every
 * collection and map, every JSON tree, and every non-final field of every model object, including private ones.
 * Members are changed before the fields that hold them, so the walk still reaches them. Immutable values are
 * replaced, never changed in place, so a copy that shares one is still a correct copy.
 */
final class DeepMutator {

    private final IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();

    private DeepMutator() {
    }

    static void mutateEverything(Object root) {
        new DeepMutator().mutate(root);
    }

    private void mutate(Object value) {
        if (value == null || DeepCopyWalker.isLeaf(value) || visited.put(value, Boolean.TRUE) != null) {
            return;
        }
        if (value.getClass().isArray()) {
            mutateArray(value);
        } else if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            new ArrayList<>(collection).forEach(this::mutate);
            try {
                collection.clear();
            } catch (UnsupportedOperationException immutable) {
                // an immutable collection cannot be changed by anyone
            }
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            new ArrayList<>(map.values()).forEach(this::mutate);
            try {
                map.clear();
            } catch (UnsupportedOperationException immutable) {
                // an immutable map cannot be changed by anyone
            }
        } else if (value instanceof ObjectNode) {
            ((ObjectNode) value).removeAll().put("mutated", true);
        } else if (value instanceof ArrayNode) {
            ((ArrayNode) value).removeAll().add("mutated");
        } else if (!value.getClass().getName().startsWith("java.")) {
            mutateFields(value);
        }
    }

    private void mutateArray(Object array) {
        Class<?> component = array.getClass().getComponentType();
        for (int i = 0; i < Array.getLength(array); i++) {
            if (component == byte.class) {
                Array.setByte(array, i, (byte) (Array.getByte(array, i) ^ 0x55));
            } else if (component.isPrimitive()) {
                // no model object holds another primitive array
                throw new AssertionError("unexpected " + component + " array");
            } else {
                mutate(Array.get(array, i));
                Array.set(array, i, null);
            }
        }
    }

    private void mutateFields(Object value) {
        for (Class<?> type = value.getClass(); type != null && !type.getName().startsWith("java."); type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object current = field.get(value);
                    mutate(current);
                    if (!Modifier.isFinal(field.getModifiers())) {
                        field.set(value, changed(field.getType(), current));
                    }
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    private static Object changed(Class<?> type, Object current) {
        if (type == int.class) {
            return (Integer) current + 1;
        } else if (type == long.class) {
            return (Long) current + 1;
        } else if (type == boolean.class) {
            return !(Boolean) current;
        } else if (type == double.class) {
            return (Double) current + 1;
        } else if (type.isPrimitive()) {
            throw new AssertionError("unexpected " + type + " field");
        } else if (current instanceof String) {
            return "mutated";
        } else if (current instanceof Boolean) {
            return !(Boolean) current;
        } else if (current instanceof Integer) {
            return (Integer) current + 1;
        } else if (current instanceof Long) {
            return (Long) current + 1;
        } else if (current instanceof Enum) {
            Object[] constants = current.getClass().getEnumConstants() != null ? current.getClass().getEnumConstants() : current.getClass().getSuperclass().getEnumConstants();
            return constants[(((Enum<?>) current).ordinal() + 1) % constants.length];
        } else if (current instanceof NottableString) {
            return NottableString.string("mutated");
        } else if (current instanceof MediaType) {
            return MediaType.TEXT_HTML;
        }
        // any other member is replaced by nothing
        return null;
    }
}
