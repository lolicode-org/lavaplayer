package com.sedmelluq.lava.common.natives;

import com.sedmelluq.lava.common.natives.architecture.SystemType;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

import static java.nio.file.attribute.PosixFilePermissions.asFileAttribute;
import static java.nio.file.attribute.PosixFilePermissions.fromString;

/**
 * Loads native libraries by name. Libraries are expected to be in classpath /natives/[arch]/[prefix]name[suffix]
 */
public class NativeLibraryLoader {
    private static final Logger log = LoggerFactory.getLogger(NativeLibraryLoader.class);

    public static final String PROPERTY_PREFIX = "lava.native.";
    private static final String DEFAULT_RESOURCE_ROOT = "/natives/";
    private static final ExtractionMode DEFAULT_EXPLICIT_EXTRACTION_MODE = ExtractionMode.CONTENT_ADDRESSED_CACHE;
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final ConcurrentMap<Path, Object> cacheLocks = new ConcurrentHashMap<>();

    private final String libraryName;
    private final Predicate<SystemType> systemFilter;
    private final NativeLibraryProperties properties;
    private final NativeLibraryBinaryProvider binaryProvider;
    private final Object lock;
    private volatile LoadResult previousResult;

    public NativeLibraryLoader(String libraryName, Predicate<SystemType> systemFilter, NativeLibraryProperties properties,
                               NativeLibraryBinaryProvider binaryProvider) {

        this.libraryName = libraryName;
        this.systemFilter = systemFilter;
        this.binaryProvider = binaryProvider;
        this.properties = properties;
        this.lock = new Object();
    }

    public enum ExtractionMode {
        /**
         * Store extracted native libraries by exact resource content hash below the extraction base.
         * This allows safe reuse across JVM processes when a single application classloader owns native loading.
         */
        CONTENT_ADDRESSED_CACHE("content-addressed-cache"),

        /**
         * Always extract native libraries into a private random subdirectory below the extraction base.
         * This preserves the historical behavior and is the safest choice for multi-classloader applications.
         */
        PRIVATE_TEMP_DIRECTORY("private-temp-directory");

        private final String propertyValue;

        ExtractionMode(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        public String propertyValue() {
            return propertyValue;
        }

        private static ExtractionMode fromProperty(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');

            switch (normalized) {
                case "content-addressed-cache":
                case "content-addressable-cache":
                case "content-cache":
                case "cache":
                    return CONTENT_ADDRESSED_CACHE;

                case "private-temp-directory":
                case "private-temp":
                case "random-temp-directory":
                case "random-temp":
                case "temp":
                    return PRIVATE_TEMP_DIRECTORY;

                default:
                    throw new IllegalArgumentException("Unknown native extraction mode: " + value);
            }
        }
    }

    public static NativeLibraryLoader create(Class<?> classLoaderSample, String libraryName) {
        return createFiltered(classLoaderSample, libraryName, null);
    }

    public static NativeLibraryLoader createFiltered(Class<?> classLoaderSample, String libraryName,
                                                     Predicate<SystemType> systemFilter) {

        return new NativeLibraryLoader(
            libraryName,
            systemFilter,
            new SystemNativeLibraryProperties(libraryName, PROPERTY_PREFIX),
            new ResourceNativeLibraryBinaryProvider(classLoaderSample, DEFAULT_RESOURCE_ROOT)
        );
    }

    /**
     * Configure the default base directory where packaged native libraries are extracted before loading.
     * By default, native resources extracted through an explicit extraction path are cached by content hash under
     * this directory. Use {@link #setDefaultExtractionPath(Path, ExtractionMode)} to select another mode.
     * This must be set before the relevant native library is loaded.
     *
     * @param extractionPath Directory under caller control for extracted native files.
     */
    public static void setDefaultExtractionPath(Path extractionPath) {
        setPathProperty("extractPath", extractionPath);
    }

    /**
     * Configure the default base directory and extraction mode for packaged native libraries.
     * This must be set before the relevant native library is loaded.
     *
     * @param extractionPath Directory under caller control for extracted native files.
     * @param extractionMode How bundled native resources should be extracted below the base directory.
     */
    public static void setDefaultExtractionPath(Path extractionPath, ExtractionMode extractionMode) {
        setPathProperty("extractPath", extractionPath);
        setStringProperty("extractMode", extractionMode.propertyValue());
    }

