package com.pk.support_ticket_api.support;

import com.pk.support_ticket_api.common.exception.ResourceNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestExceptionController {

    @GetMapping("/test/not-found")
    public void throwNotFound() {
        throw new ResourceNotFoundException("Test resource not found");
    }
}