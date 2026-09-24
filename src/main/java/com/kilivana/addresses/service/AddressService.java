package com.kilivana.addresses.service;

import com.kilivana.addresses.api.AddressResponse;
import com.kilivana.addresses.api.CreateAddressRequest;
import com.kilivana.addresses.domain.Address;
import com.kilivana.addresses.repository.AddressRepository;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public AddressService(
            AddressRepository addressRepository,
            UserRepository userRepository,
            CurrentUser currentUser) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    public List<AddressResponse> myAddresses() {
        AuthenticatedUser actor = currentUser.required();
        return addressRepository.findByUserId(actor.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public AddressResponse create(CreateAddressRequest request) {
        AuthenticatedUser actor = currentUser.required();
        User user = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean makeDefault = request.isDefault() || addressRepository.countByUserId(actor.getId()) == 0;
        if (makeDefault) {
            clearDefault(actor.getId());
        }
        Address address = new Address(
                user,
                request.label().trim(),
                request.addressLine().trim(),
                request.latitude(),
                request.longitude(),
                makeDefault);
        addressRepository.save(address);
        return toResponse(address);
    }

    @Transactional
    public AddressResponse update(UUID addressId, CreateAddressRequest request) {
        AuthenticatedUser actor = currentUser.required();
        Address address = requireOwnAddress(addressId, actor);
        if (request.isDefault()) {
            clearDefault(actor.getId());
        }
        address.update(
                request.label().trim(),
                request.addressLine().trim(),
                request.latitude(),
                request.longitude(),
                request.isDefault());
        return toResponse(address);
    }

    @Transactional
    public void delete(UUID addressId) {
        AuthenticatedUser actor = currentUser.required();
        Address address = requireOwnAddress(addressId, actor);
        addressRepository.delete(address);
    }

    private Address requireOwnAddress(UUID addressId, AuthenticatedUser actor) {
        Address address = addressRepository.findById(addressId)
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));
        if (!address.getUser().getId().equals(actor.getId())) {
            throw new UnauthorizedOperationException("You can only manage your own addresses");
        }
        return address;
    }

    private void clearDefault(UUID userId) {
        addressRepository.findByUserId(userId).stream()
                .filter(Address::isDefault)
                .forEach(address -> address.setDefault(false));
    }

    private AddressResponse toResponse(Address address) {
        return new AddressResponse(
                address.getId(),
                address.getLabel(),
                address.getAddressLine(),
                address.getLatitude(),
                address.getLongitude(),
                address.isDefault());
    }
}