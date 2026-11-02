package com.bydhud.mapshud;

import java.lang.reflect.*;

/** Reflection stays behind the exact APK hash/ABI checks in the build. */
final class Reflect {
    static Class<?> type(String name) throws Exception {
        return Class.forName(name, true, Reflect.class.getClassLoader());
    }
    static Field field(Object owner, String name) throws Exception {
        for (Class<?> c = owner instanceof Class ? (Class<?>)owner : owner.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(owner + "." + name);
    }
    static Object get(Object owner, String name) throws Exception { return field(owner,name).get(owner instanceof Class ? null : owner); }
    static void set(Object owner, String name, Object value) throws Exception { field(owner,name).set(owner instanceof Class ? null : owner,value); }
    private static boolean matches(Class<?>[] types, Object[] args) {
        if(types.length != args.length) return false;
        for(int i=0;i<types.length;i++) {
            Class<?> t=types[i];
            if(args[i]==null) { if(t.isPrimitive()) return false; continue; }
            if(t==int.class)t=Integer.class; else if(t==long.class)t=Long.class;
            else if(t==float.class)t=Float.class; else if(t==double.class)t=Double.class;
            else if(t==boolean.class)t=Boolean.class; else if(t==byte.class)t=Byte.class;
            else if(t==short.class)t=Short.class; else if(t==char.class)t=Character.class;
            if(!t.isInstance(args[i])) return false;
        }
        return true;
    }
    static Object call(Object owner, String name, Object... args) throws Exception {
        Class<?> cls=owner instanceof Class ? (Class<?>)owner : owner.getClass();
        for(Class<?> c=cls;c!=null;c=c.getSuperclass()) for(Method m:c.getDeclaredMethods()) {
            if(m.getName().equals(name) && matches(m.getParameterTypes(),args)) {
                m.setAccessible(true); return m.invoke(owner instanceof Class ? null : owner,args);
            }
        }
        throw new NoSuchMethodException(cls.getName()+"."+name+"/"+args.length);
    }
    static Object make(String name, Object... args) throws Exception {
        for(Constructor<?> c:type(name).getDeclaredConstructors()) if(matches(c.getParameterTypes(),args)) {
            c.setAccessible(true); return c.newInstance(args);
        }
        throw new NoSuchMethodException(name+".<init>/"+args.length);
    }
    static Object value(Object graph,String name) throws Exception { return call(get(graph,name),"a"); }
    static Object provider(Object value) throws Exception { return make("cvwv",value); }
    static Object lazy(Object graph,String name) throws Exception { return call(type("cvwu"),"b",get(graph,name)); }
    static Object proxy(String iface, InvocationHandler body) throws Exception {
        Class<?> c=type(iface);
        return Proxy.newProxyInstance(c.getClassLoader(),new Class<?>[]{c},(p,m,a)->{
            if(m.getDeclaringClass()==Object.class) {
                if(m.getName().equals("hashCode"))return System.identityHashCode(p);
                if(m.getName().equals("equals"))return p==a[0];
                return "MapsHud:"+iface;
            }
            return body.invoke(p,m,a);
        });
    }
    static Throwable root(Throwable e) {
        while(e instanceof InvocationTargetException && e.getCause()!=null && e.getSuppressed().length==0) e=e.getCause();
        return e;
    }
}
