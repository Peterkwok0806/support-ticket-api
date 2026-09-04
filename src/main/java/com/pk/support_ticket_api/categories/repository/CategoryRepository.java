package com.pk.support_ticket_api.categories.repository;

import com.pk.support_ticket_api.categories.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID>, JpaSpecificationExecutor<Category> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, UUID id);
}
