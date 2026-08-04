package com.aliyun.autowonder.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

public class SigarNativeLoader {

    static final String LIBRARY = "libsigar-amd64-linux-1.6.4.so";
    private final NativeCopier copier;

    public SigarNativeLoader() {
        this(SigarNativeLoader::copyClasspathNative);
    }

    SigarNativeLoader(NativeCopier copier) {
        this.copier = copier;
    }

    public static boolean isSupported(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return os.equals("linux") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    Path extract() throws IOException {
        Path directory = Files.createTempDirectory("autowonder-sigar-1.6.4-");
        Path target = directory.resolve(LIBRARY);
        setOwnerPermissions(directory);
        try {
            copier.copy(target);
            setOwnerPermissions(target);
            directory.toFile().deleteOnExit();
            target.toFile().deleteOnExit();
            return directory;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            Files.deleteIfExists(directory);
            throw e;
        }
    }

    private static void copyClasspathNative(Path target) throws IOException {
        try (InputStream input = SigarNativeLoader.class.getResourceAsStream("/sigar/" + LIBRARY)) {
            if (input == null) {
                throw new IOException("SIGAR native resource is missing");
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void setOwnerPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // The first community runtime supports Linux; this keeps tests portable.
        }
    }

    @FunctionalInterface
    interface NativeCopier {
        void copy(Path target) throws IOException;
    }
}
