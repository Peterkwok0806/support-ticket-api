package com.pk.support_ticket_api.users.domain;

import com.pk.support_ticket_api.common.domain.enums.Role;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public class UserSpecification {

    private UserSpecification() {
    }

    public static Specification<User> withFilters(
            Role role,
            UserStatus status,
            String keyword
    ) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }

            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            if (keyword != null && !keyword.isBlank()) {
                String pattern = "%" + keyword.toLowerCase() + "%";
                jakarta.persistence.criteria.Predicate emailMatch = cb.like(
                        cb.lower(root.get("email")), pattern);
                jakarta.persistence.criteria.Predicate nameMatch = cb.like(
                        cb.lower(root.get("displayName")), pattern);
                predicates.add(cb.or(emailMatch, nameMatch));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
