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
    void androidSystemNamesUseAndroidPrefix() {
        assertEquals(
            "android-armhf",
            new SystemType(DefaultArchitectureTypes.ARM_HF, DefaultOperatingSystemTypes.ANDROID).formatSystemName()
        );
        assertEquals(
            "android-aarch64",
            new SystemType(DefaultArchitectureTypes.ARMv8_64, DefaultOperatingSystemTypes.ANDROID).formatSystemName()
        );
    }
}
