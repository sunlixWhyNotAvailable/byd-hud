// These tests fake vehicle/network boundaries. Keep the JVM RSA provider unchanged
// for plain JUnit tests that share the process with Robolectric.
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
package com.bydhud.app;
