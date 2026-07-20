package online.yudream.plugin.worldmap.infrastructure.render.bluemap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Verifies the administrator-provided BlueMap v5.16 CLI before it is ever executed. */
public final class BlueMapCliLocator {

    public static final String V5_16_SHA256 = "7940d561890373897f8f6be91a52e765461f40e5be4e1c4401004073ee0d2580";

    private final String expectedSha256;

    public BlueMapCliLocator() {
        this(V5_16_SHA256);
    }

    BlueMapCliLocator(String expectedSha256) {
        this.expectedSha256 = normalize(expectedSha256);
    }

    public Path verify(Path cliJar) {
        if (cliJar == null || !Files.isRegularFile(cliJar)) {
            throw new IllegalStateException("BlueMap CLI JAR is not a readable file");
        }
        String actual = sha256(cliJar);
        if (!expectedSha256.equals(actual)) {
            throw new IllegalStateException("BlueMap CLI SHA-256 does not match the pinned v5.16 release");
        }
        return cliJar.toAbsolutePath().normalize();
    }

    private static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to verify BlueMap CLI", exception);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Expected CLI SHA-256 is required");
        }
        return value.replace("sha256:", "").toLowerCase(java.util.Locale.ROOT);
    }
}
