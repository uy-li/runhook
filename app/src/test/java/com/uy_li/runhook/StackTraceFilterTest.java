package com.uy_li.runhook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StackTraceFilterTest {

    @Test
    public void hookFramesAreRemovedAndAppFramesStayInOrder() {
        StackTraceElement[] input = {
                new StackTraceElement("com.app.Main", "onCreate", "Main.java", 10),
                new StackTraceElement("de.robv.android.xposed.XposedBridge", "handleHookedMethod", "XposedBridge.java", 100),
                new StackTraceElement("io.github.lsposed.lspd.hooker.X", "callback", "X.java", 1),
                new StackTraceElement("com.elderdrivers.riru.edxp.Main", "a", "Main.java", 1),
                new StackTraceElement("org.meowcat.edxposed.Core", "b", "Core.java", 2),
                new StackTraceElement("LSPHooker_12", "callback", "LSPHooker_12.java", 3),
                new StackTraceElement("com.uy_li.runhook.JoyrunHook", "modify", "JoyrunHook.java", 50),
                null,
                new StackTraceElement("com.app.Util", "run", "Util.java", 3),
        };
        StackTraceElement[] output = StackTraceFilter.filter(input);
        assertEquals(2, output.length);
        assertEquals("com.app.Main", output[0].getClassName());
        assertEquals("com.app.Util", output[1].getClassName());
        assertEquals(0, StackTraceFilter.filter(new StackTraceElement[0]).length);
        assertEquals(null, StackTraceFilter.filter(null));
    }

    @Test
    public void frameworkLookupIsHiddenOnlyForAppCallers() {
        StackTraceElement[] app = {
                new StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
                new StackTraceElement("com.uy_li.runhook.StealthHook", "before", "StealthHook.java", 10),
                new StackTraceElement("de.robv.android.xposed.XposedBridge", "handleHookedMethod", "XposedBridge.java", 80),
                new StackTraceElement("java.lang.Class", "forName", "Class.java", 400),
                new StackTraceElement("com.target.Detector", "check", "Detector.java", 20),
        };
        assertTrue(StackTraceFilter.shouldHideFrameworkLookup(app));

        StackTraceElement[] trusted = {
                new StackTraceElement("com.uy_li.runhook.StealthHook", "before", "StealthHook.java", 10),
                new StackTraceElement("de.robv.android.xposed.XposedBridge", "invoke", "XposedBridge.java", 1),
                new StackTraceElement("de.robv.android.xposed.XposedHelpers", "findClass", "XposedHelpers.java", 50),
        };
        assertFalse(StackTraceFilter.shouldHideFrameworkLookup(trusted));
        assertFalse(StackTraceFilter.shouldHideFrameworkLookup(null));
        assertFalse(StackTraceFilter.shouldHideFrameworkLookup(new StackTraceElement[0]));
        assertTrue(StackTraceFilter.isSensitiveClassName("de.robv.android.xposed.XposedBridge"));
        assertTrue(StackTraceFilter.isSensitiveClassName("de.robv.android.xposed.XposedHelpers"));
        assertFalse(StackTraceFilter.isSensitiveClassName("de.robv.android.xposed.XposedInit"));
    }
}
