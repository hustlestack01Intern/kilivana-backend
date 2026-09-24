package com.kilivana.media;

public record StoredFile(String storageKey, String fileName, String contentType, long sizeBytes) {
}