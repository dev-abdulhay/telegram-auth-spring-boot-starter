package io.github.dev_abdulhay.telegramauth.payments;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic safety net: records charges Telegram has but the store missed (from
 * {@code getStarTransactions}), redelivers paid orders whose listeners failed, expires and
 * purges stale orders, and expires subscriptions past {@code reconcile.subscription-grace}.
 */
public class StarPaymentReconciler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StarPaymentReconciler.class);
    private static final int PAGE = 100;
    private static final Duration SUBSCRIPTION_PERIOD = Duration.ofSeconds(StarPaymentService.SUBSCRIPTION_PERIOD_SECONDS);

    private final StarPaymentService service;
    private final StarPaymentUpdateHandler handler;
    private final StarOrderStore orders;
    private final StarSubscriptionStore subs;
    /** Transactions already scanned in full pages, per bot id ({@code null} key = main without id). */
    private final Map<Long, Integer> cursors = new HashMap<>();
    private volatile ScheduledExecutorService scheduler;

    public StarPaymentReconciler(StarPaymentService service, StarPaymentUpdateHandler handler, StarOrderStore orders,
                                 StarSubscriptionStore subs) {
        this.service = Objects.requireNonNull(service, "service");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.subs = Objects.requireNonNull(subs, "subs");
    }

    /** Runs one full pass. Safe to call manually; runs never overlap. */
    public synchronized ReconcileReport reconcile() {
        int recovered = 0;
        for (Map.Entry<Long, TelegramBotModule> bot : bots().entrySet()) {
            try {
                recovered += recover(bot.getKey(), bot.getValue());
            } catch (RuntimeException e) {
                log.warn("Stars reconciliation for bot {} failed; other bots continue", bot.getKey(), e);
            }
        }

        int redelivered = 0;
        for (StarOrder o : orders.findPaidUnfulfilled(PAGE)) {
            handler.deliver(o);
            if (orders.findById(o.id()).map(StarOrder::fulfilledAt).isPresent()) {
                redelivered++;
            }
        }

        OffsetDateTime now = now();
        TelegramPaymentsProperties props = service.properties();
        int expiredOrders = orders.expireCreatedBefore(now);
        int purged = orders.deleteExpiredBefore(now.minus(props.getOrderRetention()));

        int expiredSubs = 0;
        OffsetDateTime cutoff = now.minus(props.getReconcile().getSubscriptionGrace());
        for (StarSubscription s : subs.findLiveExpiringBefore(cutoff)) {
            // Re-read so a renewal recorded since the query is not overwritten.
            StarSubscription current = subs.findById(s.id()).orElse(null);
            if (current == null || current.status() == StarSubscriptionStatus.EXPIRED
                    || !current.expiresAt().isBefore(cutoff)) {
                continue;
            }
            StarSubscription expired = current.withStatus(StarSubscriptionStatus.EXPIRED);
            subs.save(expired);
            expiredSubs++;
            try {
                service.events().onSubscriptionExpired(expired);
            } catch (RuntimeException e) {
                log.warn("onSubscriptionExpired listener failed for subscription {}", s.id(), e);
            }
        }
        return new ReconcileReport(recovered, redelivered, expiredOrders, purged, expiredSubs);
    }

    private Map<Long, TelegramBotModule> bots() {
        Map<Long, TelegramBotModule> bots = new LinkedHashMap<>();
        TelegramBotModule main = service.moduleFor(null);
        Long mainId = service.botIdOf(main);
        bots.put(mainId, main);
        service.tenantBots().forEach((id, m) -> {
            if (!id.equals(mainId)) {
                bots.put(id, m);
            }
        });
        return bots;
    }

    /**
     * Pages through the bot's transactions from its cursor. The cursor only moves past a full
     * page in which every transaction was handled, so a charge that failed to record is
     * retried on the next run instead of being skipped.
     */
    private int recover(Long botId, TelegramBotModule module) {
        int recovered = 0;
        int offset = cursors.getOrDefault(botId, 0);
        while (true) {
            JsonNode txs = module.getBot().getStarTransactions(offset, PAGE).path("transactions");
            boolean allHandled = true;
            for (JsonNode tx : txs) {
                try {
                    if (recoverOne(botId, tx)) {
                        recovered++;
                    }
                } catch (RuntimeException e) {
                    allHandled = false;
                    log.warn("Recovering Stars transaction {} on bot {} failed", tx.path("id").asText(), botId, e);
                }
            }
            if (txs.size() < PAGE || !allHandled) {
                return recovered;
            }
            offset += PAGE;
            cursors.put(botId, offset);
        }
    }

    private boolean recoverOne(Long botId, JsonNode tx) {
        JsonNode source = tx.path("source");
        String payload = source.path("invoice_payload").asText(null);
        if (!"user".equals(source.path("type").asText()) || StarOrderIds.parsePayload(payload).isEmpty()) {
            return false;
        }
        OffsetDateTime expiresAt = source.hasNonNull("subscription_period")
                ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(tx.path("date").asLong()), ZoneOffset.UTC)
                .plus(SUBSCRIPTION_PERIOD)
                : null;
        return handler.completePayment(botId, source.path("user").path("id").asLong(), payload,
                tx.path("id").asText(null), tx.path("amount").asInt(), expiresAt);
    }

    @Override
    public void start() {
        if (scheduler != null || !service.properties().getReconcile().isEnabled()) {
            return;
        }
        long interval = service.properties().getReconcile().getInterval().toMillis();
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "star-reconcile");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(this::runSafely, interval, interval, TimeUnit.MILLISECONDS);
        scheduler = s;
    }

    @Override
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s == null) {
            return;
        }
        scheduler = null;
        s.shutdownNow();
        try {
            if (!s.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Stars reconciler did not stop within 10s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private void runSafely() {
        try {
            ReconcileReport r = reconcile();
            log.debug("Stars reconcile: {}", r);
        } catch (RuntimeException e) {
            log.error("Stars reconcile run failed", e);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(service.clock().instant(), ZoneOffset.UTC);
    }
}
