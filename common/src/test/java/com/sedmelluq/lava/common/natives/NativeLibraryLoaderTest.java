package com.sedmelluq.lava.common.natives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLibraryLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsNativeResourceToContentAddressedCachePath() throws Exception {
        byte[] nativeBytes = "native-library-content".getBytes(StandardCharsets.UTF_8);
        String hash = sha256(nativeBytes);

        Path cachedFile = extract(nativeBytes);

        assertEquals(
            tempDir.resolve("linux-x86-64")
                .resolve("libconnector.so")
                .resolve(hash)
                .resolve("libconnector.so"),
            cachedFile
        );
        assertArrayEquals(nativeBytes, Files.readAllBytes(cachedFile));

        Path cachedAgain = extract(nativeBytes);
        assertEquals(cachedFile, cachedAgain);
        assertArrayEquals(nativeBytes, Files.readAllBytes(cachedAgain));
    }

    @Test
    void rejectsExistingCachedFileWhenHashDoesNotMatch() throws Exception {
        byte[] nativeBytes = "native-library-content".getBytes(StandardCharsets.UTF_8);
        Path cachedFile = extract(nativeBytes);

        Files.write(cachedFile, "corrupt-content".getBytes(StandardCharsets.UTF_8));

        IOException error = assertThrows(IOException.class, () -> extract(nativeBytes));
        assertTrue(error.getMessage().contains("Cached native library hash mismatch"));
    }

    @Test
    void concurrentExtractionUsesSameVerifiedCachedFile() throws Exception {
        byte[] nativeBytes = "native-library-content".getBytes(StandardCharsets.UTF_8);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        try {
            List<Future<Path>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> extract(nativeBytes)));
            }

            Path expectedPath = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Path> future : futures) {
                assertEquals(expectedPath, future.get(5, TimeUnit.SECONDS));
            }
            assertArrayEquals(nativeBytes, Files.readAllBytes(expectedPath));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void defaultExtractionPathCanRequestPrivateTempMode() {
        withClearedNativeProperties(() -> {
            Path extractionPath = tempDir.resolve("natives");

            NativeLibraryLoader.setDefaultExtractionPath(
                extractionPath,
                NativeLibraryLoader.ExtractionMode.PRIVATE_TEMP_DIRECTORY
            );

            assertEquals(extractionPath.toAbsolutePath().toString(), System.getProperty("lava.native.extractPath"));
            assertEquals("private-temp-directory", System.getProperty("lava.native.extractMode"));
        });
    }

    @Test
    void libraryExtractionPathCanRequestPrivateTempMode() {
        withClearedNativeProperties(() -> {
            Path extractionPath = tempDir.resolve("natives");

            NativeLibraryLoader.setExtractionPath(
                "connector",
                extractionPath,
                NativeLibraryLoader.ExtractionMode.PRIVATE_TEMP_DIRECTORY
            );

            assertEquals(
                extractionPath.toAbsolutePath().toString(),
                System.getProperty("lava.native.connector.extractPath")
            );
            assertEquals("private-temp-directory", System.getProperty("lava.native.connector.extractMode"));
        });
    }

    @Test
    void defaultExtractionPathDoesNotOverrideExistingModeProperty() {
        withClearedNativeProperties(() -> {
            System.setProperty("lava.native.extractMode", "private-temp-directory");

            NativeLibraryLoader.setDefaultExtractionPath(tempDir.resolve("natives"));

            assertEquals("private-temp-directory", System.getProperty("lava.native.extractMode"));
        });
    }

    private Path extract(byte[] nativeBytes) throws IOException {
        return NativeLibraryLoader.extractLibraryToContentAddressedCache(
            tempDir,
            "linux-x86-64",
            "libconnector.so",
            "connector",
            new ByteArrayInputStream(nativeBytes)
        );
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] result = new char[digest.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();

        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }

        return new String(result);
    }

    private static void withClearedNativeProperties(Runnable action) {
        String[] keys = {
            "lava.native.extractPath",
            "lava.native.extractMode",
            "lava.native.connector.extractPath",
            "lava.native.connector.extractMode"
        };
        String[] oldValues = new String[keys.length];

        for (int i = 0; i < keys.length; i++) {
            oldValues[i] = System.getProperty(keys[i]);
            System.clearProperty(keys[i]);
        }

        try {
            action.run();
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (oldValues[i] == null) {
                    System.clearProperty(keys[i]);
                } else {
                    System.setProperty(keys[i], oldValues[i]);
                }
            }
        }
    }
}
