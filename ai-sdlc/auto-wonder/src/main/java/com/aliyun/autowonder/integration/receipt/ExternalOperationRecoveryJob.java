package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Service
public class ExternalOperationRecoveryJob {

    static final long STALE_AFTER_MILLIS = 30_000L;

    private final IntegrationOutboxDao outboxDao;
    private final List<ExternalOperationReadbackHandler> handlers;
    private final Clock clock;

    @Autowired
    public ExternalOperationRecoveryJob(IntegrationOutboxDao outboxDao,
                                        List<ExternalOperationReadbackHandler> handlers) {
        this(outboxDao, handlers, Clock.systemUTC());
    }

    ExternalOperationRecoveryJob(IntegrationOutboxDao outboxDao,
                                 List<ExternalOperationReadbackHandler> handlers,
                                 Clock clock) {
        this.outboxDao = outboxDao;
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 5000)
    public void recoverScheduled() {
        recover(20);
    }

    public RecoverySummary recover(int limit) {
        Date before = new Date(clock.millis() - STALE_AFTER_MILLIS);
        int found = 0;
        int notFound = 0;
        int unavailable = 0;
        for (IntegrationOutboxDO receipt : outboxDao.listRecoveryCandidates(before, limit)) {
            long expectedLockVersion = lockVersion(receipt);
            if (outboxDao.takeoverForRecovery(receipt.getId(), expectedLockVersion, before) != 1) {
                continue;
            }
            long activeLockVersion = expectedLockVersion + 1;
            receipt.setLockVersion(activeLockVersion);
            ExternalOperationReadbackHandler.ReadbackResult result = readback(receipt);
            if (result.outcome() == ExternalOperationReadbackHandler.Outcome.FOUND) {
                if (outboxDao.markSucceeded(receipt.getId(), activeLockVersion) == 1) {
                    found++;
                }
            } else if (result.outcome() == ExternalOperationReadbackHandler.Outcome.DEFINITELY_NOT_FOUND) {
                if (outboxDao.requeueAfterNotFound(receipt.getId(), activeLockVersion) == 1) {
                    notFound++;
                }
            } else {
                if (outboxDao.markUnknown(receipt.getId(), activeLockVersion,
                        ExternalOperationSanitizer.sanitizeError(result.detail())) == 1) {
                    unavailable++;
                }
            }
        }
        return new RecoverySummary(found, notFound, unavailable);
    }

    private long lockVersion(IntegrationOutboxDO receipt) {
        return receipt.getLockVersion() == null ? 0L : receipt.getLockVersion();
    }

    private ExternalOperationReadbackHandler.ReadbackResult readback(IntegrationOutboxDO receipt) {
        for (ExternalOperationReadbackHandler handler : handlers) {
            if (sameConnector(handler.connector(), receipt.getProvider())
                    && handler.supports(receipt.getEventType())) {
                try {
                    return handler.readback(receipt);
                } catch (RuntimeException failure) {
                    return ExternalOperationReadbackHandler.ReadbackResult.unavailable(failure.getMessage());
                }
            }
        }
        return ExternalOperationReadbackHandler.ReadbackResult.unavailable(
                "readback unavailable for connector/event");
    }

    private boolean sameConnector(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record RecoverySummary(int found, int definitelyNotFound, int unavailable) {
    }
}
