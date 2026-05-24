package com.sedmelluq.lava.common.natives.architecture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultArchitectureTypesTest {

    @Test
    void androidArmv7UsesHardFloatTarget() {
        assertEquals(
            DefaultArchitectureTypes.ARM_HF,
            DefaultArchitectureTypes.detect("armv7l", DefaultOperatingSystemTypes.ANDROID)
        );
    }

    @Test
    void linuxArmv7KeepsExistingSoftFloatMapping() {
        assertEquals(
            DefaultArchitectureTypes.ARM,
            DefaultArchitectureTypes.detect("armv7l", DefaultOperatingSystemTypes.LINUX)
        );
    }

    @Test
    void androidArm64UsesAarch64Target() {
        assertEquals(
            DefaultArchitectureTypes.ARMv8_64,
            DefaultArchitectureTypes.detect("arm64-v8a", DefaultOperatingSystemTypes.ANDROID)
        );
    }

    @Test
    void androidX86UsesX86Target() {
        assertEquals(
            DefaultArchitectureTypes.X86_32,
            DefaultArchitectureTypes.detect("x86", DefaultOperatingSystemTypes.ANDROID)
        );
    }

    @Test
    void androidX86_64UsesX86_64Target() {
        assertEquals(
            DefaultArchitectureTypes.X86_64,
            DefaultArchitectureTypes.detect("x86_64", DefaultOperatingSystemTypes.ANDROID)
        );
    }

    @Test
    void androidSystemNamesUseAndroidPrefix() {
        assertEquals(
            "android-armhf",
            new SystemType(DefaultArchitectureTypes.ARM_HF, DefaultOperatingSystemTypes.ANDROID).formatSystemName()
        );
        assertEquals(
            "android-aarch64",
            new SystemType(DefaultArchitectureTypes.ARMv8_64, DefaultOperatingSystemTypes.ANDROID).formatSystemName()
        );
        assertEquals(
            "android-x86",
            new SystemType(DefaultArchitectureTypes.X86_32, DefaultOperatingSystemTypes.ANDROID).formatSystemName()
        );
        assertEquals(
            "android-x86-64",
            new SystemType(DefaultArchitectureTypes.X86_64, DefaultOperatingSystemTypes.ANDROID).formatSystemName()
        );
    }
}
