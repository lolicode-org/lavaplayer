@file:Suppress("UnstableApiUsage")

import java.io.ByteArrayOutputStream

data class PackageRelocation(
    val from: String,
    val to: String
) {
    val fromPath = from.replace('.', '/')
    val toPath = to.replace('.', '/')
}

val packageRelocations = listOf(
    PackageRelocation("com.sedmelluq.discord.lavaplayer", "org.lolicode.lavaplayer"),
    PackageRelocation("com.sedmelluq.lava.common", "org.lolicode.lavaplayer.common"),
    PackageRelocation("com.sedmelluq.lavaplayer.extensions", "org.lolicode.lavaplayer.extensions")
)

val publicationGroupId = "org.lolicode"

fun relocatePackageText(text: String): String = packageRelocations.fold(text) { current, relocation ->
    current
        .replace(relocation.from, relocation.to)
        .replace(relocation.fromPath, relocation.toPath)
}

fun relocatePackagePath(path: String): String = packageRelocations.fold(path) { current, relocation ->
    current.replace(relocation.fromPath, relocation.toPath)
}

fun String.capitalized(): String = replaceFirstChar {
    if (it.isLowerCase()) it.titlecase() else it.toString()
}

fun Project.firstNonBlank(vararg keys: String): String? = keys.asSequence()
    .firstNotNullOfOrNull { key -> (findProperty(key)?.toString() ?: System.getenv(key))?.takeIf { it.isNotBlank() } }

val (gitVersion, release) = versionFromGit()
logger.lifecycle("Version: $gitVersion (release: $release)")

allprojects {
    group = publicationGroupId
    version = gitVersion

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    if (project.name == "extensions-project" || project.name == "natives") {
        return@subprojects
    }

    apply<JavaPlugin>()
    apply(plugin = "maven-publish")

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        withSourcesJar()
        withJavadocJar()
    }

    val sourceSets = the<SourceSetContainer>()
    listOf("main", "test").forEach { sourceSetName ->
        val sourceDirectory = layout.projectDirectory.dir("src/$sourceSetName/java")
        if (!sourceDirectory.asFile.exists()) {
            return@forEach
        }

        val relocatedDirectory = layout.buildDirectory.dir("relocated-src/$sourceSetName/java")
        val relocateTask = tasks.register<Copy>("relocate${sourceSetName.capitalized()}Sources") {
            from(sourceDirectory)
            into(relocatedDirectory)
            include("**/*.java")
            includeEmptyDirs = false
            duplicatesStrategy = DuplicatesStrategy.FAIL
            filteringCharset = "UTF-8"

            eachFile {
                relativePath = RelativePath(
                    true,
                    *relocatePackagePath(relativePath.pathString).split('/').toTypedArray()
                )
            }

            filter { line: String -> relocatePackageText(line) }
        }

        sourceSets.named(sourceSetName) {
            java.setSrcDirs(listOf(relocatedDirectory))
        }

        tasks.named(sourceSets.named(sourceSetName).get().compileJavaTaskName) {
            dependsOn(relocateTask)
        }

        if (sourceSetName == "main") {
            tasks.named("javadoc") {
                dependsOn(relocateTask)
            }

            tasks.named("sourcesJar") {
                dependsOn(relocateTask)
            }
        }
    }

    afterEvaluate {
        configure<PublishingExtension> {
            publications {
                register<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifactId = project.the<BasePluginExtension>().archivesName.get()

                    pom {
                        name = "lavaplayer"
                        description = "Lavalink's lavaplayer forked by lolicode.org"
                        url = "https://github.com/lolicode-org/lavaplayer"

                        licenses {
                            license {
                                name = "The Apache License, Version 2.0"
                                url = "https://github.com/lolicode-org/lavaplayer/blob/main/LICENSE"
                            }
                        }

                        developers {
                            developer {
                                id = "freyacodes"
                                name = "Freya Arbjerg"
                                url = "https://www.arbjerg.dev"
                            }
                        }

                        scm {
                            url = "https://github.com/lolicode-org/lavaplayer/"
                            connection = "scm:git:git://github.com/lolicode-org/lavaplayer.git"
                            developerConnection = "scm:git:ssh://git@github.com/lolicode-org/lavaplayer.git"
                        }
                    }
                }
            }

            repositories {
                val stagingDir = project.firstNonBlank(
                    "stagingDir",
                    "moemusic.publish.stagingDir",
                    "STAGING_DIR",
                    "LOCAL_REPO_DIR",
                )

                if (stagingDir != null) {
                    maven {
                        name = "LocalR2Staging"
                        url = uri(rootProject.file(stagingDir))
                    }
                }

                val githubPackagesUsername = project.firstNonBlank(
                    "githubPackagesUsername",
                    "gpr.user",
                    "GITHUB_PACKAGES_USERNAME",
                    "GITHUB_ACTOR"
                )
                val githubPackagesToken = project.firstNonBlank(
                    "githubPackagesToken",
                    "gpr.key",
                    "GITHUB_PACKAGES_TOKEN",
                    "GITHUB_TOKEN"
                )

                if (githubPackagesUsername != null && githubPackagesToken != null) {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/lolicode-org/lavaplayer")

                        credentials {
                            username = githubPackagesUsername
                            password = githubPackagesToken
                        }
                    }
                }
            }
        }
    }
}

