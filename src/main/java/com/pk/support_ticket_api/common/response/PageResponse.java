package com.pk.support_ticket_api.common.response;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
){

    public static <T, R> PageResponse<R> from(
            Page<T> pageData,
            Function<T, R> mapper
    ) {
        return new PageResponse<>(
                pageData.getContent()
                        .stream()
                        .map(mapper)
                        .toList(),
                pageData.getNumber(),
                pageData.getSize(),
                pageData.getTotalElements(),
                pageData.getTotalPages(),
                pageData.isFirst(),
                pageData.isLast()
        );
    }
}