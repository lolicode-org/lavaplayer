package com.sedmelluq.lava.common.natives;

import com.sedmelluq.lava.common.natives.architecture.SystemType;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
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
     * This must be set before the relevant native library is loaded.
     *
     * @param extractionPath Directory under caller control for extracted native files.
     */
    public static void setDefaultExtractionPath(Path extractionPath) {
        setPathProperty("extractPath", extractionPath);
    }

    /**
     * Configure the extraction directory for a specific native library. This overrides the default extraction path.
     * This must be set before the relevant native library is loaded.
     *
     * @param libraryName Native library name such as {@code connector}.
     * @param extractionPath Directory under caller control for extracted native files.
     */
    public static void setExtractionPath(String libraryName, Path extractionPath) {
        setPathProperty(libraryName + ".extractPath", extractionPath);
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
        try (InputStream libraryStream = binaryProvider.getLibraryStream(systemType, libraryName)) {
            if (libraryStream == null) {
                throw new UnsatisfiedLinkError("Required library was not found");
            }

            Path extractedLibraryPath = prepareExtractionDirectory().resolve(systemType.formatLibraryName(libraryName));

            try (FileOutputStream fileStream = new FileOutputStream(extractedLibraryPath.toFile())) {
                IOUtils.copy(libraryStream, fileStream);
            }

            return extractedLibraryPath;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Path prepareExtractionDirectory() throws IOException {
        Path baseDirectory = detectExtractionBaseDirectory();
        createDirectoriesSecurely(baseDirectory);

        Path extractionDirectory = createPrivateTempDirectory(baseDirectory, libraryName + "-");
        log.debug("Native library {}: created extraction directory {}.", libraryName, extractionDirectory);
        return extractionDirectory;
    }

    private Path detectExtractionBaseDirectory() {
        String explicitExtractionBase = properties.getExtractionPath();

        if (explicitExtractionBase != null) {
            log.debug("Native library {}: explicit extraction path provided - {}", libraryName, explicitExtractionBase);
            return Paths.get(explicitExtractionBase).toAbsolutePath();
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir", "/tmp"), "lava-jni-natives")
            .toAbsolutePath();

        log.debug("Native library {}: detected {} as base directory for extraction.", libraryName, path);
        return path;
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

    private static void setPathProperty(String propertyName, Path path) {
        System.setProperty(PROPERTY_PREFIX + propertyName, path.toAbsolutePath().toString());
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