fun versionFromGit(): Pair<String, Boolean> {
    val overrideVersion = sequenceOf(
        System.getenv("LAVAPLAYER_VERSION"),
        findProperty("lavaplayer.version")?.toString(),
        findProperty("version")?.toString()?.takeIf { it != "unspecified" },
    ).firstOrNull { !it.isNullOrBlank() }

    if (overrideVersion != null) {
        return overrideVersion to !overrideVersion.endsWith("-SNAPSHOT", ignoreCase = true)
    }

    fun runGit(vararg args: String): String? {
        return try {
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val process = ProcessBuilder(listOf("git", *args))
                .directory(rootDir)
                .start()

            process.inputStream.use { it.copyTo(stdout) }
            process.errorStream.use { it.copyTo(stderr) }

            if (process.waitFor() == 0) {
                stdout.toString().trim()
            } else {
                val errorSuffix = stderr.toString().trim().let { if (it.isEmpty()) "" else " ($it)" }
                logger.info("Git command failed: git ${args.joinToString(" ")}$errorSuffix")
                null
            }
        } catch (e: Exception) {
            logger.info("Git command failed to start: git ${args.joinToString(" ")} (${e.message})")
            null
        }
    }

    val githubRef = System.getenv("GITHUB_REF")
    val isTagRef = githubRef?.startsWith("refs/tags/") == true
    val isBranchOrPullRef = githubRef?.startsWith("refs/heads/") == true || githubRef?.startsWith("refs/pull/") == true

    val tagFromEnv = if (isTagRef) githubRef.removePrefix("refs/tags/") else null

    val headTag = when {
        tagFromEnv != null -> tagFromEnv
        isBranchOrPullRef -> null // In CI on a branch/PR, ignore HEAD tags to prevent release collisions
        else -> runGit("tag", "--points-at", "HEAD")
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() && !it.endsWith("-SNAPSHOT", ignoreCase = true) }
    }

    val clean = runGit("status", "--porcelain")?.isBlank() == true
    if (headTag != null && (clean || isTagRef)) {
        return headTag to true
    }

    fun computeSnapshotVersion(base: String): String {
        return if (base.endsWith("-SNAPSHOT", ignoreCase = true)) {
            base
        } else {
            val parts = base.split(".")
            if (parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
                "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}-SNAPSHOT"
            } else {
                "$base-SNAPSHOT"
            }
        }
    }

    val baseVersionProp = findProperty("baseVersion")?.toString()?.takeIf { it.isNotBlank() }
    if (baseVersionProp != null) {
        return computeSnapshotVersion(baseVersionProp) to false
    }

    val latestTag = runGit("describe", "--tags", "--abbrev=0")
    val snapshotVersion = latestTag?.let { computeSnapshotVersion(it) } ?: "next-SNAPSHOT"

    return snapshotVersion to false
}
