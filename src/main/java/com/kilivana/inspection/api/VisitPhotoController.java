package com.kilivana.inspection.api;

import com.kilivana.inspection.service.VisitPhotoService;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class VisitPhotoController {

    private final VisitPhotoService visitPhotoService;

    public VisitPhotoController(VisitPhotoService visitPhotoService) {
        this.visitPhotoService = visitPhotoService;
    }

    @PostMapping("/inspections/{inspectionId}/photos")
    public ResponseEntity<VisitPhotoResponse> upload(
            @PathVariable UUID inspectionId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(visitPhotoService.addPhoto(inspectionId, file));
    }

    @GetMapping("/inspections/{inspectionId}/photos")
    public ResponseEntity<List<VisitPhotoResponse>> list(@PathVariable UUID inspectionId) {
        return ResponseEntity.ok(visitPhotoService.listByInspection(inspectionId));
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<VisitPhotoResponse> metadata(@PathVariable UUID photoId) {
        return ResponseEntity.ok(visitPhotoService.metadata(photoId));
    }

    @GetMapping("/photos/{photoId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID photoId) throws IOException {
        VisitPhotoResponse metadata = visitPhotoService.metadata(photoId);
        byte[] bytes = visitPhotoService.content(photoId).readAllBytes();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.contentType()))
                .body(bytes);
    }
}