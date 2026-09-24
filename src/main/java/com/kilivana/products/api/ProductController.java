package com.kilivana.products.api;

import com.kilivana.products.domain.ProductStatus;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ResponseEntity<List<ProductResponse>> browse(
            @RequestParam(required = false) Sector sector,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) String query) {
        return ResponseEntity.ok(productService.browse(
                sector,
                categoryId,
                sellerId,
                query,
                PageRequest.of(0, 200, Sort.by("createdAt").descending())).getContent());
    }

    @GetMapping("/mine")
    public ResponseEntity<List<ProductResponse>> myProducts() {
        return ResponseEntity.ok(productService.myProducts());
    }

    @GetMapping("/saved")
    public ResponseEntity<List<ProductResponse>> saved() {
        return ResponseEntity.ok(productService.saved());
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getById(@PathVariable UUID productId) {
        return ResponseEntity.ok(productService.getById(productId));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<ProductResponse> update(
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(productService.update(productId, request));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> unlist(@PathVariable UUID productId) {
        productService.unlist(productId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{productId}/status")
    public ResponseEntity<ProductResponse> moderate(
            @PathVariable UUID productId,
            @Valid @RequestBody ModerateProductRequest request) {
        return ResponseEntity.ok(productService.moderate(productId, request.status()));
    }

    @PostMapping("/{productId}/images")
    public ResponseEntity<ProductImageResponse> registerImage(
            @PathVariable UUID productId,
            @Valid @RequestBody RegisterProductImageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(productService.registerImage(productId, request.fileName(), request.contentType(), request.sizeBytes(), request.url()));
    }

    @GetMapping("/{productId}/images")
    public ResponseEntity<List<ProductImageResponse>> images(@PathVariable UUID productId) {
        return ResponseEntity.ok(productService.imagesFor(productId));
    }

    @PostMapping("/{productId}/save")
    public ResponseEntity<Void> save(@PathVariable UUID productId) {
        productService.save(productId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{productId}/save")
    public ResponseEntity<Void> unsave(@PathVariable UUID productId) {
        productService.unsave(productId);
        return ResponseEntity.noContent().build();
    }
}