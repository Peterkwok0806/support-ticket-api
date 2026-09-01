package com.pk.support_ticket_api.users.repository;

import com.pk.support_ticket_api.users.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    Page<User> findAll(org.springframework.data.jpa.domain.Specification<User> spec, Pageable pageable);
}
