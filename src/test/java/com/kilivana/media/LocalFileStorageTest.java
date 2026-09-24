package com.kilivana.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kilivana.media.config.MediaProperties;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void rejectsClientMimeWhenMagicBytesAreNotAnImage() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                "not-an-image".getBytes());

        assertThatThrownBy(() -> storage.store(file))
                .isInstanceOf(MediaStorageException.class)
                .hasMessageContaining("supported image");
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
                .isInstanceOf(MediaStorageException.class)
                .hasMessageContaining("supported image");
        assertThatThrownBy(() -> smallStorage.store(new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                png)))
                .isInstanceOf(MediaStorageException.class)
                .hasMessageContaining("size");
    }

    @Test
    void rejectsTraversalWhenOpeningAStoredKey() {
        LocalFileStorage storage = new LocalFileStorage(new MediaProperties(tempDirectory.toString(), 32));

        assertThatThrownBy(() -> storage.open("../outside.png"))
                .isInstanceOf(MediaStorageException.class)
                .hasMessageContaining("storage key");
    }
}
