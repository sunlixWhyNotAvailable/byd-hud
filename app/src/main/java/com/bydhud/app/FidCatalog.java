package com.bydhud.app;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

//Read-only reflection catalog for the static BYD feature identifiers exposed by the car framework.
final class FidCatalog {
    private static final String[] ROOTS = {
            "android.hardware.bydauto.BYDAutoFeatureIds",
            "android.hardware.bydauto.BYDAutoConstants"
    };

    private FidCatalog() {
    }

    static Result collect() {
        return collect(ROOTS);
    }

    static Result collectBounded(BooleanSupplier cancelled) {
        return collect(ROOTS, cancelled);
    }

    static Result collect(String[] classNames) {
        return collect(classNames, () -> false);
    }

    static Result collect(String[] classNames, BooleanSupplier cancelled) {
        List<Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        int loadedRoots = 0;
        if (classNames == null) {
            classNames = new String[0];
        }
        for (String className : classNames) {
            if (cancelled.getAsBoolean()) break;
            try {
                Class<?> root = Class.forName(className, false, FidCatalog.class.getClassLoader());
                loadedRoots++;
                collectClass(root, visited, entries, errors, cancelled);
            } catch (Throwable error) {
                errors.add("class_load\t" + clean(className) + "\t"
                        + error.getClass().getName());
            }
        }
        if (cancelled.getAsBoolean()) errors.add("collection_cancelled\tpartial catalog");
        entries.sort(Comparator.comparing(Entry::key));
        errors.sort(String::compareTo);
        return new Result(entries, errors, loadedRoots);
    }

    private static void collectClass(
            Class<?> type,
            Set<String> visited,
            List<Entry> entries,
            List<String> errors,
            BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || !visited.add(type.getName())) {
            return;
        }
        try {
            for (Field field : type.getDeclaredFields()) {
                if (cancelled.getAsBoolean()) return;
                Class<?> fieldType = field.getType();
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
                        || field.getName().startsWith("$")
                        || (fieldType != int.class && fieldType != long.class)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    entries.add(new Entry(
                            type.getName(),
                            field.getName(),
                            fieldType == int.class ? "int" : "long",
                            ((Number) field.get(null)).longValue()));
                } catch (Throwable error) {
                    errors.add("field_read\t"
                            + clean(type.getName() + "." + field.getName())
                            + "\t" + error.getClass().getName());
                }
            }
        } catch (Throwable error) {
            errors.add("fields\t" + clean(type.getName()) + "\t"
                    + error.getClass().getName());
        }

        try {
            Class<?>[] nested = type.getDeclaredClasses();
            Arrays.sort(nested, Comparator.comparing(Class::getName));
            for (Class<?> child : nested) {
                if (cancelled.getAsBoolean()) return;
                collectClass(child, visited, entries, errors, cancelled);
            }
        } catch (Throwable error) {
            errors.add("nested_classes\t" + clean(type.getName()) + "\t"
                    + error.getClass().getName());
        }
    }

    static String text(Result result) {
        StringBuilder text = new StringBuilder();
        text.append("status=").append(result.available() ? "available" : "unavailable")
                .append('\n');
        text.append("loadedRoots=").append(result.loadedRoots).append('\n');
        text.append("entryCount=").append(result.entries.size()).append('\n');
        for (Entry entry : result.entries) {
            text.append(entry.className).append('.').append(entry.fieldName)
                    .append('\t').append(entry.type).append('\t').append(entry.value)
                    .append('\n');
        }
        for (String error : result.errors) {
            text.append("error=").append(error).append('\n');
        }
        return text.toString();
    }

    static String json(Result result) {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"schemaVersion\": 1,\n")
                .append("  \"status\": ").append(quote(
                        result.available() ? "available" : "unavailable")).append(",\n")
                .append("  \"loadedRoots\": ").append(result.loadedRoots).append(",\n")
                .append("  \"entryCount\": ").append(result.entries.size()).append(",\n")
                .append("  \"entries\": [\n");
        for (int i = 0; i < result.entries.size(); i++) {
            Entry entry = result.entries.get(i);
            json.append("    {\"class\": ").append(quote(entry.className))
                    .append(", \"field\": ").append(quote(entry.fieldName))
                    .append(", \"type\": ").append(quote(entry.type))
                    .append(", \"value\": ").append(entry.value).append("}");
            if (i + 1 < result.entries.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ],\n  \"errors\": [\n");
        for (int i = 0; i < result.errors.size(); i++) {
            json.append("    ").append(quote(result.errors.get(i)));
            if (i + 1 < result.errors.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static String quote(String value) {
        String safe = value == null ? "" : value;
        StringBuilder quoted = new StringBuilder(safe.length() + 2);
        quoted.append('\"');
        for (int i = 0; i < safe.length(); i++) {
            char current = safe.charAt(i);
            switch (current) {
                case '\\':
                    quoted.append("\\\\");
                    break;
                case '\"':
                    quoted.append("\\\"");
                    break;
                case '\n':
                    quoted.append("\\n");
                    break;
                case '\r':
                    quoted.append("\\r");
                    break;
                case '\t':
                    quoted.append("\\t");
                    break;
                default:
                    quoted.append(current);
                    break;
            }
        }
        quoted.append('\"');
        return quoted.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\t', ' ')
                .replace('\r', ' ').replace('\n', ' ');
    }

    static final class Result {
        final List<Entry> entries;
        final List<String> errors;
        final int loadedRoots;

        Result(List<Entry> entries, List<String> errors, int loadedRoots) {
            this.entries = new ArrayList<>(entries);
            this.errors = new ArrayList<>(errors);
            this.loadedRoots = loadedRoots;
        }

        boolean available() {
            return loadedRoots > 0 && !entries.isEmpty();
        }
    }

    static final class Entry {
        final String className;
        final String fieldName;
        final String type;
        final long value;

        Entry(String className, String fieldName, String type, long value) {
            this.className = className;
            this.fieldName = fieldName;
            this.type = type;
            this.value = value;
        }

        String key() {
            return className + "." + fieldName;
        }
    }
}
