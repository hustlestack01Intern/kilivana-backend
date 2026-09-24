package com.kilivana.products.service;

import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.products.api.CategoryResponse;
import com.kilivana.products.api.CreateCategoryRequest;
import com.kilivana.products.domain.Category;
import com.kilivana.products.domain.Sector;
import com.kilivana.products.repository.CategoryRepository;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.UserRole;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CurrentUser currentUser;

    public CategoryService(CategoryRepository categoryRepository, CurrentUser currentUser) {
        this.categoryRepository = categoryRepository;
        this.currentUser = currentUser;
    }

    public List<CategoryResponse> list(Sector sector) {
        List<Category> categories = sector == null
                ? categoryRepository.findByActiveTrueOrderByNameAsc()
                : categoryRepository.findBySectorAndActiveTrueOrderByNameAsc(sector);
        return categories.stream().map(this::toResponse).toList();
    }

    public CategoryResponse create(CreateCategoryRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.ADMIN) {
            throw new UnauthorizedOperationException("Only administrators can create categories");
        }
        Category category = new Category(request.name().trim(), request.sector(), true);
        categoryRepository.save(category);
        return toResponse(category);
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName(), category.getSector(), category.isActive());
    }
}