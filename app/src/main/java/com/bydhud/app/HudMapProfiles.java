package com.bydhud.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Reads and updates the saved navigator source crops. */
public final class HudMapProfiles {
    private static final Object LOCK = new Object();

    private HudMapProfiles() {
    }

    public static Map<HudMapProfile.Source, HudMapProfile> profiles(Context context) {
        if (context == null) return Collections.emptyMap();
        synchronized (LOCK) {
            return Collections.unmodifiableMap(read(context));
        }
    }

    public static boolean save(
            Context context,
            HudMapProfile.Source originalSource,
            HudMapProfile profile) {
        if (context == null || profile == null) return false;
        synchronized (LOCK) {
            EnumMap<HudMapProfile.Source, HudMapProfile> original = read(context);
            EnumMap<HudMapProfile.Source, HudMapProfile> current = new EnumMap<>(original);
            if (originalSource == null) {
                if (current.containsKey(profile.source)) return false;
            } else if (!current.containsKey(originalSource)
                    || (originalSource != profile.source && current.containsKey(profile.source))) {
                return false;
            }
            if (originalSource != null) current.remove(originalSource);
            current.put(profile.source, profile);
            if (current.equals(original)) return true;
            HudPrefs.setMapProfiles(context, encode(current), nextRevision(HudPrefs.mapProfilesRevision(context)));
            return true;
        }
    }

    public static boolean delete(Context context, HudMapProfile.Source source) {
        if (context == null || source == null) return false;
        synchronized (LOCK) {
            EnumMap<HudMapProfile.Source, HudMapProfile> current = read(context);
            if (current.remove(source) == null) return false;
            HudPrefs.setMapProfiles(context, encode(current), nextRevision(HudPrefs.mapProfilesRevision(context)));
            return true;
        }
    }

    public static HudMapProfile resolve(Context context, HudMapProfile.Source source) {
        if (source == null) throw new IllegalArgumentException("source is required");
        HudMapProfile profile = profiles(context).get(source);
        return profile == null ? HudMapProfile.defaults(source) : profile;
    }

    public static long revision(Context context) {
        return context == null ? 0L : HudPrefs.mapProfilesRevision(context);
    }

    private static EnumMap<HudMapProfile.Source, HudMapProfile> read(Context context) {
        EnumMap<HudMapProfile.Source, HudMapProfile> result =
                new EnumMap<>(HudMapProfile.Source.class);
        String raw = HudPrefs.mapProfilesJson(context);
        if (raw.isEmpty()) return result;
        try {
            JSONArray values = new JSONArray(raw);
            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.optJSONObject(i);
                if (value == null) continue;
                try {
                    HudMapProfile.Source source = HudMapProfile.Source.valueOf(
                            value.optString("source", ""));
                    result.put(source, new HudMapProfile(source,
                            value.getInt("x"), value.getInt("y"), value.getInt("scale")));
                } catch (IllegalArgumentException | JSONException ignored) {
                    // Ignore corrupt/obsolete entries while keeping other saved sources usable.
                }
            }
        } catch (JSONException ignored) {
            // A malformed catalog behaves like an empty one and is replaced on the next save.
        }
        return result;
    }

    private static String encode(Map<HudMapProfile.Source, HudMapProfile> profiles) {
        JSONArray values = new JSONArray();
        for (HudMapProfile.Source source : HudMapProfile.Source.values()) {
            HudMapProfile profile = profiles.get(source);
            if (profile == null) continue;
            JSONObject value = new JSONObject();
            try {
                value.put("source", profile.source.name());
                value.put("x", profile.x);
                value.put("y", profile.y);
                value.put("scale", profile.scale);
                values.put(value);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return values.toString();
    }

    private static long nextRevision(long revision) {
        return revision == Long.MAX_VALUE ? Long.MAX_VALUE : revision + 1L;
    }
}
