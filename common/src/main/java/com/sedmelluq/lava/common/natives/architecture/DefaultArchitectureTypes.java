package com.sedmelluq.lava.common.natives.architecture;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum DefaultArchitectureTypes implements ArchitectureType {
    ARM("arm", Arrays.asList("arm", "armeabi", "armv7b", "armv7l")),
    ARM_HF("armhf", Arrays.asList("armeabihf", "armeabi-v7a")),
    ARMv8_32("aarch32", Arrays.asList("armv8b", "armv8l")),
    ARMv8_64("aarch64", Arrays.asList("arm64", "aarch64", "aarch64_be", "arm64-v8a")),

    MIPS_32("mips", Arrays.asList("mips")),
    MIPS_32_LE("mipsel", Arrays.asList("mipsel", "mipsle")),
    MIPS_64("mips64", Arrays.asList("mips64")),
    MIPS_64_LE("mips64el", Arrays.asList("mips64el", "mips64le")),

    PPC_32("powerpc", Arrays.asList("ppc", "powerpc")),
    PPC_32_LE("powerpcle", Arrays.asList("ppcel", "ppcle")),
    PPC_64("ppc64", Arrays.asList("ppc64")),
    PPC_64_LE("ppc64le", Arrays.asList("ppc64el", "ppc64le")),

    X86_32("x86", Arrays.asList("x86", "i386", "i486", "i586", "i686")),
    X86_64("x86-64", Arrays.asList("x86_64", "amd64"));

    public final String identifier;
    public final List<String> aliases;

    DefaultArchitectureTypes(String identifier, List<String> aliases) {
        this.identifier = identifier;
        this.aliases = aliases;
    }

    @Override
    public String identifier() {
        return identifier;
    }

    public static ArchitectureType detect() {
        return detect(null);
    }

    public static ArchitectureType detect(OperatingSystemType operatingSystemType) {
        return detect(System.getProperty("os.arch"), operatingSystemType);
    }

    static ArchitectureType detect(String architectureName, OperatingSystemType operatingSystemType) {
        ArchitectureType androidType = detectAndroidArchitecture(architectureName, operatingSystemType);
        if (androidType != null) {
            return androidType;
        }

        ArchitectureType type = aliasMap.get(architectureName);

        if (type == null) {
            throw new IllegalArgumentException("Unknown architecture: " + architectureName);
        }

        return type;
    }

    private static ArchitectureType detectAndroidArchitecture(String architectureName, OperatingSystemType operatingSystemType) {
        if (operatingSystemType != DefaultOperatingSystemTypes.ANDROID) {
            return null;
        }

        return androidAliasMap.get(architectureName);
    }

    private static Map<String, ArchitectureType> aliasMap = createAliasMap();
    private static Map<String, ArchitectureType> androidAliasMap = createAndroidAliasMap();

    private static Map<String, ArchitectureType> createAliasMap() {
        Map<String, ArchitectureType> aliases = new HashMap<>();

        for (DefaultArchitectureTypes value : values()) {
            for (String alias : value.aliases) {
                aliases.put(alias, value);
            }
        }

        return aliases;
    }

    private static Map<String, ArchitectureType> createAndroidAliasMap() {
        Map<String, ArchitectureType> aliases = new HashMap<>();

        aliases.put("arm", ARM_HF);
        aliases.put("armeabi", ARM_HF);
        aliases.put("armv7b", ARM_HF);
        aliases.put("armv7l", ARM_HF);
        aliases.put("armeabi-v7a", ARM_HF);
        aliases.put("arm64", ARMv8_64);
        aliases.put("aarch64", ARMv8_64);
        aliases.put("arm64-v8a", ARMv8_64);
        aliases.put("x86", X86_32);
        aliases.put("i386", X86_32);
        aliases.put("i486", X86_32);
        aliases.put("i586", X86_32);
        aliases.put("i686", X86_32);
        aliases.put("x86_64", X86_64);
        aliases.put("amd64", X86_64);

        return aliases;
    }
}
