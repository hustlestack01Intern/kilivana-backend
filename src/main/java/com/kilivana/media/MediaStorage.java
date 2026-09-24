package com.kilivana.media;

import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;

public interface MediaStorage {

    StoredFile store(MultipartFile file);

    InputStream open(String storageKey);

    void delete(String storageKey);
}