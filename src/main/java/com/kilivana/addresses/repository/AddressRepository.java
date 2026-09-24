package com.kilivana.addresses.repository;

import com.kilivana.addresses.domain.Address;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByUserId(UUID userId);

    long countByUserId(UUID userId);
}