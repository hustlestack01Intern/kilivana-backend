package com.kilivana.products.api;

import com.kilivana.products.service.ProductService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sellers")
public class SellerCatalogController {

    private final ProductService productService;

    public SellerCatalogController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{sellerId}/products")
    public ResponseEntity<List<ProductResponse>> productsOf(@PathVariable UUID sellerId) {
        return ResponseEntity.ok(productService.sellerProducts(sellerId));
    }
}