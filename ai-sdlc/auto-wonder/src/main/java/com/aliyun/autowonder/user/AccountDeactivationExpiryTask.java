package com.aliyun.autowonder.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountDeactivationExpiryTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountDeactivationExpiryTask.class);

    private final AccountDeactivationService deactivationService;

    public AccountDeactivationExpiryTask(AccountDeactivationService deactivationService) {
        this.deactivationService = deactivationService;
    }

    @Scheduled(fixedDelayString = "${autowonder.deactivation.expiry.fixed-delay-ms:60000}")
    public void sweep() {
        try {
            int processed = deactivationService.processExpiredDeactivations();
            if (processed > 0) {
                LOGGER.info("Processed {} expired account deactivations", processed);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to process expired account deactivations", e);
        }
    }
}
