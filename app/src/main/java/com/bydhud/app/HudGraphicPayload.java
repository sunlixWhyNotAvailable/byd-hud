package com.bydhud.app;

//models binary HUD graphics so the sender can separate image transport from navigation decisions.

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

//models HudGraphicPayload data here so transport and parser layers share a stable contract.
final class HudGraphicPayload {
    private static final String BLACK_JPEG_34_BASE64 =
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoM"
                    + "DAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkU"
                    + "DQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"
                    + "FBT/wAARCAAiACIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBg"
                    + "cICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEI"
                    + "I0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWm"
                    + "NkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5"
                    + "usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQ"
                    + "EBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEE"
                    + "BSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nz"
                    + "g5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaX"
                    + "mJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8v"
                    + "P09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKACiiigAooooAKKKKACiiigAooooAKK"
                    + "KKACiiigAooooA//Z";
    private static final String BLACK_JPEG_1_BASE64 =
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoM"
                    + "DAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkU"
                    + "DQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU"
                    + "FBT/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBg"
                    + "cICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEI"
                    + "I0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWm"
                    + "NkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5"
                    + "usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQ"
                    + "EBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEE"
                    + "BSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nz"
                    + "g5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaX"
                    + "mJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8v"
                    + "P09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q==";
    private static final Map<String, Bitmap> OEM_ICON_CACHE = new HashMap<>();

