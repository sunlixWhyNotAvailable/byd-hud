package com.bydhud.app;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Changes only the marker shader read from the supplied GDPC pack. */
final class WazeScenePatch {
    static final String PACK = "assets/res/godot/waze_map_scenes.pck";
    static final String MARKER_ASSET = "assets/bydhud/waze-marker.gdshader";
    private static final String MARKER = "Shaders/user_location.gdshader";
    private static final String DECLARATION = "uniform bool bydhud_amber = false;";
    private static final String BRANCH = "  if (bydhud_amber) {\n"
            + "    float level = max(color.g, color.b);\n"
            + "    float fill = OUTPUT_IS_SRGB ? smoothstep(0.70, 0.77, level) : smoothstep(0.448, 0.554, level);\n"
            + "    vec3 amber = OUTPUT_IS_SRGB ? vec3(1.0, 0.72, 0.10) : vec3(1.0, 0.477, 0.010);\n"
            + "    vec3 outline = OUTPUT_IS_SRGB ? vec3(0.08, 0.06, 0.02) : vec3(0.007, 0.005, 0.002);\n"
            + "    color.rgb = mix(outline, amber, fill);\n  }\n";

    private static final class Entry {
        final int index, offset, size;
        Entry(int index, int offset, int size) { this.index=index; this.offset=offset; this.size=size; }
    }

    private static byte[] md5(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("MD5").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IOException("MD5 unavailable for GDPC entry verification", e); }
    }

    private static Map<String, Entry> entries(byte[] data) throws IOException {
        try {
            ByteBuffer input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length < 100 || input.getInt(0) != 0x43504447 || input.getInt(4) != 2 || input.getInt(20) != 0)
                throw new IOException("Unsupported/encrypted GDPC header");
            long base = input.getLong(24); int count = input.getInt(96);
            if (base < 0 || base > data.length || count < 1 || count > (data.length - 100) / 40)
                throw new IOException("Invalid GDPC entry count/base");
            input.position(100); Map<String, Entry> result = new LinkedHashMap<>();
            for (int index=0; index<count; index++) {
                int length=input.getInt();
                if (length<1 || length>input.remaining()-36) throw new IOException("Invalid GDPC name length");
                byte[] name=new byte[length];input.get(name);int end=length;while(end>0 && name[end-1]==0)end--;
                String path=new String(name,0,end,StandardCharsets.UTF_8);int record=input.position();
                long relative=input.getLong(), size=input.getLong();byte[] digest=new byte[16];input.get(digest);
                int flags=input.getInt();
                if(flags!=0 || relative<0 || relative>data.length-base || size<0 || size>data.length-base-relative)
                    throw new IOException("Invalid GDPC entry bounds/flags: "+path);
                int offset=(int)(base+relative);
                if(!Arrays.equals(digest,md5(Arrays.copyOfRange(data,offset,offset+(int)size))))
                    throw new IOException("GDPC entry checksum differs: "+path);
                if(result.put(path,new Entry(record,offset,(int)size))!=null)throw new IOException("Duplicate GDPC entry: "+path);
            }
            for(Entry entry:result.values())if(entry.offset<input.position())throw new IOException("GDPC payload overlaps index");
            if(!result.containsKey(MARKER))throw new IOException("Marker shader missing");
            return result;
        } catch(BufferUnderflowException | IndexOutOfBoundsException e) { throw new IOException("Truncated GDPC pack",e); }
    }

    static byte[] marker(byte[] pack) throws IOException {
        Entry entry=entries(pack).get(MARKER);
        return Arrays.copyOfRange(pack,entry.offset,entry.offset+entry.size);
    }

    static byte[] patch(byte[] input) throws IOException {
        Map<String,Entry> original=entries(input);Entry marker=original.get(MARKER);
        byte[] source=Arrays.copyOfRange(input,marker.offset,marker.offset+marker.size);
        String text=new String(source,StandardCharsets.UTF_8);
        if(text.contains(DECLARATION) && text.contains(BRANCH))return input;
        StringBuilder hash=new StringBuilder();for(byte value:md5(source))hash.append(String.format(Locale.ROOT,"%02x",value&255));
        if(!hash.toString().equals("d1c341789a6190643e6994b7fa417b82"))throw new IOException("Unknown marker shader baseline");
        text=replaceOnce(text,"uniform bool with_lighting;","uniform bool with_lighting;\n"+DECLARATION);
        text=replaceOnce(text,"  if (with_lighting) {",BRANCH+"  if (with_lighting) {");
        byte[] changed=text.getBytes(StandardCharsets.UTF_8);int offset=(input.length+15)&~15;
        byte[] result=Arrays.copyOf(input,offset+changed.length);System.arraycopy(changed,0,result,offset,changed.length);
        ByteBuffer output=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        output.putLong(marker.index,offset-output.getLong(24));output.putLong(marker.index+8,changed.length);
        output.position(marker.index+16);output.put(md5(changed));
        Map<String,Entry> checked=entries(result);
        for(Map.Entry<String,Entry> item:original.entrySet())if(!item.getKey().equals(MARKER)) {
            Entry before=item.getValue(),after=checked.get(item.getKey());
            if(!Arrays.equals(Arrays.copyOfRange(input,before.offset,before.offset+before.size),
                    Arrays.copyOfRange(result,after.offset,after.offset+after.size)))throw new IOException("Unrelated scene entry changed");
        }
        return result;
    }

    private static String replaceOnce(String text,String needle,String value) throws IOException {
        int index=text.indexOf(needle);
        if(index<0 || text.indexOf(needle,index+needle.length())>=0)throw new IOException("Missing/ambiguous shader anchor");
        return text.substring(0,index)+value+text.substring(index+needle.length());
    }
}
