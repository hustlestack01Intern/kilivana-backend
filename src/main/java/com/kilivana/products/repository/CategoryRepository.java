package com.kilivana.products.repository;

import com.kilivana.products.domain.Category;
import com.kilivana.products.domain.Sector;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByActiveTrueOrderByNameAsc();

    List<Category> findBySectorAndActiveTrueOrderByNameAsc(Sector sector);
}