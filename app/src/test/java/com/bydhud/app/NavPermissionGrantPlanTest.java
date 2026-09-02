package com.bydhud.app;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Executes the exact Context factory/rebind source, not the String overload or a copied policy. */
public final class NavPermissionGrantPlanTest {
    private static final String PACKAGE = "com.bydhud.app";
    private static final String NOTIFICATION = PACKAGE + "/" + PACKAGE + ".NavNotificationListenerService";
    private static final String ACCESSIBILITY = PACKAGE + "/" + PACKAGE + ".NavAccessibilityService";
    private static final String[] MALFORMED = {
            "missing-slash", "/Listener", "org.example/", "org.example/.",
            "org.example/Name/Extra", "org.example/.Bad Name", "org.example/.Bad\nName",
            "org. example/.Listener", "org.example/Bad..Name", "org.example/.Bad;id",
            "org.example/.Bad'Name", "org.example/$(id)", "org.example/.Bad" + (char) 1 + "Name"
    };

    @ClassRule public static final TemporaryFolder TEMP = new TemporaryFolder();
    private static URLClassLoader isolated;
    private static Class<?> contextType;
    private static Class<?> planType;

    @BeforeClass public static void compileLiveSourceWithIsolatedAndroidBoundary() throws Exception {
        File classes = TEMP.newFolder("classes");
        List<String> arguments = new ArrayList<>(Arrays.asList(
                "--release", "8", "-encoding", "UTF-8", "-classpath", classes.getAbsolutePath(),
                "-d", classes.getAbsolutePath()));
        addStub(arguments, "android/content/ComponentName.java", """
                package android.content;
                public final class ComponentName {
                    private final String pkg, name;
                    public ComponentName(String pkg, String name) {
                        this.pkg = java.util.Objects.requireNonNull(pkg);
                        this.name = java.util.Objects.requireNonNull(name);
                    }
                    public String getPackageName() { return pkg; }
                    public String getClassName() { return name; }
                    public String flattenToString() { return pkg + "/" + name; }
                    public static ComponentName unflattenFromString(String text) {
                        int slash = text.indexOf('/');
                        if (slash < 0 || slash + 1 >= text.length()) return null;
                        String pkg = text.substring(0, slash), name = text.substring(slash + 1);
                        if (name.startsWith(".")) name = pkg + name;
                        return new ComponentName(pkg, name);
                    }
                }
                """);
        addStub(arguments, "android/content/pm/ServiceInfo.java", """
                package android.content.pm;
                public final class ServiceInfo { public String packageName, name; }
                """);
        addStub(arguments, "android/content/pm/PackageManager.java", """
                package android.content.pm;
                public final class PackageManager {
                    public final java.util.List<String> lookups = new java.util.ArrayList<>();
                    public final java.util.List<Integer> flags = new java.util.ArrayList<>();
                    public final java.util.Map<String, ServiceInfo> installed = new java.util.HashMap<>();
                    public void declare(String requested, String pkg, String name) {
                        ServiceInfo info = new ServiceInfo(); info.packageName = pkg; info.name = name;
                        installed.put(requested, info);
                    }
                    public ServiceInfo getServiceInfo(android.content.ComponentName component, int flag)
                            throws NameNotFoundException {
                        String key = component.flattenToString(); lookups.add(key); flags.add(flag);
                        if (!installed.containsKey(key)) throw new NameNotFoundException();
                        return installed.get(key);
                    }
                    public static final class NameNotFoundException extends Exception { }
                }
                """);
        addStub(arguments, "android/content/Context.java", """
                package android.content;
                public final class Context {
                    private final android.content.pm.PackageManager manager = new android.content.pm.PackageManager();
                    public String getPackageName() { return "com.bydhud.app"; }
                    public android.content.pm.PackageManager getPackageManager() { return manager; }
                }
                """);
        Path source = Paths.get("src/main/java/com/bydhud/app/NavPermissionGrantPlan.java");
        if (!Files.exists(source)) source = Paths.get("app").resolve(source);
        assertTrue("live production source must exist", Files.isRegularFile(source));
        arguments.add(source.toAbsolutePath().toString());

        //JDK compiler is present in the Gradle JVM; reflection avoids requiring javax.tools in android.jar.
        Object compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null);
        assertNotNull("These source-behavior tests require the configured JDK", compiler);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        Method run = Class.forName("javax.tools.Tool").getMethod("run", InputStream.class,
                OutputStream.class, OutputStream.class, String[].class);
        int exit = (Integer) run.invoke(compiler, null, diagnostics, diagnostics, arguments.toArray(new String[0]));
        assertEquals(new String(diagnostics.toByteArray(), StandardCharsets.UTF_8), 0, exit);
        isolated = new URLClassLoader(new URL[]{classes.toURI().toURL()}, null);
        contextType = isolated.loadClass("android.content.Context");
        planType = isolated.loadClass("com.bydhud.app.NavPermissionGrantPlan");
    }

    @AfterClass public static void closeIsolatedClasses() throws Exception {
        if (isolated != null) isolated.close();
    }

    private static void addStub(List<String> arguments, String name, String source) throws Exception {
        Path file = TEMP.getRoot().toPath().resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        arguments.add(file.toString());
    }

    @Test public void grantPreservesUnresolvedForeignEntriesInBothLists() throws Exception {
        Fixture fixture = new Fixture();
        Object plan = fixture.plan("org.example/.Notify$Inner", ":org.other/.Access");
        assertValid(plan);
        assertEquals("org.example/org.example.Notify$Inner:" + NOTIFICATION, field(plan, "notificationListenersValue"));
        assertEquals(":org.other/org.other.Access:" + ACCESSIBILITY, field(plan, "accessibilityServicesValue"));
        assertEquals(Arrays.asList(
                put("enabled_notification_listeners", "org.example/org.example.Notify$Inner:" + NOTIFICATION),
                put("enabled_accessibility_services", ":org.other/org.other.Access:" + ACCESSIBILITY),
                put("accessibility_enabled", "1"), "appops set com.bydhud.app SYSTEM_ALERT_WINDOW allow"), commands(plan));
        fixture.assertOnlyOwnLookups();
    }

    @Test public void grantPreservesStaleSamePackageEntriesInBothLists() throws Exception {
        Fixture fixture = new Fixture();
        Object plan = fixture.plan(PACKAGE + "/.RetiredNotification", PACKAGE + "/.RetiredAccess");
        assertValid(plan);
        assertEquals(PACKAGE + "/" + PACKAGE + ".RetiredNotification:" + NOTIFICATION, field(plan, "notificationListenersValue"));
        assertEquals(":" + PACKAGE + "/" + PACKAGE + ".RetiredAccess:" + ACCESSIBILITY, field(plan, "accessibilityServicesValue"));
        fixture.assertOnlyOwnLookups();
    }

    @Test public void normalizationPreservesOrderAndDeduplicatesEquivalentFullRelativeAndBareNames() throws Exception {
        Fixture fixture = new Fixture();
        Object plan = fixture.plan(
                ": org.alpha/.Listener ::org.alpha/org.alpha.Listener:org.beta/Outer$Inner:"
                        + PACKAGE + "/.NavNotificationListenerService:" + NOTIFICATION,
                ":" + PACKAGE + "/.NavAccessibilityService:" + ACCESSIBILITY
                        + ":org.gamma/.Сервіс$Inner:org.gamma/org.gamma.Сервіс$Inner");
        assertValid(plan);
        assertEquals("org.alpha/org.alpha.Listener:org.beta/org.beta.Outer$Inner:" + NOTIFICATION,
                field(plan, "notificationListenersValue"));
        assertEquals(":" + ACCESSIBILITY + ":org.gamma/org.gamma.Сервіс$Inner", field(plan, "accessibilityServicesValue"));
        fixture.assertOnlyOwnLookups();
    }

    @Test public void nullEmptyAndSeparatorOnlySettingsRetainExistingEmptyListSemantics() throws Exception {
        for (String empty : new String[]{null, "", "null", "::", ": :"}) {
            Fixture fixture = new Fixture();
            Object plan = fixture.plan(empty, empty);
            assertValid(plan);
            assertEquals(NOTIFICATION, field(plan, "notificationListenersValue"));
            assertEquals(":" + ACCESSIBILITY, field(plan, "accessibilityServicesValue"));
            fixture.assertOnlyOwnLookups();
        }
    }

    @Test public void runtimeRebindRemovesOnlyCurrentOwnAccessibilityAndRestoresItLast() throws Exception {
        Fixture fixture = new Fixture();
        String retained = "org.example/org.example.Outer$Inner:" + PACKAGE + "/" + PACKAGE + ".RetiredAccess";
        List<String> commands = fixture.rebind(":org.example/.Outer$Inner:" + PACKAGE + "/.RetiredAccess:"
                + PACKAGE + "/.NavAccessibilityService:" + ACCESSIBILITY);
        assertEquals(Arrays.asList(put("enabled_accessibility_services", ":" + retained),
                put("enabled_accessibility_services", ":" + retained + ":" + ACCESSIBILITY),
                put("accessibility_enabled", "1")), commands);
        fixture.assertOnlyOwnLookups();
    }

    @Test public void resolvedOwnServiceNamesControlGrantAndRebindNotHardcodedClassSuffixes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.declare(NOTIFICATION, PACKAGE, ".InstalledListener");
        fixture.declare(ACCESSIBILITY, PACKAGE, "InstalledAccess$Service");
        String target = PACKAGE + "/" + PACKAGE + ".InstalledAccess$Service";
        String stale = PACKAGE + "/" + PACKAGE + ".NavAccessibilityService";
        Object plan = fixture.plan("", PACKAGE + "/.NavAccessibilityService");
        assertValid(plan);
        assertEquals(PACKAGE + "/" + PACKAGE + ".InstalledListener", field(plan, "notificationService"));
        assertEquals(target, field(plan, "accessibilityService"));
        fixture.assertOnlyOwnLookups();
        fixture.clearLookups();
        assertEquals(Arrays.asList(put("enabled_accessibility_services", ":" + stale),
                put("enabled_accessibility_services", ":" + stale + ":" + target),
                put("accessibility_enabled", "1")), fixture.rebind(stale + ":" + PACKAGE + "/.InstalledAccess$Service:" + target));
        fixture.assertOnlyOwnLookups();
    }

    @Test public void malformedExistingComponentInEitherListFailsClosed() throws Exception {
        for (String malformed : MALFORMED) {
            for (boolean inNotificationList : new boolean[]{true, false}) {
                Fixture fixture = new Fixture();
                Object plan = fixture.plan(inNotificationList ? malformed : "", inNotificationList ? "" : malformed);
                assertFalse(malformed, valid(plan));
                assertTrue(malformed, commands(plan).isEmpty());
                assertFalse(malformed, ((String) field(plan, "error")).isEmpty());
            }
        }
    }

    @Test public void malformedAccessibilityCannotProducePartialRebindCommands() throws Exception {
        for (String malformed : MALFORMED) assertTrue(malformed, new Fixture().rebind(malformed).isEmpty());
    }

    @Test public void eachMissingIntendedOwnServiceStillBlocksGrantAndRebind() throws Exception {
        for (String missing : new String[]{NOTIFICATION, ACCESSIBILITY}) {
            Fixture fixture = new Fixture();
            fixture.installed().remove(missing);
            Object plan = fixture.plan("org.example/.Listener", "org.example/.Access");
            assertFalse(valid(plan));
            assertTrue(commands(plan).isEmpty());
            fixture.assertOnlyOwnLookups();
            fixture.clearLookups();
            assertTrue(fixture.rebind(ACCESSIBILITY).isEmpty());
            fixture.assertOnlyOwnLookups();
        }
    }

    @Test public void invalidOwnServiceMetadataAndMissingContextStayFailClosed() throws Exception {
        for (String[] metadata : new String[][]{{"wrong.package", "Service"}, {PACKAGE, " "}, {PACKAGE, null}}) {
            Fixture fixture = new Fixture();
            fixture.declare(ACCESSIBILITY, metadata[0], metadata[1]);
            Object plan = fixture.plan("", "");
            assertFalse(valid(plan));
            assertTrue(commands(plan).isEmpty());
            assertTrue(fixture.rebind(ACCESSIBILITY).isEmpty());
        }
        Object plan = invoke("fromCurrentSettings", new Class<?>[]{contextType, String.class, String.class}, null, "", "");
        assertFalse(valid(plan));
        assertTrue(commands(plan).isEmpty());
    }

    @Test public void selectiveGrantFlagsDoNotChangeValidationOrTouchOtherSettings() throws Exception {
        Fixture fixture = new Fixture();
        Object plan = fixture.plan("org.example/.Notify", "org.example/.Access", false, true, false, false);
        assertValid(plan);
        assertEquals(Arrays.asList(put("enabled_accessibility_services", ":org.example/org.example.Access:" + ACCESSIBILITY)), commands(plan));
        fixture.assertOnlyOwnLookups();
    }

    @Test public void shellBoundaryStillQuotesValuesAndRejectsUnknownSettingKeys() throws Exception {
        String command = (String) invoke("secureSettingPutCommandForTest", new Class<?>[]{String.class, String.class},
                "enabled_accessibility_services", ":org.example/Outer$Inner'quoted");
        assertEquals("settings put secure enabled_accessibility_services ':org.example/Outer$Inner'\\''quoted'", command);
        try {
            invoke("secureSettingPutCommandForTest", new Class<?>[]{String.class, String.class}, "other_setting", "1");
            fail("Only the fixed permission setting keys are writable");
        } catch (IllegalArgumentException expected) { }
    }

    private static String put(String key, String value) { return "settings put secure " + key + " '" + value + "'"; }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static boolean valid(Object plan) throws Exception {
        Method method = planType.getDeclaredMethod("isValid"); method.setAccessible(true); return (Boolean) method.invoke(plan);
    }
    private static void assertValid(Object plan) throws Exception { assertTrue(String.valueOf(field(plan, "error")), valid(plan)); }
    @SuppressWarnings("unchecked") private static List<String> commands(Object plan) throws Exception { return (List<String>) field(plan, "shellCommands"); }
    private static Object invoke(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = planType.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, values); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw new AssertionError(failure.getCause());
        }
    }

    private static final class Fixture {
        final Object context = contextType.getConstructor().newInstance();
        final Object manager = contextType.getMethod("getPackageManager").invoke(context);
        Fixture() throws Exception {
            declare(NOTIFICATION, PACKAGE, ".NavNotificationListenerService");
            declare(ACCESSIBILITY, PACKAGE, ".NavAccessibilityService");
        }
        void declare(String requested, String pkg, String name) throws Exception {
            manager.getClass().getMethod("declare", String.class, String.class, String.class).invoke(manager, requested, pkg, name);
        }
        Object plan(String notification, String accessibility, boolean... grant) throws Exception {
            return invoke("fromCurrentSettings", new Class<?>[]{contextType, String.class, String.class, String.class,
                            boolean.class, boolean.class, boolean.class, boolean.class}, context, PACKAGE, notification, accessibility,
                    grant.length == 0 || grant[0], grant.length == 0 || grant[1], grant.length == 0 || grant[2], grant.length == 0 || grant[3]);
        }
        @SuppressWarnings("unchecked") List<String> rebind(String accessibility) throws Exception {
            return (List<String>) invoke("accessibilityRuntimeRebindCommands", new Class<?>[]{contextType, String.class, String.class},
                    context, PACKAGE, accessibility);
        }
        @SuppressWarnings("unchecked") java.util.Map<String, Object> installed() throws Exception {
            return (java.util.Map<String, Object>) manager.getClass().getField("installed").get(manager);
        }
        @SuppressWarnings("unchecked") List<String> lookups() throws Exception {
            return (List<String>) manager.getClass().getField("lookups").get(manager);
        }
        void clearLookups() throws Exception {
            lookups().clear(); ((List<?>) manager.getClass().getField("flags").get(manager)).clear();
        }
        void assertOnlyOwnLookups() throws Exception {
            assertEquals(Arrays.asList(NOTIFICATION, ACCESSIBILITY), lookups());
            assertEquals(Arrays.asList(0, 0), manager.getClass().getField("flags").get(manager));
        }
    }
}
