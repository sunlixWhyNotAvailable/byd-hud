package com.bydhud.app;

import org.json.JSONObject;

import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/** Selected RoadInfo fields only: never retains coordinates, text or image bytes. */
final class RoadInfoDiagnosticFrame {
    final Map<String, Object> fields = new TreeMap<>();

    static RoadInfoDiagnosticFrame parse(byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("null_payload");
        Cursor wrapper = new Cursor(payload, 0, payload.length);
        RoadInfoDiagnosticFrame frame = new RoadInfoDiagnosticFrame();
        boolean found = false;
        while (wrapper.hasNext()) {
            int tag = wrapper.tag();
            if (tag == 10) {
                Cursor road = wrapper.message();
                frame.readRoad(road);
                found = true;
            } else wrapper.skip(tag & 7);
        }
        if (!found) throw new IllegalArgumentException("missing_road_info");
        return frame;
    }

    JSONObject json() { return new JSONObject(fields); }

    boolean matches(RoadInfoDiagnosticFrame other) {
        return !fields.isEmpty() && other != null && fields.equals(other.fields);
    }

    private void readRoad(Cursor road) {
        while (road.hasNext()) {
            int tag = road.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            String key = null;
            switch (field) {
                case 2: key = "guideType"; break;
                case 3: key = "remainingM"; break;
                case 4: key = "remainingSeconds"; break;
                case 5: key = "laneCount"; break;
                case 9: key = "distanceM"; break;
                case 11: key = "speedLimit"; break;
                case 16: key = "navigationStatus"; break;
                case 28: key = "direction"; break;
                default: break;
            }
            if (key != null && wire == 0) {
                fields.put(key, (int) road.varint());
            } else if ((field == 7 || field == 8) && wire == 2) {
                Cursor image = road.message();
                CRC32 crc = new CRC32();
                crc.update(image.bytes, image.position, image.end - image.position);
                String prefix = field == 7 ? "lanes" : "turn";
                fields.put(prefix + "Bytes", image.end - image.position);
                fields.put(prefix + "Crc32", Long.toHexString(crc.getValue()));
            } else {
                if (key != null || field == 7 || field == 8) {
                    throw new IllegalArgumentException("unexpected_field_wire_type");
                }
                road.skip(wire);
            }
        }
    }

    private static final class Cursor {
        final byte[] bytes;
        final int end;
        int position;

        Cursor(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.position = start;
            this.end = end;
        }

        boolean hasNext() { return position < end; }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (!hasNext()) throw new IllegalArgumentException("truncated_varint");
                int next = bytes[position++] & 255;
                if (shift == 63 && (next & 254) != 0) {
                    throw new IllegalArgumentException("overflow_varint");
                }
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw new IllegalArgumentException("overflow_varint");
        }

        int tag() {
            long value = varint();
            if (value <= 0 || value > 0xffffffffL || (value >>> 3) == 0) {
                throw new IllegalArgumentException("invalid_tag");
            }
            return (int) value;
        }

        Cursor message() {
            long length = varint();
            if (length < 0 || length > end - position) {
                throw new IllegalArgumentException("invalid_length");
            }
            int start = position;
            position += (int) length;
            return new Cursor(bytes, start, position);
        }

        void skip(int wire) {
            if (wire == 0) varint();
            else if (wire == 2) message();
            else if (wire == 1 || wire == 5) {
                int count = wire == 1 ? 8 : 4;
                if (count > end - position) throw new IllegalArgumentException("truncated_fixed");
                position += count;
            } else throw new IllegalArgumentException("unsupported_wire_type");
        }
    }
}
