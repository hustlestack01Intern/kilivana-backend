package com.kilivana.inspection.service;

import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.inspection.api.VisitPhotoResponse;
import com.kilivana.inspection.domain.Inspection;
import com.kilivana.inspection.domain.VisitPhoto;
import com.kilivana.inspection.repository.InspectionRepository;
import com.kilivana.inspection.repository.VisitPhotoRepository;
import com.kilivana.media.MediaStorage;
import com.kilivana.media.StoredFile;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.UserRole;
import jakarta.transaction.Transactional;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VisitPhotoService {

    private final VisitPhotoRepository visitPhotoRepository;
    private final InspectionRepository inspectionRepository;
    private final MediaStorage mediaStorage;
    private final CurrentUser currentUser;

    public VisitPhotoService(
            VisitPhotoRepository visitPhotoRepository,
            InspectionRepository inspectionRepository,
            MediaStorage mediaStorage,
            CurrentUser currentUser) {
        this.visitPhotoRepository = visitPhotoRepository;
        this.inspectionRepository = inspectionRepository;
        this.mediaStorage = mediaStorage;
        this.currentUser = currentUser;
    }

    @Transactional
    public VisitPhotoResponse addPhoto(UUID inspectionId, MultipartFile file) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can attach visit photos");
        }
        Inspection inspection = inspectionRepository.findWithActorsById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        if (!inspection.getInspector().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only attach photos to your own inspections");
        }
        StoredFile stored = mediaStorage.store(file);
        VisitPhoto photo = new VisitPhoto(
                inspection,
                stored.storageKey(),
                stored.fileName(),
                stored.contentType(),
                stored.sizeBytes());
        visitPhotoRepository.save(photo);
        return toResponse(photo);
    }

    public List<VisitPhotoResponse> listByInspection(UUID inspectionId) {
        AuthenticatedUser actor = currentUser.required();
        Inspection inspection = inspectionRepository.findWithActorsById(inspectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inspection not found"));
        requireInspectionAccess(actor, inspection);
        return visitPhotoRepository.findByInspectionId(inspectionId).stream().map(this::toResponse).toList();
    }

    public VisitPhotoResponse metadata(UUID photoId) {
        return toResponse(requirePhoto(photoId));
    }

    public InputStream content(UUID photoId) {
        VisitPhoto photo = requirePhoto(photoId);
        return mediaStorage.open(photo.getStorageKey());
    }

    private VisitPhoto requirePhoto(UUID photoId) {
        AuthenticatedUser actor = currentUser.required();
        VisitPhoto photo = visitPhotoRepository.findWithDetailsById(photoId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found"));
        requireInspectionAccess(actor, photo.getInspection());
        return photo;
    }

    private void requireInspectionAccess(AuthenticatedUser actor, Inspection inspection) {
        if (actor.getRole() != UserRole.INSPECTOR
                && !inspection.getProduct().getOwner().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You cannot access photos for this inspection");
        }
    }

    private VisitPhotoResponse toResponse(VisitPhoto photo) {
        return new VisitPhotoResponse(
                photo.getId(),
                photo.getInspection().getId(),
                photo.getFileName(),
                photo.getContentType(),
                photo.getSizeBytes(),
                photo.getUploadedAt(),
                "/api/v1/photos/" + photo.getId() + "/content");
    }
}