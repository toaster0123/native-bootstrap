package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Discovers ordered destinations without creating directories or loading native code. */
public final class NativeDirectories {
    private NativeDirectories() {
    }

    /**
     * Returns preferred roots, JVM temp, user cache and ~/.jme3/natives/namespace, in that order. Default
     * paths may be overridden with natives.tempDir, natives.userHome and natives.cacheDir. Linux then tries
     * the namespace under existing XDG_RUNTIME_DIR and /dev/shm directories. The default .jme3 home prefix
     * may be overridden with natives.namespace. Android and iOS return only a virtual candidate that carries
     * the platform without referring to a filesystem directory.
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
        String homePrefix = System.getProperty("natives.namespace");
        if (homePrefix == null || homePrefix.trim().isEmpty()) homePrefix = ".jme3";
        return candidates(homePrefix, namespace, preferredRoots, operatingSystem);
    }

    /**
     * Returns preferred roots, JVM temp, user cache and ~/homePrefix/natives/namespace, in that order. Path
     * system properties apply; the explicit homePrefix takes precedence over natives.namespace. Linux then
     * tries the namespace under existing XDG_RUNTIME_DIR and /dev/shm directories. Android and iOS return
     * only a virtual candidate without a filesystem directory.
     *
     * @param homePrefix
     *            directory name under the user's home, for example ".jme3"
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
        validateHomePrefix(homePrefix);
        validateNamespace(namespace);
        OperatingSystem os = resolve(operatingSystem);
        if (os == OperatingSystem.ANDROID || os == OperatingSystem.IOS)
            return buildCandidates(namespace, Collections.emptyList(), os);
        Map<Path, Boolean> roots = new LinkedHashMap<>();
        for (Path root : preferredRoots)
            roots.putIfAbsent(Objects.requireNonNull(root).toAbsolutePath().normalize(), true);
        Path temp = overridePath("natives.tempDir");
        if (temp == null) temp = absolutePath(System.getProperty("java.io.tmpdir"));
        if (temp != null) roots.putIfAbsent(temp.normalize(), true);
        Path userHome = overridePath("natives.userHome");
        if (userHome == null) userHome = absolutePath(System.getProperty("user.home"));
        Path cache = overridePath("natives.cacheDir");
        if (cache == null && userHome != null) {
            if (os == OperatingSystem.WINDOWS)
                cache = absoluteOr(System.getenv("LOCALAPPDATA"), userHome.resolve("AppData/Local"));
            else if (os == OperatingSystem.MACOS) cache = userHome.resolve("Library/Caches");
            else cache = absoluteOr(System.getenv("XDG_CACHE_HOME"), userHome.resolve(".cache"));
        }
        if (cache != null) roots.putIfAbsent(cache.resolve(namespace).normalize(), true);
        if (userHome != null) {
            roots.putIfAbsent(userHome.resolve(homePrefix).resolve("natives").resolve(namespace).normalize(),
                    true);
        }
        if (os == OperatingSystem.LINUX) {
            addRuntimeRoot(roots, absolutePath(System.getenv("XDG_RUNTIME_DIR")), namespace);
            addRuntimeRoot(roots, Paths.get("/dev/shm"), namespace);
        }
        List<DirectoryCandidate> candidates = new ArrayList<>();
        roots.forEach((root, createParents) -> candidates
                .add(new DirectoryCandidate(root, namespace, os, createParents)));
        return Collections.unmodifiableList(candidates);
    }

    /**
     * Returns the default ordered candidates with ~/.jme3/natives/namespace as the home fallback, followed on
     * Linux by the runtime fallbacks.
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
     * Returns the default ordered candidates with a custom home fallback prefix.
     *
     * @param homePrefix
     *            directory name under the user's home, for example ".jme3"
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
     *            exact extraction roots
     * @param operatingSystem
     *            supplier evaluated once per list
     * @return an immutable ordered list of lazy suppliers
     */
    public static List<DirectoryCandidate> fromRoots(String namespace, List<Path> roots,
            Supplier<OperatingSystem> operatingSystem) {
        validateNamespace(namespace);
        return buildCandidates(namespace, roots, resolve(operatingSystem));
    }

    private static List<DirectoryCandidate> buildCandidates(String namespace, List<Path> roots,
            OperatingSystem os) {
        if (os == OperatingSystem.ANDROID || os == OperatingSystem.IOS)
            return Collections.singletonList(new DirectoryCandidate(os));
        Set<Path> unique = new LinkedHashSet<>();
        for (Path root : roots) unique.add(Objects.requireNonNull(root).toAbsolutePath().normalize());
        List<DirectoryCandidate> result = new ArrayList<>();
        for (Path root : unique) result.add(new DirectoryCandidate(root, namespace, os));
        return Collections.unmodifiableList(result);
    }

    private static OperatingSystem resolve(Supplier<OperatingSystem> operatingSystem) {
        return Objects.requireNonNull(Objects.requireNonNull(operatingSystem, "operatingSystem").get(),
                "OS supplier returned null");
    }

    private static void validateNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Namespace must be a simple name of 1 to 64 characters");
    }

    private static void validateHomePrefix(String homePrefix) {
        if (homePrefix == null || !homePrefix.matches("[A-Za-z0-9._-]{1,64}") || homePrefix.equals(".")
                || homePrefix.equals(".."))
            throw new IllegalArgumentException(
                    "Home prefix must be a simple directory name of 1 to 64 characters");
    }

    private static void addRuntimeRoot(Map<Path, Boolean> roots, Path base, String namespace) {
        if (base != null && base.isAbsolute() && Files.isDirectory(base))
            roots.putIfAbsent(base.resolve(namespace).normalize(), false);
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

    private static Path absoluteOr(String value, Path fallback) {
        return value != null && !value.isEmpty() && Paths.get(value).isAbsolute() ? Paths.get(value)
                                                                                  : fallback;
    }
}
