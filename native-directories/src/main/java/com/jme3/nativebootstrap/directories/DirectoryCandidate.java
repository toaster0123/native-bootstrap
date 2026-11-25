package com.jme3.nativebootstrap.directories;

import com.jme3.nativebootstrap.common.OperatingSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * A lazy extraction destination. Each call creates a distinct directory with best-effort permission
 * hardening.
 */
public final class DirectoryCandidate implements Supplier<Path> {
    private final Path root;
    private final String prefix;
    private final OperatingSystem operatingSystem;
    private final boolean createParents;

    public DirectoryCandidate(Path root, String namespace, OperatingSystem operatingSystem) {
        this(root, namespace, operatingSystem, true);
    }

    DirectoryCandidate(Path root, String namespace, OperatingSystem operatingSystem, boolean createParents) {
        this.root = root.toAbsolutePath().normalize();
        this.prefix = namespace + "-";
        this.operatingSystem = operatingSystem;
        this.createParents = createParents;
    }

    DirectoryCandidate(OperatingSystem operatingSystem) {
        this.root = null;
        this.prefix = null;
        this.operatingSystem = operatingSystem;
        this.createParents = false;
    }

    /**
     * Returns the destination root without creating or accessing it.
     *
     * @return the configured absolute root
     * @throws UnsupportedOperationException
     *             if this is a virtual mobile candidate
     */
    public Path root() {
        if (root == null) throw new UnsupportedOperationException("No root directory available");
        return root;
    }

    /**
     * Returns the operating system selected when this candidate was created.
     *
     * @return the selected operating system
     */
    public OperatingSystem operatingSystem() {
        return operatingSystem;
    }

    /**
     * Creates a fresh extraction directory, or throws UncheckedIOException. The Linux runtime fallbacks may
     * create their namespace directory under an existing base. Other candidates can create missing parents.
     * The natives.noAdditionalChecks system property disables external permission helpers when true; Java
     * permission checks and directory creation remain active. The returned directory is scheduled for
     * best-effort deletion at JVM exit; callers must arrange cleanup for any files they add.
     */
    @Override
    public Path get() {
        try {
            if (root == null) throw new IOException("Mobile candidate has no extraction directory");
            try {
                root.toFile();
            } catch (UnsupportedOperationException e) {
                throw new IOException("Native extraction requires a file-backed path: " + root, e);
            }
            Path directory = PrivateDirectory.create(root, prefix, operatingSystem, createParents);
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare native directory under " + root, e);
        }
    }

    @Override
    public String toString() {
        return root == null ? operatingSystem + " candidate" : root.toString();
    }
}
