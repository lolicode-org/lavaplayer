import org.apache.tools.ant.taskdefs.condition.Os
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    base
}

val versionProps = Properties().apply {
    file("$projectDir/versions.properties").inputStream().use { load(it) }
}

val opusVersion = versionProps["opus"] as String
val mpg123Version = versionProps["mpg123"] as String
val oggVersion = versionProps["ogg"] as String
val vorbisVersion = versionProps["vorbis"] as String
val sampleRateVersion = versionProps["samplerate"] as String
val fdkAacVersion = versionProps["fdkaac"] as String
val opusSha256 = versionProps["opusSha256"] as String
val mpg123Sha256 = versionProps["mpg123Sha256"] as String
val oggSha256 = versionProps["oggSha256"] as String
val vorbisSha256 = versionProps["vorbisSha256"] as String
val sampleRateSha256 = versionProps["samplerateSha256"] as String
val fdkAacSha256 = versionProps["fdkaacSha256"] as String

fun verifySha256(dest: String, expectedSha256: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    file(dest).inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }

            digest.update(buffer, 0, count)
        }
    }

    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual.equals(expectedSha256, ignoreCase = true)) {
        "Checksum mismatch for $dest. Expected $expectedSha256, got $actual."
    }
}

fun extractTarArchive(archivePath: String, unpackPath: String) {
    val process = ProcessBuilder("tar", "xf", archivePath, "-C", unpackPath)
        .inheritIO()
        .start()

    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "Failed to extract archive $archivePath."
    }
}

fun downloadFile(url: String, dest: String, sha256: String) {
    val destFile = file(dest)
    destFile.parentFile.mkdirs()
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    check(connection.responseCode in 200..299) {
        "Failed to download $url, HTTP ${connection.responseCode}."
    }
    connection.inputStream.use { input ->
        destFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    verifySha256(dest, sha256)
}

tasks.register("load") {
    doLast {
        if (!file("$projectDir/samplerate/src").exists()) {
            val downloadPath = "${layout.buildDirectory.get()}/tmp/libsamplerate.tar.xz"
            val unpackPath = "${layout.buildDirectory.get()}/tmp"

            downloadFile(
                "https://github.com/libsndfile/libsamplerate/releases/download/$sampleRateVersion/libsamplerate-$sampleRateVersion.tar.xz",
                downloadPath,
                sampleRateSha256
            )

            extractTarArchive(downloadPath, unpackPath)

            copy {
                from("$unpackPath/libsamplerate-$sampleRateVersion/src")
                into("$projectDir/samplerate/src")
            }

            copy {
                from("$unpackPath/libsamplerate-$sampleRateVersion/include")
                into("$projectDir/samplerate/include")
            }
        }

        if (!file("$projectDir/fdk-aac/libAACdec").exists()) {
            val downloadPath = "${layout.buildDirectory.get()}/tmp/fdk-aac-$fdkAacVersion.tar.gz"
            val unpackPath = "${layout.buildDirectory.get()}/tmp"

            downloadFile(
                "https://downloads.sourceforge.net/opencore-amr/fdk-aac-$fdkAacVersion.tar.gz",
                downloadPath,
                fdkAacSha256
            )

            copy {
                from(tarTree(file(downloadPath)))
                into(unpackPath)
            }

            copy {
                from("$unpackPath/fdk-aac-$fdkAacVersion")
                into("$projectDir/fdk-aac")
                exclude("CMakeLists.txt")
            }
        }

        if (!file("$projectDir/vorbis/libogg").exists()) {
            val downloadPath = "${layout.buildDirectory.get()}/tmp/libogg-$oggVersion.tar.xz"
            val unpackPath = "${layout.buildDirectory.get()}/tmp"

            downloadFile(
                "https://downloads.xiph.org/releases/ogg/libogg-$oggVersion.tar.xz",
                downloadPath,
                oggSha256
            )

            extractTarArchive(downloadPath, unpackPath)

            file("$projectDir/vorbis").mkdirs()
            file("$unpackPath/libogg-$oggVersion")
                .renameTo(file("$projectDir/vorbis/libogg"))
        }

        if (!file("$projectDir/vorbis/libvorbis").exists()) {
            val downloadPath = "${layout.buildDirectory.get()}/tmp/libvorbis-$vorbisVersion.tar.xz"
            val unpackPath = "${layout.buildDirectory.get()}/tmp"

            downloadFile(
                "https://downloads.xiph.org/releases/vorbis/libvorbis-$vorbisVersion.tar.xz",
                downloadPath,
                vorbisSha256
            )

            extractTarArchive(downloadPath, unpackPath)

            file("$projectDir/vorbis").mkdirs()
            file("$unpackPath/libvorbis-$vorbisVersion")
                .renameTo(file("$projectDir/vorbis/libvorbis"))
        }

        if (!file("$projectDir/opus/opus").exists()) {
            val downloadPath = "${layout.buildDirectory.get()}/tmp/temp.tar.gz"

            downloadFile(
                "https://downloads.xiph.org/releases/opus/opus-$opusVersion.tar.gz",
                downloadPath,
                opusSha256
            )

            copy {
                from(tarTree(file(downloadPath)))
                into("${layout.buildDirectory.get()}/tmp")
            }

            file("$projectDir/opus").mkdirs()
            file("${layout.buildDirectory.get()}/tmp/opus-$opusVersion")
                .renameTo(file("$projectDir/opus/opus"))
        }

        if (!Os.isFamily(Os.FAMILY_WINDOWS) && !file("$projectDir/mp3/mpg123").exists()) {
            val downloadPath = "${layout.buildDirectory.get()}/tmp/temp.tar.bz2"

            downloadFile(
                "https://www.mpg123.de/download/mpg123-$mpg123Version.tar.bz2",
                downloadPath,
                mpg123Sha256
            )

            copy {
                from(tarTree(file(downloadPath)))
                into("${layout.buildDirectory.get()}/tmp")
            }

            file("$projectDir/mp3").mkdirs()
            file("${layout.buildDirectory.get()}/tmp/mpg123-$mpg123Version")
                .renameTo(file("$projectDir/mp3/mpg123"))
        }
    }
}
