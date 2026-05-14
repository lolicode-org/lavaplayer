rootProject.name = "lavaplayer"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":common",
    ":main",
    ":extensions",
    ":extensions:format-xm",
    ":natives"
)

// https://github.com/gradle/gradle/issues/19254
project(":extensions").name = "extensions-project"

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            common()
            others()
            test()
        }
    }
}

fun VersionCatalogBuilder.common() {
    library("slf4j", "org.slf4j", "slf4j-api").version("2.0.18")
    library("commons-io", "commons-io", "commons-io").version("2.22.0")
    library("intellij-annotations", "org.jetbrains", "annotations").version("26.1.0")

    version("jackson", "2.21.3")
    library("jackson-core", "com.fasterxml.jackson.core", "jackson-core").versionRef("jackson")
    library("jackson-databind", "com.fasterxml.jackson.core", "jackson-databind").versionRef("jackson")

    library("httpclient", "org.apache.httpcomponents", "httpclient").version("4.5.14")

    library("jsoup", "org.jsoup", "jsoup").version("1.22.2")
    library("json", "org.json", "json").version("20251224")
}

fun VersionCatalogBuilder.others() {
    library("ibxm-fork", "com.github.walkyst", "ibxm-fork").version("a75")
}

fun VersionCatalogBuilder.test() {
    library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").version("5.14.4")
    library("junit-platform-launcher", "org.junit.platform", "junit-platform-launcher").version("1.14.4")
    library("logback-classic", "ch.qos.logback", "logback-classic").version("1.5.32")
}
