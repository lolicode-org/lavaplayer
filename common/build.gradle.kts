plugins {
    `java-library`
}

base {
    archivesName = "lava-common"
}

dependencies {
    implementation(libs.slf4j)
    implementation(libs.commons.io)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
