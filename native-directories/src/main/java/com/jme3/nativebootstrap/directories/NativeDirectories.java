package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Discovers ordered destinations without creating directories or loading native code. */
public final class NativeDirectories {
    private NativeDirectories() {
    }

    /**
     * Tries preferred bases, JVM temp, user cache and user home in order. Linux also tries XDG_RUNTIME_DIR
     * and /dev/shm. Each base uses the same prefix and namespace layout. The prefix defaults to .jme3 and can
     * be set with natives.namespace. Android and iOS return a virtual candidate.
     *
     * @param namespace
     *            application or library name
     * @param preferredRoots
     *            roots to try before defaults
     * @param operatingSystem
     *            supplier evaluated once per list; the result is shared by its candidates
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String namespace, List<Path> preferredRoots,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(defaultHomePrefix(), namespace, preferredRoots, operatingSystem);
    }

    /**
     * Returns the ordered candidates with an explicit prefix. Discovered bases must already exist. Preferred
     * bases and explicit natives.tempDir, natives.cacheDir and natives.userHome overrides can be created on
     * first use.
     *
     * @param homePrefix
     *            directory name under every base, for example ".jme3"
     * @param namespace
     *            application or library name
     * @param preferredRoots
     *            roots to try before defaults
     * @param operatingSystem
     *            supplier evaluated once per list; the result is shared by its candidates
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String homePrefix, String namespace,
            List<Path> preferredRoots, Supplier<OperatingSystem> operatingSystem) {
        return candidates(homePrefix, namespace, preferredRoots, operatingSystem, true);
    }

    /**
     * Returns the default ordered candidates.
     *
     * @param namespace
     *            application or library name
     * @param operatingSystem
     *            supplier evaluated once per list
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String namespace,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(namespace, Collections.emptyList(), operatingSystem);
    }

    /**
     * Returns the default ordered candidates with a custom prefix for every base.
     *
     * @param homePrefix
     *            directory name under every base, for example ".jme3"
     * @param namespace
     *            application or library name
     * @param operatingSystem
     *            supplier evaluated once per list
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> candidates(String homePrefix, String namespace,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(homePrefix, namespace, Collections.emptyList(), operatingSystem);
    }

    /**
     * Uses only these roots, preserving order and removing duplicates. Android and iOS ignore the roots and
     * return only a virtual candidate. No filesystem access occurs.
     *
     * @param namespace
     *            application or library name
     * @param roots
     *            explicit base directories
     * @param operatingSystem
     *            supplier evaluated once per list
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> fromRoots(String namespace, List<Path> roots,
            Supplier<OperatingSystem> operatingSystem) {
        return candidates(defaultHomePrefix(), namespace, roots, operatingSystem, false);
    }

    private static List<DirectoryCandidate> candidates(String homePrefix, String namespace,
            List<Path> preferredRoots, Supplier<OperatingSystem> operatingSystem, boolean includeDefaults) {
        validateHomePrefix(homePrefix);
        validateNamespace(namespace);
        OperatingSystem os = resolve(operatingSystem);
        if (os == OperatingSystem.ANDROID || os == OperatingSystem.IOS)
            return Collections.singletonList(new DirectoryCandidate(os));

        Roots roots = new Roots();
        for (Path base : preferredRoots) roots.manual(Objects.requireNonNull(base));
        if (includeDefaults) {
            Path temp = overridePath("natives.tempDir");
            Path home = overridePath("natives.userHome");
            Path cache = overridePath("natives.cacheDir");
            Path userHome = home != null ? home : absolutePath(System.getProperty("user.home"));
            if (temp != null) roots.manual(temp);
            else roots.existing(absolutePath(System.getProperty("java.io.tmpdir")));
            if (cache != null) roots.manual(cache);
            else roots.existing(defaultCache(os, userHome));
            if (home != null) roots.manual(home);
            else roots.existing(userHome);
            if (os == OperatingSystem.LINUX) {
                roots.existing(absolutePath(System.getenv("XDG_RUNTIME_DIR")));
                roots.existing(Paths.get("/dev/shm"));
            }
        }

        List<DirectoryCandidate> candidates = new ArrayList<>();
        roots.bases.forEach((base, createBase) -> candidates
                .add(new DirectoryCandidate(base, homePrefix, namespace, os, createBase)));
        return Collections.unmodifiableList(candidates);
    }

    private static final class Roots {
        final Map<Path, Boolean> bases = new LinkedHashMap<>();

        void manual(Path base) {
            bases.put(base.toAbsolutePath().normalize(), true);
        }

        void existing(Path base) {
            if (base != null && base.isAbsolute() && Files.isDirectory(base.normalize()))
                bases.putIfAbsent(base.normalize(), false);
        }
    }

    private static Path defaultCache(OperatingSystem os, Path home) {
        String fallback;
        if (os == OperatingSystem.MACOS) {
            fallback = "Library/Caches";
        } else {
            boolean windows = os == OperatingSystem.WINDOWS;
            Path configured = absolutePath(System.getenv(windows ? "LOCALAPPDATA" : "XDG_CACHE_HOME"));
            if (configured != null) return configured;
            fallback = windows ? "AppData/Local" : ".cache";
        }
        return home == null ? null : home.resolve(fallback);
    }

    static String defaultHomePrefix() {
        String value = System.getProperty("natives.namespace");
        return value == null || value.trim().isEmpty() ? ".jme3" : value;
    }

    private static OperatingSystem resolve(Supplier<OperatingSystem> operatingSystem) {
        return Objects.requireNonNull(Objects.requireNonNull(operatingSystem, "operatingSystem").get(),
                "OS supplier returned null");
    }

    static void validateNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Namespace must be a simple name of 1 to 64 characters");
    }

    static void validateHomePrefix(String homePrefix) {
        if (homePrefix == null || !homePrefix.matches("[A-Za-z0-9._-]{1,64}") || homePrefix.equals(".")
                || homePrefix.equals(".."))
            throw new IllegalArgumentException(
                    "Home prefix must be a simple directory name of 1 to 64 characters");
    }

    private static Path absolutePath(String value) {
        if (value == null || value.isEmpty()) return null;
        Path path = Paths.get(value);
        return path.isAbsolute() ? path : null;
    }

    private static Path overridePath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) return null;
        Path path = Paths.get(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException(property + " must be an absolute path");
        return path;
    }

}
