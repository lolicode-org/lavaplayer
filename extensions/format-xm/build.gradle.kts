plugins {
    `java-library`
}
base {
    archivesName = "lavaplayer-ext-format-xm"
}

dependencies {
    compileOnly(projects.main)
    implementation(libs.ibxm.fork)
    implementation(libs.slf4j)
}
