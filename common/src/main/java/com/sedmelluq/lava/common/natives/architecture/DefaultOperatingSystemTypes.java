package com.sedmelluq.lava.common.natives.architecture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.function.Predicate;

public enum DefaultOperatingSystemTypes implements OperatingSystemType {
    LINUX("linux", "lib", ".so"),
    LINUX_MUSL("linux-musl", "lib", ".so"),
    ANDROID("android", "lib", ".so"),
    WINDOWS("win", "", ".dll"),
    DARWIN("darwin", "lib", ".dylib"),
    SOLARIS("solaris", "lib", ".so");

    private static final Logger log = LoggerFactory.getLogger(DefaultOperatingSystemTypes.class);
    private static final SystemProbe DEFAULT_SYSTEM_PROBE = new DefaultSystemProbe();
    private static volatile Boolean cachedAndroid;
    private static volatile Boolean cachedMusl;

    private final String identifier;
    private final String libraryFilePrefix;
    private final String libraryFileSuffix;

    DefaultOperatingSystemTypes(String identifier, String libraryFilePrefix, String libraryFileSuffix) {
        this.identifier = identifier;
        this.libraryFilePrefix = libraryFilePrefix;
        this.libraryFileSuffix = libraryFileSuffix;
    }

    @Override
    public String identifier() {
        return identifier;
    }

    @Override
    public String libraryFilePrefix() {
        return libraryFilePrefix;
    }

    @Override
    public String libraryFileSuffix() {
        return libraryFileSuffix;
    }

    public static OperatingSystemType detect() {
        return detect(DEFAULT_SYSTEM_PROBE, true);
    }

    static OperatingSystemType detect(SystemProbe systemProbe) {
        return detect(systemProbe, false);
    }

    private static OperatingSystemType detect(SystemProbe systemProbe, boolean useCache) {
        String osFullName = systemProbe.getProperty("os.name");

        if (osFullName.startsWith("Windows")) {
            return WINDOWS;
        } else if (osFullName.startsWith("Mac OS X")) {
            return DARWIN;
        } else if (osFullName.startsWith("Solaris")) {
            return SOLARIS;
        } else if (osFullName.toLowerCase().startsWith("linux")) {
            if (checkAndroid(systemProbe, useCache)) {
                return ANDROID;
            }

            return checkMusl(systemProbe, useCache) ? LINUX_MUSL : LINUX;
        } else {
            throw new IllegalArgumentException("Unknown operating system: " + osFullName);
        }
    }

    private static boolean checkAndroid(SystemProbe systemProbe, boolean useCache) {
        if (!useCache) {
            return detectAndroid(systemProbe);
        }

        Boolean b = cachedAndroid;
        if (b == null) {
            synchronized (DefaultOperatingSystemTypes.class) {
                b = cachedAndroid;
                if (b == null) {
                    b = cachedAndroid = detectAndroid(systemProbe);
                }
            }
        }
        return b;
    }

    private static boolean detectAndroid(SystemProbe systemProbe) {
        boolean check = isAndroidRuntime(systemProbe) ||
            isAndroidEnvironment(systemProbe) ||
            hasAndroidSystemPaths(systemProbe) ||
            hasMappedLibrary(systemProbe, DefaultOperatingSystemTypes::isAndroidMappedLibrary);

        log.debug("is android: {}", check);
        return check;
    }

    private static boolean isAndroidRuntime(SystemProbe systemProbe) {
        return propertyContains(systemProbe, "java.runtime.name", "android") ||
            propertyContains(systemProbe, "java.vm.name", "dalvik") ||
            propertyContains(systemProbe, "java.vm.name", "art") ||
            propertyContains(systemProbe, "java.vendor", "android") ||
            propertyContains(systemProbe, "java.vm.vendor", "android");
    }

    private static boolean propertyContains(SystemProbe systemProbe, String propertyName, String value) {
        String property = systemProbe.getProperty(propertyName);
        return property != null && property.toLowerCase(Locale.ROOT).contains(value);
    }

    private static boolean isAndroidEnvironment(SystemProbe systemProbe) {
        return hasEnvironment(systemProbe, "ANDROID_ROOT") ||
            hasEnvironment(systemProbe, "ANDROID_DATA") ||
            hasEnvironment(systemProbe, "ANDROID_ART_ROOT") ||
            hasEnvironment(systemProbe, "ANDROID_I18N_ROOT") ||
            hasEnvironment(systemProbe, "ANDROID_TZDATA_ROOT");
    }

    private static boolean hasEnvironment(SystemProbe systemProbe, String name) {
        String value = systemProbe.getEnvironment(name);
        return value != null && !value.trim().isEmpty();
    }

    private static boolean hasAndroidSystemPaths(SystemProbe systemProbe) {
        return systemProbe.pathExists("/system/build.prop") ||
            systemProbe.pathExists("/system/bin/linker") ||
            systemProbe.pathExists("/system/bin/linker64") ||
            systemProbe.pathExists("/apex/com.android.runtime");
    }

    private static boolean isAndroidMappedLibrary(String line) {
        return line.contains("/bionic/libc.so") ||
            line.contains("/apex/com.android.runtime/") ||
            line.contains("/system/lib/libc.so") ||
            line.contains("/system/lib64/libc.so");
    }

    private static boolean checkMusl(SystemProbe systemProbe, boolean useCache) {
        if (!useCache) {
            boolean check = hasMappedLibrary(systemProbe, line -> line.contains("-musl-"));
            log.debug("is musl: {}", check);
            return check;
        }

        Boolean b = cachedMusl;
        if (b == null) {
            synchronized (DefaultOperatingSystemTypes.class) {
                b = cachedMusl;
                if (b == null) {
                    boolean check = hasMappedLibrary(systemProbe, line -> line.contains("-musl-"));
                    log.debug("is musl: {}", check);
                    b = cachedMusl = check;
                }
            }
        }
        return b;
    }

    private static boolean hasMappedLibrary(SystemProbe systemProbe, Predicate<String> lineMatcher) {
        try {
            return systemProbe.anyProcSelfMapsLineMatches(lineMatcher);
        } catch (IOException fail) {
            log.error("Failed to detect libc type, assuming non-matching", fail);
            return false;
        }
    }

    interface SystemProbe {
        String getProperty(String name);

        String getEnvironment(String name);

        boolean pathExists(String path);

        boolean anyProcSelfMapsLineMatches(Predicate<String> lineMatcher) throws IOException;
    }

    private static class DefaultSystemProbe implements SystemProbe {
        @Override
        public String getProperty(String name) {
            return System.getProperty(name);
        }

        @Override
        public String getEnvironment(String name) {
            return System.getenv(name);
        }

        @Override
        public boolean pathExists(String path) {
            return Files.exists(Paths.get(path));
        }

        @Override
        public boolean anyProcSelfMapsLineMatches(Predicate<String> lineMatcher) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(Paths.get("/proc/self/maps"))))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lineMatcher.test(line)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }
}
