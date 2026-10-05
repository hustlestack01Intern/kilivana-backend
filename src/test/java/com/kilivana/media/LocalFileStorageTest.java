package com.kilivana.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kilivana.media.config.MediaProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalFileStorageTest {

    @TempDir
    Path tempDirectory;

    @Test
    void storesServerNamedPngAndIgnoresTheClientFilename() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };

        StoredFile stored = storage.store(new MockMultipartFile(
                "file",
                "../../escape.png",
                "image/png",
                png));

        assertThat(stored.storageKey()).doesNotContain("escape").endsWith(".png");
        assertThat(stored.fileName()).endsWith(".png").doesNotContain("escape");
        assertThat(stored.contentType()).isEqualTo("image/png");
        assertThat(Files.exists(tempDirectory.resolve(stored.storageKey()))).isTrue();
    }

    @Test
    @DisplayName("a declared image MIME that is not an image is the caller's fault, not a storage fault")
    void rejectsClientMimeWhenMagicBytesAreNotAnImage() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                "not-an-image".getBytes());

        assertThatThrownBy(() -> storage.store(file))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("supported image");
    }

    @Test
    @DisplayName("an unsupported declared type answers 400, so it must not be a server fault")
    void rejectsUnsupportedContentType() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes());

        assertThatThrownBy(() -> storage.store(file))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("Unsupported image type");
    }

    @Test
    @DisplayName("an empty upload is the caller's fault")
    void rejectsAnEmptyUpload() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));

        assertThatThrownBy(() -> storage.store(new MockMultipartFile("file", "photo.png", "image/png", new byte[0])))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> storage.store(null))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class);
    }

    @Test
    void rejectsMismatchedMimeAndOversizedFiles() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));
        LocalFileStorage smallStorage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 4));
        byte[] png = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };

        assertThatThrownBy(() -> storage.store(new MockMultipartFile(
                "file",
                "photo.jpg",
                "image/jpeg",
                png)))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("supported image");
        assertThatThrownBy(() -> smallStorage.store(new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                png)))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("size");
    }

    @Test
    @DisplayName("a configuration that cannot produce a read limit is a client fault, matching every other invalid() call")
    void rejectsAnUnusableConfiguredSize() {
        LocalFileStorage storage = new LocalFileStorage(
                new MediaProperties(tempDirectory.toString(), (long) Integer.MAX_VALUE + 1));

        assertThatThrownBy(() -> storage.store(new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void rejectsTraversalWhenOpeningAStoredKey() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));

        assertThatThrownBy(() -> storage.open("../outside.png"))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("storage key");
    }

    @Test
    @DisplayName("every rejected storage key in the resolve path stays a client fault")
    void rejectedStorageKeysAreClientFaults() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));

        for (String key : new String[] {
                null,
                "",
                "   ",
                "..",
                ".",
                "2026/09/../escape.png",
                "..\\escape.png",
                "/absolute.png",
                "2026//file.png",
                "2026/./file.png"
        }) {
            assertThatThrownBy(() -> storage.open(key))
                    .as("open(%s)", key)
                    .isInstanceOf(InvalidMediaException.class)
                    .isNotInstanceOf(MediaStorageException.class)
                    .hasMessageContaining("Invalid media storage key");
            assertThatThrownBy(() -> storage.delete(key))
                    .as("delete(%s)", key)
                    .isInstanceOf(InvalidMediaException.class)
                    .isNotInstanceOf(MediaStorageException.class)
                    .hasMessageContaining("Invalid media storage key");
        }
    }

    @Test
    @DisplayName("a missing stored file is reported to the caller, never as a broken volume")
    void openingAnAbsentKeyIsAClientFault() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));

        assertThatThrownBy(() -> storage.open("2026/09/30/never-written.png"))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("not available");
    }

    @Test
    @DisplayName("deleting an absent key is a no-op, and a rejected key is still a client fault")
    void deleteDistinguishesAbsentFromRejected() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));
        Path stored = tempDirectory.resolve("2026/09/30/existing.png");
        Files.createDirectories(stored.getParent());
        Files.write(stored, new byte[]{1, 2, 3});

        storage.delete("2026/09/30/never-written.png");

        assertThat(Files.exists(stored)).isTrue();
        storage.delete("2026/09/30/existing.png");
        assertThat(Files.exists(stored)).isFalse();
        assertThatThrownBy(() -> storage.delete("../outside.png"))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class);
    }

    @Test
    @DisplayName("a symlink pointing outside the root is a client fault, and the read path does not re-label it")
    void aSymlinkEscapeStaysAClientFault() throws Exception {
        Path root = Files.createDirectory(tempDirectory.resolve("root"));
        Path outside = Files.createDirectories(tempDirectory.resolve("outside"));
        Files.write(outside.resolve("secret.png"), new byte[]{1, 2, 3});
        symlinkOrSkip(outside, root.resolve("link"));
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(root.toString(), 32));

        assertThatThrownBy(() -> storage.open("link/secret.png"))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("Invalid media storage key");
    }

    @Test
    @DisplayName("a date directory pre-created as a symlink outside the root is a client fault, not a broken volume")
    void aSymlinkedDateDirectoryIsAClientFault() throws Exception {
        // store() derives "<date>/<uuid>.png" from the clock, so the only way to point the
        // date segment outside the root is to pre-create it as a symlink. verifyDirectory
        // is the last check standing between that and a write outside MEDIA_ROOT, and it
        // reports through the same invalid() helper as every other rejected request.
        Path root = Files.createDirectory(tempDirectory.resolve("root"));
        Path outside = Files.createDirectories(tempDirectory.resolve("elsewhere"));
        symlinkOrSkip(outside, root.resolve(LocalDate.now(ZoneOffset.UTC).toString()));
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(root.toString(), 32));

        assertThatThrownBy(() -> storage.store(new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})))
                .isInstanceOf(InvalidMediaException.class)
                .isNotInstanceOf(MediaStorageException.class)
                .hasMessageContaining("Invalid media storage directory");
        assertThat(countEntries(outside)).isZero();
    }

    /** {@code Files.list} opens a directory handle, so the stream has to be closed. */
    private static long countEntries(Path directory) throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.count();
        }
    }

    /**
     * Symlinks are unavailable on some filesystems and without developer mode on Windows. Skip
     * loudly rather than return quietly: a silently passing assertion proves nothing, and the
     * type check it guards is the whole point of the test.
     */
    private static void symlinkOrSkip(Path target, Path link) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("this filesystem cannot create symlinks: " + exception.getMessage());
        }
    }
}
