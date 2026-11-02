package com.bydhud.mapshud;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.content.res.Resources;
import android.opengl.GLES20;
import com.bydhud.mapcapture.MapPalette;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.*;
import static com.bydhud.mapshud.Reflect.*;

/** HUD copies and context-local shader uniforms; decoded tile/style resources remain immutable. */
public final class HudStyle {
    private static final Map<Object,Object> owners=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object,Boolean> baseShaders=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object,Boolean> routes=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object,RouteCopies> routeStyles=new WeakHashMap<>();
    // Tile preparation runs off the GL thread; retain the context identity, not a thread flag.
    private static final Map<Object,WeakReference<Object>> routeContexts=new WeakHashMap<>();
    private static final class RouteCopies {
        final Map<Object,Object> styles=new WeakHashMap<>();
        final Set<Object> copies=Collections.newSetFromMap(new WeakHashMap<>());
        final Set<String> logged=new HashSet<>();
        final Set<Integer> segments=new HashSet<>();
        final Map<Object,Map<Long,Integer>> legends=new WeakHashMap<>();
    }
    private static final Map<Object,IdentityHashMap<Object,Object>> labelCopies=new IdentityHashMap<>();
    private static final Set<Object> tiles=Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> gl=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Object> glOwner=new ThreadLocal<>();
    private static final ThreadLocal<MapPalette> glPalette=new ThreadLocal<>();
    private static final ThreadLocal<Map<Object,Integer>> uniforms=ThreadLocal.withInitial(WeakHashMap::new);
    private static final ThreadLocal<Boolean> shieldBuilding=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Throwable> shieldFailure=new ThreadLocal<>();
    private static volatile Bitmap emptyShieldBitmap;
    private static volatile Object emptyShieldObject;
    private static volatile Object emptyShieldClock;
    static void prepare() {
        if(emptyShieldBitmap!=null && emptyShieldObject!=null)return;
        synchronized(HudStyle.class) {
            if(emptyShieldBitmap==null)emptyShieldBitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
            emptyShieldObject();
        }
    }
    static void own(Object object,Object map) { if(object!=null)owners.put(object,map); }
    static void ownRoute(Object route,Object map) { own(route,map);routes.put(route,false); }
    static boolean owned(Object object) { return object!=null && owners.containsKey(object); }
    private static void failure(Object owner,Throwable error) { HudScene.presentationFailure(owners.get(owner),root(error)); }
    static void register(Object map,Object container,Object renderer) throws Exception {
        own(map,map); own(container,map); own(renderer,map);
        Object controller=call(container,"c");
        own(controller,map); own(get(controller,"B"),map); own(get(controller,"F"),map);
        own(get(controller,"G"),map); own(get(controller,"D"),map);
    }
    static void unregister(Object map) {
        synchronized(routeStyles) {
            routeStyles.remove(map);
            synchronized(owners) {
                routeContexts.values().removeIf(v->owners.get(v.get())==map);
                for(Map.Entry<Object,Object> entry:owners.entrySet()) if(entry.getValue()==map) {
                    if(routes.containsKey(entry.getKey()))routes.put(entry.getKey(),true);
                    synchronized(labelCopies){labelCopies.remove(entry.getKey());}
                }
                owners.values().removeIf(v->v==map);
            }
        }
    }
    public static void frame(Object renderer) {
        // The renderer identity is available before the auxiliary ownership table is populated.
        MapPalette palette=HudScene.rendererPalette(renderer);
        boolean hud=palette!=null;
        glPalette.set(palette);
        gl.set(hud);glOwner.set(hud?owners.get(renderer):null);
    }
    public static void context(Object renderer) { frame(renderer); uniforms.get().clear(); }
    public static void tile(Object tile) {
        try {
            if(!owned(get(tile,"ab")))return;
            synchronized(tiles) { if(!tiles.add(tile))return; }
            tagShaders(tile,Collections.newSetFromMap(new IdentityHashMap<>()),0);
        } catch(Throwable e){HudScene.presentationFailure(e);}
    }
    private static void tagShaders(Object item,Set<Object> seen,int depth) throws Exception {
        if(item==null || depth>6 || !seen.add(item))return;
        if(type("bqgi").isInstance(item)){baseShaders.put(item,true);return;}
        if(item instanceof Iterable){for(Object e:(Iterable<?>)item)tagShaders(e,seen,depth+1);return;}
        String name=item.getClass().getName();
        if(!(name.startsWith("bpn")||name.startsWith("bpm")||name.startsWith("bqd")||name.startsWith("bqg")))return;
        for(Class<?> c=item.getClass();c!=null && c!=Object.class;c=c.getSuperclass())for(Field f:c.getDeclaredFields()) {
            if(Modifier.isStatic(f.getModifiers())||f.getType().isPrimitive())continue;
            f.setAccessible(true); tagShaders(f.get(item),seen,depth+1);
        }
    }
    public static String roadShader(String source) { return gl.get()?source+"\n// BYD_HUD_ROAD\n":source; }
    public static String roadVertex(String source) {
        if(!gl.get())return source;
        if(!source.contains("C=s.z;"))throw new IllegalStateException("HUD road stroke ABI changed");
        return source.replace("void main(){","varying lowp float bydHudStroke;\nvoid main(){bydHudStroke=s.z;");
    }
    public static Object routeStyle(Object provider,long id,Object original) {
        return providerRoute(provider,id,original,false);
    }
    public static Object namedRouteStyle(Object provider,long id,Object original) {
        return providerRoute(provider,id,original,true);
    }
    private static Object providerRoute(Object provider,long id,Object original,boolean named) {
        if(original==null)return original;
        try { synchronized(routeStyles) {
            Object map=owners.get(provider);
            if(map==null)return original;
            RouteCopies cache=routeStyles.computeIfAbsent(map,k->new RouteCopies());
            int route=HudPalette.routeName(named?(int)id:0,named?-1:id,cache.segments,
                    named?Collections.emptyMap():globalRoutes(cache,call(provider,"p")));
            return route==0?original:routeCopy(map,route,original,false,named?"provider.named":"provider.global",provider,null);
        }}catch(Throwable e){failure(provider,e);return original;}
    }
    public static void routeSegments(Object presenter,Object input) {
        Object map=null;
        try {
            map=owners.get(get(presenter,"g"));if(map==null)return;
            Object classifier=get(get(presenter,"k"),"b");
            synchronized(routeStyles) {
                RouteCopies cache=routeStyles.computeIfAbsent(map,k->new RouteCopies());
                for(Object segment:(List<?>)get(input,"i")) {
                    if(((Number)get(segment,"c")).intValue()!=4)continue;
                    Object style=get(segment,"e");
                    if(style==null || (((Number)get(style,"b")).intValue()&1)==0)continue;
                    int named=((Number)call(call(classifier,"k",segment),"getNumber")).intValue();
                    if(named!=0 && cache.segments.add(named))cache.legends.clear();
                }
            }
        }catch(Throwable e){if(map!=null)HudScene.presentationFailure(map,root(e));}
    }
    private static Map<Long,Integer> globalRoutes(RouteCopies cache,Object legend) throws Exception {
        if(legend==null)return Collections.emptyMap();
        Map<Long,Integer> indexed=cache.legends.get(legend);if(indexed!=null)return indexed;
        indexed=new HashMap<>();
        Set<Integer> named=new HashSet<>(cache.segments);
        for(int id:HudPalette.NAMED_ROUTES)named.add(id);
        for(int id:named)indexRoute(call(legend,"b",id),id,indexed,Collections.newSetFromMap(new IdentityHashMap<>()));
        cache.legends.put(legend,indexed);return indexed;
    }
    private static void indexRoute(Object style,int named,Map<Long,Integer> indexed,Set<Object> seen) throws Exception {
        if(style==null || !seen.add(style))return;
        long global=((Number)get(style,"b")).longValue();
        if(global>=0)indexed.put(global,named);
        Map<?,?> variants=(Map<?,?>)get(style,"e");
        if(variants!=null)for(Object variant:variants.values())indexRoute(variant,named,indexed,seen);
    }
    public static void routeCache(Object tile) {
        Object context=null;
        try {
            context=get(tile,"ab");Object cache=get(tile,"k"), resolver=get(cache,"j");
            synchronized(routeStyles) {
                WeakReference<Object> owner=new WeakReference<>(context);
                routeContexts.put(cache,owner);routeContexts.put(resolver,owner);
            }
        } catch(Throwable e){if(owned(context))failure(context,e);}
    }
    public static Object routeRow(Object cache,Object descriptor,Object original) {
        return lateRouteCopy(cache,descriptor,original,true);
    }
    public static Object lateRouteStyle(Object cache,Object descriptor,Object original) {
        return lateRouteCopy(cache,descriptor,original,false);
    }
    private static Object lateRouteCopy(Object cache,Object descriptor,Object original,boolean row) {
        Object map=null;
        try { synchronized(routeStyles) {
            WeakReference<Object> context=routeContexts.get(cache);
            map=context==null?null:owners.get(context.get());
            if(map==null || descriptor==null || original==null)return original;
            int named=((Number)get(descriptor,"d")).intValue();
            long global=((Number)get(descriptor,"b")).longValue();
            if(named==0 && global==-1)return original;
            RouteCopies copies=routeStyles.computeIfAbsent(map,k->new RouteCopies());
            Object resolver=cache.getClass().getName().equals("bpnp")?get(cache,"j"):cache;
            int id=HudPalette.routeName(named,global,copies.segments,
                    named!=0?Collections.emptyMap():globalRoutes(copies,get(resolver,"d")));
            if(id==0)return original;
            return routeCopy(map,id,original,row,cache.getClass().getName()+(row?".row":".style"),context.get(),descriptor);
        }}catch(Throwable e){if(map!=null)HudScene.presentationFailure(map,root(e));return original;}
    }
    private static Object routeCopy(Object map,long id,Object original,boolean row,String path,Object context,Object descriptor) throws Exception {
        RouteCopies cache=routeStyles.computeIfAbsent(map,k->new RouteCopies());
        int target=HudScene.paletteFor(map).route;
        Object copy=row?copyRouteRow(original,target,cache):copyRouteStyle(original,target,cache);
        if(com.bydhud.mapcapture.LifecycleJournalTransport.detailed() && cache.logged.add(path+":"+id)) {
            Object[] before=row?new Object[]{original}:(Object[])get(original,"c");
            Object[] after=row?new Object[]{copy}:(Object[])get(copy,"c");
            List<String> beforeColors=routeColors(before),afterColors=routeColors(after);
            int changed=0;for(int n=0;n<beforeColors.size();n++)if(!beforeColors.get(n).equals(afterColors.get(n)))changed++;
            HudScene.event("hud_route_style",map,"path="+path+" style="+id+" route="+Integer.toHexString(target)
                    +" context="+Integer.toHexString(System.identityHashCode(context))
                    +" cache="+Integer.toHexString(System.identityHashCode(cache))
                    +(descriptor==null?"":" named="+get(descriptor,"d")+" global="+get(descriptor,"b")+" local="+get(descriptor,"c"))
                    +" rows="+after.length+" changed="+changed+" before="+beforeColors+" after="+afterColors);
        }
        return copy;
    }
    private static List<String> routeColors(Object[] rows) throws Exception {
        List<String> colors=new ArrayList<>();
        for(Object row:rows) {
            for(String key:new String[]{"j","m"})colors.add(Integer.toHexString(((Number)get(row,key)).intValue()));
            for(String key:new String[]{"n","o","p"})for(Object stroke:(Object[])get(row,key))strokeColors(stroke,colors);
            strokeColors(get(row,"q"),colors);
        }
        return colors;
    }
    private static void strokeColors(Object stroke,List<String> colors) throws Exception {
        if(stroke!=null)for(String key:new String[]{"b","c"})colors.add(Integer.toHexString(((Number)get(stroke,key)).intValue()));
    }
    private static Object copyRouteStyle(Object original,int target,RouteCopies cache) throws Exception {
        if(cache.copies.contains(original))return original;
        Object cached=cache.styles.get(original);if(cached!=null)return cached;
        Object[] rows=(Object[])get(original,"c");Object[] copies=rows.clone();
        for(int n=0;n<rows.length;n++)copies[n]=copyRouteRow(rows[n],target,cache);
        android.util.SparseIntArray index=(android.util.SparseIntArray)get(original,"d");
        Object copy=make("bpqf",get(original,"b"),copies,((byte[])get(original,"l")).clone(),
                index==null?null:index.clone(),false);
        // Preserve parsed theme variants; dropping them changes stock style selection.
        Map<?,?> variants=(Map<?,?>)get(original,"e");
        if(variants!=null) {
            Map<Object,Object> variantCopies=new HashMap<>();
            for(Map.Entry<?,?> variant:variants.entrySet())
                variantCopies.put(variant.getKey(),copyRouteStyle(variant.getValue(),target,cache));
            set(copy,"e",variantCopies);
        }
        cache.styles.put(original,copy);cache.copies.add(copy);return copy;
    }
    private static Object copyRouteRow(Object original,int target,RouteCopies cache) throws Exception {
        if(cache.copies.contains(original))return original;
        Object builder=call(original,"f");int peak=0;
        for(String key:new String[]{"g","h"})peak=Math.max(peak,HudPalette.blueLevel(((Number)get(builder,key)).intValue()));
        for(String key:new String[]{"j","k","l"})for(Object stroke:(Object[])get(builder,key))
            peak=Math.max(peak,strokeBlueLevel(stroke));
        peak=Math.max(peak,strokeBlueLevel(get(builder,"m")));
        if(peak==0)return original;
        for(String key:new String[]{"g","h"})set(builder,key,HudPalette.routeColor(((Number)get(builder,key)).intValue(),peak,target));
        for(String key:new String[]{"j","k","l"}) {
            Object[] strokes=((Object[])get(builder,key)).clone();
            for(int i=0;i<strokes.length;i++)strokes[i]=routeStroke(strokes[i],peak,target);
            set(builder,key,strokes);
        }
        set(builder,"m",routeStroke(get(builder,"m"),peak,target));
        Object copy=make("bppb",builder);cache.copies.add(copy);return copy;
    }
    private static int strokeBlueLevel(Object stroke) throws Exception {
        if(stroke==null)return 0;
        return Math.max(HudPalette.blueLevel(((Number)get(stroke,"b")).intValue()),
                HudPalette.blueLevel(((Number)get(stroke,"c")).intValue()));
    }
    private static Object routeStroke(Object stroke,int peak,int target) throws Exception {
        if(stroke==null || strokeBlueLevel(stroke)==0)return stroke;
        return make("bpqe",HudPalette.routeColor(((Number)get(stroke,"b")).intValue(),peak,target),
                HudPalette.routeColor(((Number)get(stroke,"c")).intValue(),peak,target),get(stroke,"d"),
                ((int[])get(stroke,"e")).clone(),get(stroke,"h"),get(stroke,"i"),get(stroke,"l"),
                get(stroke,"m"),get(stroke,"j"),get(stroke,"k"));
    }
    public static void shaderSource(int shader,String source) {
        if(!gl.get()){GLES20.glShaderSource(shader,source);return;}
        String original=source;
        try {
            int[] type=new int[1]; GLES20.glGetShaderiv(shader,GLES20.GL_SHADER_TYPE,type,0);
            if(type[0]==GLES20.GL_FRAGMENT_SHADER) {
                String changed=source.replaceFirst("void\\s+main\\s*\\(","void bydHudOriginalMain(");
                if(changed.equals(source)) {
                    throw new IllegalStateException("HUD fragment shader has no main function");
                }
                boolean road=source.contains("// BYD_HUD_ROAD");
                MapPalette palette=glPalette.get();
                source=changed+"\nuniform lowp float bydHudBase;\n"
                    +(road?"varying lowp float bydHudStroke;\n":"")
                    +"void main(){bydHudOriginalMain();if(bydHudBase>0.5)gl_FragColor.rgb="
                    +(road?"(bydHudStroke<0.5?"+rgb(palette.casing())+":"+rgb(palette.road)+")":rgb(palette.background))
                    +"/255.*gl_FragColor.a;}\n";
            }
            GLES20.glShaderSource(shader,source);
        }catch(Throwable e){
            // Keep a compilable stock shader while the failed HUD scene is retired.
            try {GLES20.glShaderSource(shader,original);}catch(Throwable cleanup){e.addSuppressed(cleanup);}
            HudScene.presentationFailure(glOwner.get(),e);
        }
    }
    public static void shaderState(Object state) {
        if(!gl.get())return;
        try {
            Object program=get(state,"i"); if(program==null)return;
            int id=((Number)get(program,"w")).intValue();
            Integer location=uniforms.get().get(program);
            if(location==null){location=GLES20.glGetUniformLocation(id,"bydHudBase");uniforms.get().put(program,location);}
            if(location>=0)GLES20.glUniform1f(location,baseShaders.containsKey(state)?1f:0f);
        }catch(Throwable e){HudScene.presentationFailure(e);}
    }
    public static void clearColor(float r,float g,float b,float a) {
        if(gl.get()) {
            int color=glPalette.get().background;
            GLES20.glClearColor(((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,a);
        } else GLES20.glClearColor(r,g,b,a);
    }
    private static String rgb(int color) {
        return "vec3("+((color>>>16)&255)+".,"+((color>>>8)&255)+".,"+(color&255)+".)";
    }
    public static void labels(Object layer,Object collector) throws Exception {
        Object owner;
        try {owner=get(layer,"F");}
        catch(Throwable e){
            Object map=owners.get(layer);if(map==null)map=glOwner.get();
            if(map!=null)HudScene.presentationFailure(map,e);
            else if(gl.get())HudScene.presentationFailure(e);
            else call(layer,"bydHudOriginal_e",collector);
            return;
        }
        if(!owned(owner)) {
            if(gl.get())HudScene.presentationFailure(new IllegalStateException("HUD label owner is not registered"));
            else call(layer,"bydHudOriginal_e",collector);
            return;
        }
        own(layer,owners.get(owner));
        try {
            if(get(layer,"b")==null) {
                failure(owner,new IllegalStateException("owned HUD label layer has no labeler"));
                return;
            }
            Object discarded=type("cqsh").getConstructor(byte[].class).newInstance((Object)null);
            call(layer,"bydHudOriginal_e",discarded);
            // Preserve retirements while withholding new static labels in either labeler implementation.
            ((Set)get(collector,"a")).addAll((Set)get(discarded,"a"));
        }catch(Throwable e){failure(owner,e);}
    }
    public static Object label(Object factory,Object label) {
        if(!owned(factory)) {
            if(gl.get()) {
                IllegalStateException error=new IllegalStateException("HUD label factory is not registered");
                HudScene.presentationFailure(error);
                return emptyLabel(error);
            }
            return label;
        }
        try { synchronized(labelCopies) {
            IdentityHashMap<Object,Object> copies=labelCopies.computeIfAbsent(factory,k->new IdentityHashMap<>());
            Object existing=copies.get(label);if(existing!=null)return existing;
            Object copy=filterLabel(label);
            copies.put(label,copy);copies.put(copy,copy);
            return copy;
        }}catch(Throwable e){failure(factory,e);return emptyLabel(e);}
    }
    public static Object labelLookup(Object factory,Object label) {
        if(!owned(factory)) {
            if(gl.get()) {
                IllegalStateException error=new IllegalStateException("HUD label lookup factory is not registered");
                HudScene.presentationFailure(error);
                return emptyLabel(error);
            }
            return label;
        }
        synchronized(labelCopies) {
            IdentityHashMap<Object,Object> copies=labelCopies.get(factory);
            Object existing=copies==null?null:copies.get(label);
            if(existing!=null)return existing;
            try {
                Object filtered=filterLabel(label);
                if(copies!=null){copies.put(label,filtered);copies.put(filtered,filtered);}
                return filtered;
            }catch(Throwable e){failure(factory,e);return emptyLabel(e);}
        }
    }
    private static Object emptyLabel(Throwable original) {
        try {return get(type("cnal"),"a");}
        catch(Throwable e) {
            Throwable cause=root(original), fallback=root(e);
            if(cause!=fallback)cause.addSuppressed(fallback);
            throw new IllegalStateException("cannot create empty owned HUD label",cause);
        }
    }
    public static void labelRemove(Object factory,Object handle) {
        try { synchronized(labelCopies) {
            IdentityHashMap<Object,Object> copies=labelCopies.get(factory);
            if(copies==null)return;
            Object removed=call(handle,"j");
            copies.values().removeIf(value->value==removed);
        }}catch(Throwable e){failure(factory,e);}
    }
    private static Object filterLabel(Object label) throws Exception {
        Object builder=call(label,"toBuilder");call(builder,"copyOnWrite");Object copy=get(builder,"instance");
        for(String field:new String[]{"e","f"}) {
            Object span=get(copy,field); if(span==null)continue;
            Object spanBuilder=call(span,"toBuilder");call(spanBuilder,"copyOnWrite");Object spanCopy=get(spanBuilder,"instance");
            call(spanCopy,"a"); List atoms=(List)get(spanCopy,"c");
            for(int index=0;index<atoms.size();index++) {
                Object atom=atoms.get(index);if(((String)get(atom,"c")).isEmpty())continue;
                Object atomBuilder=call(atom,"toBuilder");call(atomBuilder,"copyOnWrite");Object atomCopy=get(atomBuilder,"instance");
                set(atomCopy,"c","");set(atomCopy,"b",((Number)get(atomCopy,"b")).intValue()&~1);
                // An atom can contain text AND an icon/style reference; retain all nontext fields.
                atoms.set(index,call(atomBuilder,"build"));
            }
            set(copy,field,call(spanBuilder,"build"));
        }
        return call(builder,"build");
    }
    public static Resources positionResources(Resources resources,Object map) {
        if(!owned(map))return resources;
        return new PositionResources(resources,map);
    }
    public static int positionAccuracyOutline(Object factory,int id,int color) {
        // aotw.m forces the derived stroke opaque after reading the transparent fill.
        return MarkerPalette.accuracyOutline(owned(factory),id,color);
    }
    private static final class PositionResources extends Resources {
        final Resources original;
        final Object map;
        final int marker;
        final Set<Integer> noted=new HashSet<>();
        PositionResources(Resources original,Object map) {
            super(original.getAssets(),original.getDisplayMetrics(),original.getConfiguration());
            this.original=original;this.map=map;this.marker=HudScene.paletteFor(map).marker;
        }
        private int tint(int id,int color) {
            if(MarkerPalette.accuracyHaloColor(id))return Color.TRANSPARENT;
            float[] hsv=new float[3]; Color.colorToHSV(color,hsv);
            return hsv[1]>.2f && hsv[0]>=180f && hsv[0]<=255f ? (color&0xff000000)|(marker&0xffffff) : color;
        }
        @Override public int getColor(int id) { return tint(id,original.getColor(id)); }
        @Override public int getColor(int id,Theme theme) { return tint(id,original.getColor(id,theme)); }
    }
    /** Replaces only bqcz's decode call; its lazy bitmap cache and release path remain stock. */
    public static Bitmap positionBitmap(Resources resources,int id) {
        Bitmap decoded=BitmapFactory.decodeResource(resources,id);
        if(!(resources instanceof PositionResources) || decoded==null
                || (MarkerPalette.sourceFill(id)==0 && !MarkerPalette.haloTexture(id)))return decoded;
        PositionResources owner=(PositionResources)resources;
        Bitmap output=null;
        try {
            int width=decoded.getWidth(),height=decoded.getHeight();
            int[] pixels=new int[width*height];
            decoded.getPixels(pixels,0,width,0,0,width,height);
            int changed=MarkerPalette.recolor(id,pixels,owner.marker);
            output=decoded.copy(Bitmap.Config.ARGB_8888,true);
            if(output==null)throw new IllegalStateException("HUD marker bitmap copy returned null: 0x"+Integer.toHexString(id));
            output.setDensity(decoded.getDensity());
            output.setPixels(pixels,0,width,0,0,width,height);
            if(com.bydhud.mapcapture.LifecycleJournalTransport.detailed()) synchronized(owner.noted) {
                if(owner.noted.add(id))HudScene.event("hud_marker_texture",owner.map,
                        "resource=0x"+Integer.toHexString(id)+" owned=true size="+width+"x"+height
                                +" changedPixels="+changed+(MarkerPalette.haloTexture(id)?" halo=hidden":" rgb="+Integer.toHexString(owner.marker)));
            }
            return output;
        } catch(RuntimeException | Error failure) {
            if(output!=null)output.recycle();
            throw failure;
        } finally { decoded.recycle(); }
    }
    public static void positionFactory(Object factory) {
        Object map=null;
        try {
            map=get(factory,"d"); if(!owned(map))return;
            own(factory,map); own(get(factory,"g"),map); own(get(factory,"h"),map);
        }catch(Throwable e){failure(map,e);}
    }
    public static String caption(Object owner,String text) { return owned(owner)||gl.get()?"":text; }
    public static Object callout(Object factory,Object maneuver,Object result) {
        if(!owned(factory)) {
            if(gl.get()) {
                IllegalStateException error=new IllegalStateException("HUD callout factory is not registered");
                HudScene.presentationFailure(error);
                return emptyLabel(error);
            }
            return result;
        }
        try {
            if(maneuver==get(type("ajjj"),"g"))return get(type("cnal"),"a");
            return filterLabel(result);
        }catch(Throwable e){failure(factory,e);return emptyLabel(e);}
    }
    public static Bitmap shield(Object owner,String text,boolean x,String text2,boolean y) throws Exception {
        if(!owned(owner) && gl.get()) {
            HudScene.presentationFailure(glOwner.get(),new IllegalStateException("HUD shield owner is not registered"));
            return emptyShieldBitmap();
        }
        if(owned(owner))return emptyShieldBitmap();
        return (Bitmap)call(owner,"bydHudOriginal_a",text,x,text2,y);
    }
    public static Object shieldObject(Object owner,Object point,String text,String secondary,boolean a,boolean b) throws Exception {
        if(!owned(owner) && gl.get()) {
            HudScene.presentationFailure(glOwner.get(),new IllegalStateException("HUD shield object owner is not registered"));
            return emptyShieldObject();
        }
        if(!owned(owner))return call(owner,"bydHudOriginal_b",point,text,secondary,a,b);
        boolean previous=shieldBuilding.get();
        Throwable previousFailure=shieldFailure.get();
        shieldBuilding.set(true);shieldFailure.remove();
        Object result=null;Throwable originalFailure=null,filterFailure=null;
        try {result=call(owner,"bydHudOriginal_b",point,text,secondary,a,b);}
        catch(Throwable e){originalFailure=root(e);}
        finally {filterFailure=shieldFailure.get();shieldBuilding.set(previous);shieldFailure.set(previousFailure);}
        if(originalFailure!=null) {
            if(filterFailure!=null && filterFailure!=originalFailure)originalFailure.addSuppressed(filterFailure);
            failure(owner,originalFailure);
            return emptyShieldObject();
        }
        if(filterFailure!=null) {
            failure(owner,filterFailure);
            return emptyShieldObject();
        }
        return result;
    }
    public static void shieldBuilder(Object builder) {
        if(!shieldBuilding.get())return;
        try {
            Object protoBuilder=call(builder,"e");call(protoBuilder,"copyOnWrite");Object proto=get(protoBuilder,"instance");
            set(proto,"e",get(type("cnag"),"a"));set(proto,"f",get(type("cnag"),"a"));
            set(proto,"b",((Number)get(proto,"b")).intValue()&~3);
        }catch(Throwable e){
            shieldFailure.set(root(e));
        }
    }
    private static Bitmap emptyShieldBitmap() {
        Bitmap result=emptyShieldBitmap;
        if(result!=null)return result;
        IllegalStateException error=new IllegalStateException("owned HUD shield fallback was not prepared before Phoenix start");
        HudScene.presentationFailure(glOwner.get(),error);
        throw error;
    }
    private static Object emptyShieldObject() {
        Object result=emptyShieldObject;
        if(result!=null)return result;
        synchronized(HudStyle.class) {
            result=emptyShieldObject;
            if(result!=null)return result;
            try {
                Class<?> booq=type("booq"),boor=type("boor");
                Object clock=boor.getConstructor(long.class).newInstance(0L);
                Object proxy=Proxy.newProxyInstance(booq.getClassLoader(),new Class<?>[]{booq},(p,m,args)->{
                    if(m.getDeclaringClass()==Object.class) {
                        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
                        if(m.getName().equals("equals"))return p==args[0];
                        return "MapsHudEmptyShield";
                    }
                    if(m.getReturnType()==void.class)return null;
                    if(m.getName().equals("a") && m.getReturnType()==boor)return emptyShieldClock;
                    if(m.getName().equals("j") && m.getReturnType()==boolean.class)return false;
                    throw new IllegalStateException("unexpected empty-shield method "+m);
                });
                emptyShieldClock=clock;emptyShieldObject=proxy;return proxy;
            }catch(Throwable e){throw new IllegalStateException("cannot create valid empty HUD shield",root(e));}
        }
    }
    public static void routeDelivery(Object task) throws Exception {
        Object route=null;
        boolean hud=false;
        try {
            int kind=((Number)get(task,"d")).intValue();
            if(kind==5 || kind==6) {
                Object callback=get(task,"c");route=get(callback,"c");
                hud=routes.containsKey(route);
                if(hud) synchronized(get(route,"Y")) {
                    boolean retired=Boolean.TRUE.equals(routes.get(route));
                    Object slot=get(route,kind==5?"au":"av");
                    if(!retired && ((java.util.concurrent.atomic.AtomicBoolean)get(route,"aR")).get() && get(slot,"b")==callback) {
                        call(task,"bydHudOriginal_run");return;
                    }
                    // Map destruction owns all remaining resources after retirement.
                    if(!retired)call(get(task,"b"),"U");
                    return;
                }
            }
        }catch(Throwable e){failure(route,e);if(hud)return;}
        call(task,"bydHudOriginal_run");
    }
}