    /**
     * Configure the extraction directory for a specific native library. This overrides the default extraction path.
     * By default, native resources extracted through an explicit extraction path are cached by content hash under
     * this directory. Use {@link #setExtractionPath(String, Path, ExtractionMode)} to select another mode.
     * This must be set before the relevant native library is loaded.
     *
     * @param libraryName Native library name such as {@code connector}.
     * @param extractionPath Directory under caller control for extracted native files.
     */
    public static void setExtractionPath(String libraryName, Path extractionPath) {
        setPathProperty(libraryName + ".extractPath", extractionPath);
    }

    /**
     * Configure the extraction directory and mode for a specific native library.
     * This overrides the default extraction path and mode.
     * This must be set before the relevant native library is loaded.
     *
     * @param libraryName Native library name such as {@code connector}.
     * @param extractionPath Directory under caller control for extracted native files.
     * @param extractionMode How bundled native resources should be extracted below the base directory.
     */
    public static void setExtractionPath(String libraryName, Path extractionPath, ExtractionMode extractionMode) {
        setPathProperty(libraryName + ".extractPath", extractionPath);
        setStringProperty(libraryName + ".extractMode", extractionMode.propertyValue());
    }

    /**
     * Configure the default directory to load native libraries from directly instead of extracting bundled resources.
     * This must be set before the relevant native library is loaded.
     *
     * @param libraryDirectory Directory containing the native binaries for the current platform.
     */
    public static void setDefaultLibraryDirectory(Path libraryDirectory) {
        setPathProperty("dir", libraryDirectory);
    }

    /**
     * Configure a specific native library to load from a directory instead of extracting bundled resources.
     * This must be set before the relevant native library is loaded.
     *
     * @param libraryName Native library name such as {@code connector}.
     * @param libraryDirectory Directory containing the native binary for the current platform.
     */
    public static void setLibraryDirectory(String libraryName, Path libraryDirectory) {
        setPathProperty(libraryName + ".dir", libraryDirectory);
    }

    /**
     * Configure a specific native library to load from an explicit file path.
     * This must be set before the relevant native library is loaded.
     *
     * @param libraryName Native library name such as {@code connector}.
     * @param libraryPath Full path to the library binary.
     */
    public static void setLibraryPath(String libraryName, Path libraryPath) {
        setPathProperty(libraryName + ".path", libraryPath);
    }

    public void load() {
        LoadResult result = previousResult;

        if (result == null) {
            synchronized (lock) {
                result = previousResult;

                if (result == null) {
                    result = loadWithFailureCheck();
                    previousResult = result;
                }
            }
        }

        if (!result.success) {
            throw result.exception;
        }
    }

    private LoadResult loadWithFailureCheck() {
        log.info("Native library {}: loading with filter {}", libraryName, systemFilter);

        try {
            loadInternal();
            return new LoadResult(true, null);
        } catch (Throwable e) {
            log.error("Native library {}: loading failed.", libraryName, e);
            return new LoadResult(false, new RuntimeException(e));
        }
    }

    private void loadInternal() {
        String explicitPath = properties.getLibraryPath();

        if (explicitPath != null) {
            log.debug("Native library {}: explicit path provided {}", libraryName, explicitPath);

            loadFromFile(Paths.get(explicitPath).toAbsolutePath());
        } else {
            SystemType systemType = detectMatchingSystemType();

            if (systemType != null) {
                String explicitDirectory = properties.getLibraryDirectory();

                if (explicitDirectory != null) {
                    log.debug("Native library {}: explicit directory provided {}", libraryName, explicitDirectory);

                    loadFromFile(Paths.get(explicitDirectory, systemType.formatLibraryName(libraryName)).toAbsolutePath());
                } else {
                    loadFromFile(extractLibraryFromResources(systemType));
                }
            }
        }
    }

    private void loadFromFile(Path libraryFilePath) {
        log.debug("Native library {}: attempting to load library at {}", libraryName, libraryFilePath);
        System.load(libraryFilePath.toAbsolutePath().toString());
        log.info("Native library {}: successfully loaded.", libraryName);
    }