    private static Context appContext;
    private static String laneKey = "";
    private static byte[] laneBytes = new byte[0];
    private static String turnKey = "";
    private static byte[] turnBytes = new byte[0];

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudGraphicPayload() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized void setContext(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    static synchronized byte[] buildLanePng(HudState state) {
        HudLaneModel.LaneSpec[] lanes = HudLaneModel.parse(state);
        if (lanes.length == 0) {
            laneKey = "empty";
            laneBytes = new byte[0];
            return laneBytes;
        }
        String signature = HudLaneModel.signature(state);
        HudLaneGeometry.Geometry geometry = HudLaneGeometry.calculate(state, lanes.length);
        String key = "oem|" + geometry.bitmapWidth + "x" + geometry.bitmapHeight
                + "|" + geometry.iconWidth + "x" + geometry.iconHeight
                + "|gap=" + geometry.gapPx
                + "|edge=" + geometry.edgePaddingPx
                + "|" + signature;
        if (key.equals(laneKey) && laneBytes.length > 0) {
            return laneBytes;
        }

        byte[] bytes = buildStaticLanePng(lanes, geometry);

        laneKey = key;
        laneBytes = bytes;
        return laneBytes;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized int lanePngLength(HudState state) {
        return state.includeLaneBitmap ? buildLanePng(state).length : 0;
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    static synchronized byte[] buildTurnPng(HudState state) {
        String key = "turn|" + state.turnBitmapMode + "|" + state.turnBitmapId;
        if (key.equals(turnKey) && turnBytes.length > 0) {
            return turnBytes;
        }

        turnKey = key;
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_1) {
            turnBytes = buildBlankTurnPng(1, 1, Color.TRANSPARENT);
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_34) {
            turnBytes = buildBlankTurnPng(34, 34, Color.TRANSPARENT);
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_120) {
            turnBytes = buildBlankTurnPng(120, 120, Color.TRANSPARENT);
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_34) {
            turnBytes = buildBlankTurnPng(34, 34, Color.BLACK);
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_JPEG_34) {
            turnBytes = buildBlackTurnJpeg34();
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_RGB_PNG_34) {
            turnBytes = buildBlackTurnRgbPng34();
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_JPEG_1) {
            turnBytes = buildBlackTurnJpeg1();
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_RGB_PNG_1) {
            turnBytes = buildBlackTurnRgbPng1();
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_120) {
            turnBytes = buildBlankTurnPng(120, 120, Color.BLACK);
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_BAD_1) {
            turnBytes = new byte[] { 0x42 };
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_FAKE_JPEG) {
            turnBytes = new byte[] { (byte) 0xff, (byte) 0xd8, 0x00, 0x00 };
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_ZERO_BYTES_34) {
            turnBytes = new byte[34 * 34 * 4];
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_OEM) {
            turnBytes = buildOemTurnPng(state.turnBitmapId);
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_OEM_RAW) {
            turnBytes = buildOemTurnRawBytes(state.turnBitmapId);
        } else if (state.turnBitmapMode == HudState.TURN_BITMAP_EMPTY_FIELD) {
            turnBytes = new byte[0];
        } else {
            turnBytes = new byte[0];
        }
        return turnBytes;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized int turnPngLength(HudState state) {
        return state.turnBitmapMode == HudState.TURN_BITMAP_OMIT ? 0 : buildTurnPng(state).length;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized String turnFieldStatus(HudState state) {
        if (state.turnBitmapMode == HudState.TURN_BITMAP_OMIT) {
            return "absent:omit";
        }
        byte[] bytes = buildTurnPng(state);
        if (state.turnBitmapMode == HudState.TURN_BITMAP_EMPTY_FIELD) {
            return "present:empty";
        }
        if (bytes.length > 0) {
            return "present:bytes";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_OEM
                || state.turnBitmapMode == HudState.TURN_BITMAP_OEM_RAW) {
            return "absent:missing";
        }
        return "absent:failed";
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized String turnFieldMagic(HudState state) {
        if (state.turnBitmapMode == HudState.TURN_BITMAP_OMIT) {
            return "absent";
        }
        byte[] bytes = buildTurnPng(state);
        if (state.turnBitmapMode == HudState.TURN_BITMAP_EMPTY_FIELD) {
            return "empty";
        }
        if (bytes.length == 0) {
            if (state.turnBitmapMode == HudState.TURN_BITMAP_OEM
                    || state.turnBitmapMode == HudState.TURN_BITMAP_OEM_RAW) {
                return "missing";
            }
            return "failed";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BAD_1) {
            return "bad";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_FAKE_JPEG) {
            return "fakejpg";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_ZERO_BYTES_34) {
            return "zero";
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) {
            return "jpg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return "png";
        }
        return "unknown";
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized String turnFieldDescriptor(HudState state) {
        String status = turnFieldStatus(state);
        if (!"present:bytes".equals(status)) {
            return status;
        }

        byte[] bytes = buildTurnPng(state);
        String magic = turnFieldMagic(state);
        if (!"png".equals(magic)) {
            return magic;
        }
        if (bytes.length < 26
                || (bytes[0] & 0xff) != 0x89
                || bytes[1] != 0x50
                || bytes[2] != 0x4e
                || bytes[3] != 0x47) {
            return "png:invalid";
        }

        int width = readPngInt(bytes, 16);
        int height = readPngInt(bytes, 20);
        int colorType = bytes[25] & 0xff;
        if (colorType == 6) {
            String alpha = isTransparentBlankMode(state.turnBitmapMode) ? "alpha0" : "alpha";
            return "png:" + width + "x" + height + ":rgba-" + alpha;
        }
        if (colorType == 2) {
            return "png:" + width + "x" + height + ":rgb";
        }
        return "png:" + width + "x" + height + ":color" + colorType;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static synchronized String turnResourceName(HudState state) {
        if (state.turnBitmapMode == HudState.TURN_BITMAP_OMIT) {
            return "omit";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_OEM_RAW) {
            String name = resolveOemTurnResourceName(state.turnBitmapId);
            return name.isEmpty() ? "missing" : "raw_" + name;
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_34) {
            return "blank_34_transparent";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_1) {
            return "blank_1_transparent";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_120) {
            return "blank_120_transparent";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_120) {
            return "blank_120_black";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_34) {
            return "blank_34_black";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_JPEG_34) {
            return "blank_34_black_jpeg";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_RGB_PNG_34) {
            return "blank_34_black_rgb_png";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_JPEG_1) {
            return "blank_1_black_jpeg";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BLACK_RGB_PNG_1) {
            return "blank_1_black_rgb_png";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_EMPTY_FIELD) {
            return "empty_field8";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_BAD_1) {
            return "bad_1_byte";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_FAKE_JPEG) {
            return "bad_fake_jpeg";
        }
        if (state.turnBitmapMode == HudState.TURN_BITMAP_ZERO_BYTES_34) {
            return "zero_34_bytes";
        }
        String name = resolveOemTurnResourceName(state.turnBitmapId);
        return name.isEmpty() ? "missing" : name;
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildStaticLanePng(HudLaneModel.LaneSpec[] lanes, HudLaneGeometry.Geometry geometry) {
        if (appContext == null || lanes.length == 0) {
            return new byte[0];
        }

        Bitmap[] icons = new Bitmap[lanes.length];
        for (int i = 0; i < lanes.length; i++) {
            icons[i] = loadStaticLaneIcon(lanes[i]);
            if (icons[i] == null) {
                return new byte[0];
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(geometry.bitmapWidth, geometry.bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Rect dst = new Rect();
        for (int i = 0; i < icons.length; i++) {
            int slotLeft = geometry.edgePaddingPx + i * (geometry.slotWidth + geometry.gapPx);
            int left = slotLeft + Math.round((geometry.slotWidth - geometry.iconWidth) / 2f);
            int top = Math.round((geometry.slotHeight - geometry.iconHeight) / 2f);
            dst.set(left, top, left + geometry.iconWidth, top + geometry.iconHeight);
            canvas.drawBitmap(icons[i], null, dst, null);
        }
        return toPng(bitmap);
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildBlankTurnPng(int width, int height, int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        int alpha = (color >>> 24) & 0xff;
        byte[] raw = new byte[height * (1 + width * 4)];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            raw[offset++] = 0;
            for (int x = 0; x < width; x++) {
                raw[offset++] = (byte) red;
                raw[offset++] = (byte) green;
                raw[offset++] = (byte) blue;
                raw[offset++] = (byte) alpha;
            }
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(raw);
        } catch (IOException e) {
            return new byte[0];
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(0x89);
        png.write(0x50);
        png.write(0x4e);
        png.write(0x47);
        png.write(0x0d);
        png.write(0x0a);
        png.write(0x1a);
        png.write(0x0a);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writePngInt(ihdr, width);
        writePngInt(ihdr, height);
        ihdr.write(8);
        ihdr.write(6);
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        writePngChunk(png, "IHDR", ihdr.toByteArray());
        writePngChunk(png, "IDAT", compressed.toByteArray());
        writePngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildBlackTurnRgbPng34() {
        return buildBlackTurnRgbPng(34, 34);
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildBlackTurnRgbPng1() {
        return buildBlackTurnRgbPng(1, 1);
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildBlackTurnRgbPng(int width, int height) {
        byte[] raw = new byte[height * (1 + width * 3)];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            raw[offset++] = 0;
            for (int x = 0; x < width; x++) {
                raw[offset++] = 0;
                raw[offset++] = 0;
                raw[offset++] = 0;
            }
        }

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
            deflater.write(raw);
        } catch (IOException e) {
            return new byte[0];
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(0x89);
        png.write(0x50);
        png.write(0x4e);
        png.write(0x47);
        png.write(0x0d);
        png.write(0x0a);
        png.write(0x1a);
        png.write(0x0a);

        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writePngInt(ihdr, width);
        writePngInt(ihdr, height);
        ihdr.write(8);
        ihdr.write(2);
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        writePngChunk(png, "IHDR", ihdr.toByteArray());
        writePngChunk(png, "IDAT", compressed.toByteArray());
        writePngChunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        writePngInt(out, data.length);
        out.write(typeBytes, 0, typeBytes.length);
        out.write(data, 0, data.length);

        CRC32 crc = new CRC32();
        crc.update(typeBytes, 0, typeBytes.length);
        crc.update(data, 0, data.length);
        writePngInt(out, (int) crc.getValue());
    }

    //sends encoded data here so transport side effects stay behind a single boundary.
    private static void writePngInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static int readPngInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isTransparentBlankMode(int mode) {
        return mode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_1
                || mode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_34
                || mode == HudState.TURN_BITMAP_BLANK_TRANSPARENT_120;
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildBlackTurnJpeg34() {
        return Base64.getDecoder().decode(BLACK_JPEG_34_BASE64);
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildBlackTurnJpeg1() {
        return Base64.getDecoder().decode(BLACK_JPEG_1_BASE64);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static Bitmap loadStaticLaneIcon(HudLaneModel.LaneSpec lane) {
        if (!lane.customResourceName.isEmpty()) {
            return loadResourceBitmap(lane.customResourceName);
        }
        return loadOemIcon(lane);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static Bitmap loadOemIcon(HudLaneModel.LaneSpec lane) {
        String name = (lane.recommended ? "global_image_landfront_hud_" : "global_image_landback_hud_")
                + resourceSuffix(lane.iconId);
        return loadResourceBitmap(name);
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildOemTurnPng(int turnBitmapId) {
        if (appContext == null) {
            return isOemBlankTurnSource(turnBitmapId)
                    ? buildBlankTurnPng(1, 1, Color.TRANSPARENT)
                    : new byte[0];
        }

        String resourceName = resolveOemTurnResourceName(turnBitmapId);
        if (resourceName.isEmpty()) {
            return isOemBlankTurnSource(turnBitmapId)
                    ? buildBlankTurnPng(1, 1, Color.TRANSPARENT)
                    : new byte[0];
        }
        Bitmap icon = loadResourceBitmap(resourceName);
        if (icon == null) {
            return isOemBlankTurnSource(turnBitmapId)
                    ? buildBlankTurnPng(1, 1, Color.TRANSPARENT)
                    : new byte[0];
        }

        Bitmap bitmap = Bitmap.createBitmap(icon.getWidth(), icon.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawBitmap(icon, 0f, 0f, null);
        return toPng(bitmap);
    }

    //builds this artifact here so callers do not duplicate protocol or UI construction details.
    private static byte[] buildOemTurnRawBytes(int turnBitmapId) {
        if (appContext == null) {
            return new byte[0];
        }

        String resourceName = resolveOemTurnResourceName(turnBitmapId);
        if (resourceName.isEmpty()) {
            return new byte[0];
        }

        Resources resources = appContext.getResources();
        int resId = resources.getIdentifier(resourceName, "drawable", appContext.getPackageName());
        if (resId == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (InputStream input = resources.openRawResource(resId)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            return new byte[0];
        }
        return out.toByteArray();
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static String resolveOemTurnResourceName(int turnBitmapId) {
        if (appContext == null) {
            return isOemBlankTurnSource(turnBitmapId)
                    ? "global_image_hud_sou" + turnBitmapId + "_day"
                    : "";
        }
        String suffix = String.valueOf(Math.max(0, turnBitmapId));
        String[] names = {
                "global_image_ar_hud_new_sou" + suffix,
                "global_image_hud_sou" + suffix + "_day",
                "global_image_hud_sou" + suffix
        };
        Resources resources = appContext.getResources();
        for (String name : names) {
            if (resources.getIdentifier(name, "drawable", appContext.getPackageName()) != 0) {
                return name;
            }
        }
        return "";
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isOemBlankTurnSource(int turnBitmapId) {
        return turnBitmapId >= 72 && turnBitmapId <= 76;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static Bitmap loadResourceBitmap(String name) {
        Resources resources = appContext.getResources();
        Bitmap cached = OEM_ICON_CACHE.get(name);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        int resId = resources.getIdentifier(name, "drawable", appContext.getPackageName());
        if (resId == 0) {
            return null;
        }
        Bitmap decoded = BitmapFactory.decodeResource(resources, resId);
        if (decoded != null) {
            OEM_ICON_CACHE.put(name, decoded);
        }
        return decoded;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static String resourceSuffix(int iconId) {
        if (iconId >= 0 && iconId <= 9) {
            return String.valueOf(iconId);
        }
        if (iconId == 10) {
            return "a";
        }
        if (iconId == 11) {
            return "b";
        }
        if (iconId == 12) {
            return "c";
        }
        return String.valueOf(iconId);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static byte[] toPng(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }
}
