package com.sedmelluq.lava.common.natives.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultOperatingSystemTypesTest {

    @Test
    void detectsAndroidFromEnvironmentBeforeLinux() {
        TestSystemProbe probe = new TestSystemProbe("Linux");
        probe.environment.put("ANDROID_ROOT", "/system");

        assertEquals(DefaultOperatingSystemTypes.ANDROID, DefaultOperatingSystemTypes.detect(probe));
    }

    @Test
    void detectsAndroidFromBionicMappingBeforeLinux() {
        TestSystemProbe probe = new TestSystemProbe("Linux");
        probe.mappedLibraries.add("7b80000000-7b80084000 r--p 00000000 fe:00 1 /apex/com.android.runtime/lib64/bionic/libc.so");

        assertEquals(DefaultOperatingSystemTypes.ANDROID, DefaultOperatingSystemTypes.detect(probe));
    }

    @Test
    void detectsMuslWhenLinuxHasMuslMappingAndNoAndroidSignals() {
        TestSystemProbe probe = new TestSystemProbe("Linux");
        probe.mappedLibraries.add("7f0000000000-7f0000010000 r-xp 00000000 00:00 0 /lib/ld-musl-aarch64.so.1");

        assertEquals(DefaultOperatingSystemTypes.LINUX_MUSL, DefaultOperatingSystemTypes.detect(probe));
    }

    @Test
    void detectsRegularLinuxWithoutAndroidOrMuslSignals() {
        TestSystemProbe probe = new TestSystemProbe("Linux");

        assertEquals(DefaultOperatingSystemTypes.LINUX, DefaultOperatingSystemTypes.detect(probe));
    }

    private static class TestSystemProbe implements DefaultOperatingSystemTypes.SystemProbe {
        private final Map<String, String> properties = new HashMap<>();
        private final Map<String, String> environment = new HashMap<>();
        private final Set<String> paths = new HashSet<>();
        private final Set<String> mappedLibraries = new HashSet<>();

        private TestSystemProbe(String osName) {
            properties.put("os.name", osName);
        }

        @Override
        public String getProperty(String name) {
            return properties.get(name);
        }

        @Override
        public String getEnvironment(String name) {
            return environment.get(name);
        }

        @Override
        public boolean pathExists(String path) {
            return paths.contains(path);
        }

        @Override
        public boolean anyProcSelfMapsLineMatches(Predicate<String> lineMatcher) throws IOException {
            for (String mappedLibrary : mappedLibraries) {
                if (lineMatcher.test(mappedLibrary)) {
                    return true;
                }
            }

            return false;
        }
    }
}
