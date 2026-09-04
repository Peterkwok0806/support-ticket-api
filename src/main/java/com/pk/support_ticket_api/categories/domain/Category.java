package com.pk.support_ticket_api.categories.domain;

import com.pk.support_ticket_api.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "categories")
public class Category extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "sla_hours_low", nullable = false)
    private Integer slaHoursLow = 72;

    @Column(name = "sla_hours_medium", nullable = false)
    private Integer slaHoursMedium = 48;

    @Column(name = "sla_hours_high", nullable = false)
    private Integer slaHoursHigh = 24;

    @Column(name = "sla_hours_urgent", nullable = false)
    private Integer slaHoursUrgent = 4;

    @Column(name = "is_active", nullable = false)
    private Boolean active = true;
}
