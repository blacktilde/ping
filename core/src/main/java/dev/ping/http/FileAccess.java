package dev.ping.http;

import dev.ping.rpc.RpcException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Where a request body may read files from, and how a stored path becomes a real file.
 *
 * <p>A path is <strong>literal</strong>: it is never interpolated, so nothing a variable or a
 * captured value holds can steer a request at a different file than the one the user chose.
 *
 * <ul>
 *   <li>A relative path is resolved against {@code base}, the collection folder, and must stay
 *       inside it once symlinks are followed. That is how a file kept with the collection stays
 *       portable in Git.
 *   <li>An absolute path is honoured only when {@code allowAbsolute}. The desktop shell checks
 *       absolute paths against the files a dialog chose this session before the core sees
 *       them, and refuses them outright for collection runs; the CLI is the user's own shell.
 * </ul>
 *
 * @param base          the collection folder relative paths resolve against; null when the
 *                      request has no collection, in which case relative paths are refused
 */
public record FileAccess(Path base, boolean allowAbsolute) {

    /** Direct callers (tests, the CLI): absolute paths allowed, no collection for relative ones. */
    public static final FileAccess LOCAL = new FileAccess(null, true);

    /**
     * @param path  the stored path, exactly as written
     * @param label what the file is for, used in error messages ("photo", "the body")
     * @return the real path of a readable regular file
     */
    public Path resolve(String path, String label) {
        if (path == null || path.isBlank()) {
            throw RpcException.invalidParams("Choose a file for " + label);
        }
        Path requested;
        try {
            requested = Path.of(path);
        } catch (InvalidPathException e) {
            throw RpcException.invalidParams("The file path for " + label + " is not valid");
        }

        Path resolved;
        if (requested.isAbsolute()) {
            if (!allowAbsolute) {
                throw RpcException.invalidParams("The file for " + label + " is an absolute path, "
                        + "which is not allowed here. Keep the file inside the collection and choose it again.");
            }
            resolved = requested.normalize();
        } else {
            if (base == null) {
                throw RpcException.invalidParams("The file for " + label
                        + " is a relative path, but this request is not in a collection");
            }
            Path root = base.toAbsolutePath().normalize();
            resolved = root.resolve(requested).normalize();
            if (!resolved.startsWith(root)) {
                throw RpcException.invalidParams("The file for " + label + " is outside the collection");
            }
        }

        Path real;
        try {
            real = resolved.toRealPath();
            if (!requested.isAbsolute()) {
                // A symlink inside the collection must not lead out of it.
                Path realRoot = base.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw RpcException.invalidParams("The file for " + label + " is outside the collection");
                }
            }
        } catch (IOException e) {
            throw RpcException.invalidParams("File not found for " + label + ": " + resolved.getFileName());
        }
        if (!Files.isRegularFile(real)) {
            throw RpcException.invalidParams("The file for " + label + " is not a regular file: "
                    + real.getFileName());
        }
        if (!Files.isReadable(real)) {
            throw RpcException.invalidParams("The file for " + label + " cannot be read: " + real.getFileName());
        }
        return real;
    }
}
