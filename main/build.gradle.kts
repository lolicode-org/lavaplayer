import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

plugins {
    `java-library`
}

base {
    archivesName = "lavaplayer"
}

val generatedVersionResourcesDir = layout.buildDirectory.dir("generated-resources/version")
val versionResourceFile = generatedVersionResourcesDir.map {
    it.file("org/lolicode/lavaplayer/tools/version.txt")
}

sourceSets {
    named("main") {
        resources.srcDir(layout.buildDirectory.dir("embedded-natives"))
        resources.srcDir(generatedVersionResourcesDir)
    }
}

dependencies {
    api(projects.common)
    implementation(libs.slf4j)

    api(libs.httpclient)
    implementation(libs.commons.io)
    implementation(libs.commons.codec)

    api(libs.jackson.core)
    api(libs.jackson.databind)

    implementation(libs.jsoup)
    implementation(libs.json)

    implementation(libs.intellij.annotations)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.logback.classic)
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
        }
    }

    val updateVersion by registering {
        inputs.property("version", version)
        outputs.file(versionResourceFile)

        doLast {
            val output = versionResourceFile.get().asFile.toPath()
            output.parent.createDirectories()
            output.writeText(version.toString())
        }
    }

    processResources {
        dependsOn(updateVersion)
    }

    sourcesJar {
        dependsOn(updateVersion)
    }
}
