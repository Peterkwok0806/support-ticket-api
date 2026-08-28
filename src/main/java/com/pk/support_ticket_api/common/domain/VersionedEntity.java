package com.pk.support_ticket_api.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;

@Getter
@MappedSuperclass
public abstract class VersionedEntity extends BaseEntity {

    @Version
    @Column(nullable = false)
    private Long version;
}