    private Path extractLibraryFromResources(SystemType systemType) {
        String explicitExtractionBase = properties.getExtractionPath();
        Path baseDirectory = detectExtractionBaseDirectory(explicitExtractionBase);
        String libraryFileName = systemType.formatLibraryName(libraryName);
        ExtractionMode extractionMode = explicitExtractionBase != null ?
            detectExtractionMode(properties.getExtractionMode()) :
            ExtractionMode.PRIVATE_TEMP_DIRECTORY;

        try (InputStream libraryStream = binaryProvider.getLibraryStream(systemType, libraryName)) {
            if (libraryStream == null) {
                throw new UnsatisfiedLinkError("Required library was not found");
            }

            if (extractionMode == ExtractionMode.CONTENT_ADDRESSED_CACHE) {
                return extractLibraryToContentAddressedCache(
                    baseDirectory,
                    systemType.formatSystemName(),
                    libraryFileName,
                    libraryName,
                    libraryStream
                );
            }

            Path extractedLibraryPath = prepareExtractionDirectory(baseDirectory).resolve(libraryFileName);

            try (OutputStream fileStream = Files.newOutputStream(
                extractedLibraryPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )) {
                IOUtils.copy(libraryStream, fileStream);
            }

            return extractedLibraryPath;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path prepareExtractionDirectory(Path baseDirectory) throws IOException {
        createDirectoriesSecurely(baseDirectory);

        Path extractionDirectory = createPrivateTempDirectory(baseDirectory, libraryName + "-");
        log.debug("Native library {}: created extraction directory {}.", libraryName, extractionDirectory);
        return extractionDirectory;
    }

    private Path detectExtractionBaseDirectory(String explicitExtractionBase) {
        if (explicitExtractionBase != null) {
            log.debug("Native library {}: explicit extraction path provided - {}", libraryName, explicitExtractionBase);
            return Paths.get(explicitExtractionBase).toAbsolutePath();
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir", "/tmp"), "lava-jni-natives")
            .toAbsolutePath();

        log.debug("Native library {}: detected {} as base directory for extraction.", libraryName, path);
        return path;
    }

    private ExtractionMode detectExtractionMode(String explicitExtractionMode) {
        if (explicitExtractionMode == null) {
            log.debug("Native library {}: using default extraction mode {}.", libraryName,
                DEFAULT_EXPLICIT_EXTRACTION_MODE.propertyValue());
            return DEFAULT_EXPLICIT_EXTRACTION_MODE;
        }

        ExtractionMode mode = ExtractionMode.fromProperty(explicitExtractionMode);
        log.debug("Native library {}: explicit extraction mode provided - {}.", libraryName, mode.propertyValue());
        return mode;
    }

    static Path extractLibraryToContentAddressedCache(Path baseDirectory, String systemName, String libraryFileName,
                                                      String libraryName, InputStream libraryStream) throws IOException {

        createDirectoriesSecurely(baseDirectory);

        Path libraryCacheDirectory = baseDirectory
            .resolve(systemName)
            .resolve(libraryFileName);
        createDirectoriesSecurely(libraryCacheDirectory);

        Path tempFile = createPrivateTempFile(libraryCacheDirectory, libraryName + "-", ".tmp");
        String expectedHash;

        try {
            expectedHash = copyStreamToFileWithSha256(libraryStream, tempFile);
        } catch (IOException | RuntimeException e) {
            deleteFileIfExists(tempFile);
            throw e;
        }

        Path hashDirectory = libraryCacheDirectory.resolve(expectedHash);
        createDirectoriesSecurely(hashDirectory);

        Path cachedFile = hashDirectory.resolve(libraryFileName);
        Object jvmLock = cacheLocks.computeIfAbsent(cachedFile.toAbsolutePath().normalize(), key -> new Object());

        synchronized (jvmLock) {
            try (FileChannel lockChannel = FileChannel.open(
                hashDirectory.resolve(".install.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
            ); FileLock ignored = acquireFileLock(lockChannel)) {

                if (Files.exists(cachedFile)) {
                    deleteFileIfExists(tempFile);
                    verifyFileHash(cachedFile, expectedHash);
                    return cachedFile;
                }

                moveTempFileToCache(tempFile, cachedFile);
                try {
                    verifyFileHash(cachedFile, expectedHash);
                } catch (IOException | RuntimeException e) {
                    deleteFileIfExists(cachedFile);
                    throw e;
                }
                return cachedFile;
            } catch (IOException | RuntimeException e) {
                deleteFileIfExists(tempFile);
                throw e;
            }
        }
    }

    private SystemType detectMatchingSystemType() {
        SystemType systemType;

        try {
            systemType = SystemType.detect(properties);
        } catch (IllegalArgumentException e) {
            if (systemFilter != null) {
                log.info("Native library {}: could not detect system type, but system filter is {} - assuming it does " +
                    "not match and skipping library.", libraryName, systemFilter);

                return null;
            } else {
                throw e;
            }
        }

        if (systemFilter != null && !systemFilter.test(systemType)) {
            log.debug("Native library {}: system filter does not match detected system {}, skipping", libraryName,
                systemType.formatSystemName());
            return null;
        }

        return systemType;
    }

    private static Path createPrivateTempDirectory(Path baseDirectory, String prefix) throws IOException {
        boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

        if (isPosix) {
            return Files.createTempDirectory(baseDirectory, prefix, asFileAttribute(fromString("rwx------")));
        }

        return Files.createTempDirectory(baseDirectory, prefix);
    }

    private static Path createPrivateTempFile(Path directory, String prefix, String suffix) throws IOException {
        boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

        if (isPosix) {
            return Files.createTempFile(directory, prefix, suffix, asFileAttribute(fromString("rw-------")));
        }

        return Files.createTempFile(directory, prefix, suffix);
    }

    private static void createDirectoriesSecurely(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return;
        }

        if (Files.exists(path)) {
            throw new IOException("Native extraction base path is not a directory: " + path);
        }

        boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (isPosix) {
            Files.createDirectories(path, asFileAttribute(fromString("rwx------")));
        } else {
            Files.createDirectories(path);
        }
    }

    private static String copyStreamToFileWithSha256(InputStream input, Path output) throws IOException {
        MessageDigest digest = sha256Digest();

        try (DigestInputStream digestInput = new DigestInputStream(input, digest);
             OutputStream fileStream = Files.newOutputStream(
                 output,
                 StandardOpenOption.TRUNCATE_EXISTING,
                 StandardOpenOption.WRITE
             )) {

            IOUtils.copy(digestInput, fileStream);
        }

        return toHex(digest.digest());
    }

    private static void moveTempFileToCache(Path tempFile, Path cachedFile) throws IOException {
        try {
            Files.move(tempFile, cachedFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, cachedFile);
        }
    }

    private static FileLock acquireFileLock(FileChannel channel) throws IOException {
        while (true) {
            try {
                return channel.lock();
            } catch (OverlappingFileLockException e) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for native library cache lock.", interrupted);
                }
            }
        }
    }

    private static void verifyFileHash(Path file, String expectedHash) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Cached native library path is not a file: " + file);
        }

        String actualHash = hashFile(file);
        if (!expectedHash.equals(actualHash)) {
            throw new IOException("Cached native library hash mismatch for " + file +
                ": expected " + expectedHash + ", got " + actualHash);
        }
    }

    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = sha256Digest();

        try (DigestInputStream digestInput = new DigestInputStream(Files.newInputStream(file), digest)) {
            IOUtils.copy(digestInput, OutputStream.nullOutputStream());
        }

        return toHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " digest is not available.", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();

        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }

        return new String(result);
    }

    private static void deleteFileIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Failed to delete native library file {}.", path, e);
        }
    }

    private static void setPathProperty(String propertyName, Path path) {
        System.setProperty(PROPERTY_PREFIX + propertyName, path.toAbsolutePath().toString());
    }

    private static void setStringProperty(String propertyName, String value) {
        System.setProperty(PROPERTY_PREFIX + propertyName, value);
    }

    private static class LoadResult {
        public final boolean success;
        public final RuntimeException exception;

        private LoadResult(boolean success, RuntimeException exception) {
            this.success = success;
            this.exception = exception;
        }
    }
}
