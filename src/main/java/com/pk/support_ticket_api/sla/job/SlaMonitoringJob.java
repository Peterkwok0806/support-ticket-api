package com.pk.support_ticket_api.sla.job;

import com.pk.support_ticket_api.sla.service.SlaMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringJob {

    private final SlaMonitoringService slaMonitoringService;

    /**
     * SLA Breach 檢查：每 15 分鐘執行
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void checkSlaBreach() {
        log.info("Starting SLA breach check...");
        try {
            slaMonitoringService.processSlaBreaches();
        } catch (Exception e) {
            log.error("Error during SLA breach check", e);
        }
    }

    /**
     * SLA Warning 檢查：每 30 分鐘執行
     */
    @Scheduled(cron = "0 */30 * * * *")
    public void checkSlaWarning() {
        log.info("Starting SLA warning check...");
        try {
            slaMonitoringService.processSlaWarnings();
        } catch (Exception e) {
            log.error("Error during SLA warning check", e);
        }
    }
}
