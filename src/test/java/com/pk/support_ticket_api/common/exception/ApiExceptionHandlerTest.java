package com.pk.support_ticket_api.common.exception;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import com.pk.support_ticket_api.support.TestExceptionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TestExceptionController.class)
@Import({
        ApiExceptionHandler.class,
        FixedClockConfig.class
})
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnStandardNotFoundErrorResponse() throws Exception {
        mockMvc.perform(get("/test/not-found")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message")
                        .value("Test resource not found"))
                .andExpect(jsonPath("$.path").value("/test/not-found"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void shouldGenerateTraceIdAndReturnItInResponseHeader() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}

  @TestConfiguration
class FixedClockConfig {
    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(
            Instant.parse("2026-08-26T10:01:00Z"),
            ZoneOffset.UTC
        );
    }
}
