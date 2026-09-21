package com.bydhud.app;

import org.json.JSONException;
import org.json.JSONObject;

/** Keeps Android's checked JSON exceptions out of capture lifecycle APIs. */
final class ShanghaiJson {
    private ShanghaiJson() { }

    static JSONObject object(Object... keysAndValues) {
        if ((keysAndValues.length & 1) != 0) throw new IllegalArgumentException("JSON key/value mismatch");
        JSONObject result = new JSONObject();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            put(result, String.valueOf(keysAndValues[index]), keysAndValues[index + 1]);
        }
        return result;
    }

    static JSONObject put(JSONObject target, String key, Object value) {
        try {
            return target.put(key, value);
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode Shanghai diagnostic JSON", error);
        }
    }

    static JSONObject copy(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (JSONException error) {
            throw new IllegalStateException("Could not copy Shanghai diagnostic JSON", error);
        }
    }
}
