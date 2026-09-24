package com.kilivana.media;

import com.kilivana.media.config.MediaProperties;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class LocalFileStorage implements MediaStorage {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp");

    private final MediaProperties properties;

    public LocalFileStorage(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredFile store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() <= 0) {
            throw invalid("Uploaded file must not be empty");
        }
        long maxBytes = properties.maxFileSizeBytes();
        if (file.getSize() > maxBytes) {
            throw invalid("Uploaded file exceeds the allowed size");
        }
        String declaredType = normalizeContentType(file.getContentType());
        if (declaredType == null || !ALLOWED_CONTENT_TYPES.contains(declaredType)) {
            throw invalid("Unsupported image type");
        }

        int readLimit = readLimit(maxBytes);
        byte[] bytes;
        try (InputStream input = file.getInputStream()) {
            bytes = input.readNBytes(readLimit + 1);
        } catch (IOException exception) {
            throw new MediaStorageException("Failed to read uploaded file", exception);
        }
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw invalid("Uploaded file exceeds the allowed size");
        }

        ImageType imageType = detectImageType(bytes);
        if (imageType == null || !declaredType.equals(imageType.contentType())) {
            throw invalid("Uploaded file content is not a supported image");
        }

        try {
            Path root = root();
            Files.createDirectories(root);
            String identifier = UUID.randomUUID().toString();
            String storageKey = LocalDate.now(ZoneOffset.UTC) + "/" + identifier + imageType.extension();
            Path target = resolve(root, storageKey);
            Files.createDirectories(target.getParent());
            verifyDirectory(root, target.getParent());
            try (OutputStream output = Files.newOutputStream(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                output.write(bytes);
            } catch (IOException exception) {
                Files.deleteIfExists(target);
                throw exception;
            }
            return new StoredFile(
                    storageKey,
                    identifier + imageType.extension(),
                    imageType.contentType(),
                    bytes.length);
        } catch (IOException exception) {
            throw new MediaStorageException("Failed to store uploaded file", exception);
        }
    }

    @Override
    public InputStream open(String storageKey) {
        try {
            Path root = root();
            Path target = resolve(root, storageKey);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Stored file is not available");
            }
            return new LimitedInputStream(Files.newInputStream(target), properties.maxFileSizeBytes());
        } catch (MediaStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MediaStorageException("Failed to open stored file", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Path target = resolve(root(), storageKey);
            Files.deleteIfExists(target);
        } catch (MediaStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MediaStorageException("Failed to delete stored file", exception);
        }
    }

    private Path root() {
        return Paths.get(properties.effectiveRootDir()).toAbsolutePath().normalize();
    }

    private Path resolve(Path root, String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("\\")) {
            throw invalid("Invalid media storage key");
        }
        for (String segment : storageKey.split("/", -1)) {
            if (segment.isBlank() || segment.equals("..") || segment.equals(".")) {
                throw invalid("Invalid media storage key");
            }
        }
        Path supplied;
        try {
            supplied = Paths.get(storageKey);
        } catch (InvalidPathException exception) {
            throw invalid("Invalid media storage key");
        }
        if (supplied.isAbsolute()) {
            throw invalid("Invalid media storage key");
        }
        Path target = root.resolve(supplied).normalize();
        if (!target.startsWith(root)) {
            throw invalid("Invalid media storage key");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path realRoot = root.toRealPath();
                Path realTarget = target.toRealPath();
                if (!realTarget.startsWith(realRoot)) {
                    throw invalid("Invalid media storage key");
                }
            } catch (IOException exception) {
                throw new MediaStorageException("Failed to resolve stored file", exception);
            }
        }
        return target;
    }

    private void verifyDirectory(Path root, Path directory) throws IOException {
        Path realRoot = root.toRealPath();
        Path realDirectory = directory.toRealPath();
        if (!realDirectory.startsWith(realRoot)) {
            throw invalid("Invalid media storage directory");
        }
    }

    private int readLimit(long maxBytes) {
        if (maxBytes >= Integer.MAX_VALUE) {
            throw invalid("Configured media size is too large");
        }
        return (int) maxBytes;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return normalized.equals("image/jpg") ? "image/jpeg" : normalized;
    }

    private ImageType detectImageType(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return new ImageType("image/jpeg", ".jpg");
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return new ImageType("image/png", ".png");
        }
        if (bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && ((bytes[3] == '8' && bytes[4] == '7')
                || (bytes[3] == '9' && bytes[4] == 'a'))
                && bytes[5] == 'a') {
            return new ImageType("image/gif", ".gif");
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return new ImageType("image/webp", ".webp");
        }
        return null;
    }

    private MediaStorageException invalid(String message) {
        return new MediaStorageException(message);
    }

    private record ImageType(String contentType, String extension) {
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            int readLength = (int) Math.min(length, remaining);
            int read = super.read(buffer, offset, readLength);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long amount) throws IOException {
            long skipped = super.skip(Math.min(amount, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }
}
