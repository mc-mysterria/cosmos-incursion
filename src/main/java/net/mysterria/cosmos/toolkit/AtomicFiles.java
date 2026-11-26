package net.mysterria.cosmos.toolkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes save files through a temp file, so a failed write never truncates the saved data.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(File file, String content) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
