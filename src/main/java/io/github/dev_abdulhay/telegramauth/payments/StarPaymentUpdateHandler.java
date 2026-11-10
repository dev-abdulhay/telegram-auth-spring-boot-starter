package io.github.dev_abdulhay.telegramauth.payments;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the three Stars updates of a bot module: answers {@code pre_checkout_query},
 * records {@code successful_payment} idempotently (first payments and subscription
 * renewals), and applies {@code refunded_payment}.
 *
 * <p>The charge id is the idempotency key throughout: a charge that is already stored is
 * never recorded or delivered twice by this class, whether it arrives as a duplicate update
 * or through the reconciler.
 */
public class StarPaymentUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(StarPaymentUpdateHandler.class);
    private static final Duration SUBSCRIPTION_PERIOD = Duration.ofSeconds(StarPaymentService.SUBSCRIPTION_PERIOD_SECONDS);

    private final StarPaymentService service;
    private final StarOrderStore orders;
    private final StarSubscriptionStore subs;
    private final PreCheckoutValidator validator;
    private final ExecutorService validationExecutor;

    /** @param validator optional host veto; {@code null} allows every order that passes the built-in checks */
    public StarPaymentUpdateHandler(StarPaymentService service, StarOrderStore orders, StarSubscriptionStore subs,
                                    PreCheckoutValidator validator) {
        this.service = Objects.requireNonNull(service, "service");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.subs = Objects.requireNonNull(subs, "subs");
        this.validator = validator;
        AtomicInteger n = new AtomicInteger();
        this.validationExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "star-precheckout-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Stops the pre-checkout validation threads; called by Spring on context close. */
    public void shutdown() {
        validationExecutor.shutdownNow();
    }

    /** Claims the pre-checkout, successful-payment and refunded-payment hooks of {@code module}. */
    public void register(TelegramBotModule module) {
        Long botId = service.botIdOf(module);
        module.onPreCheckoutQuery(update -> handlePreCheckout(module, update));
        module.onSuccessfulPayment(update -> handleSuccessfulPayment(botId, update));
        module.onRefundedPayment(this::handleRefundedPayment);
    }

    /**
     * Records a charge. Idempotent and shared with the reconciler, so a renewal is told apart
     * from a first payment by stored state rather than by update flags: an original order
     * that already holds a different charge and belongs to a subscription means renewal.
     *
     * @param subscriptionExpiresAt Telegram's expiry for subscription charges, or {@code null}
     * @return true if this call recorded the charge
     */
    public boolean completePayment(Long botId, long userId, String payload, String chargeId, int totalAmount,
                                   OffsetDateTime subscriptionExpiresAt) {
        if (chargeId == null || chargeId.isEmpty()) {
            log.error("Stars payment without a charge id (payload {}) ignored", payload);
            return false;
        }
        if (orders.findByChargeId(chargeId).isPresent()) {
            return false;
        }
        StarOrder order = StarOrderIds.parsePayload(payload).flatMap(orders::findById).orElse(null);
        if (order == null) {
            log.error("Stars charge {} by user {} (payload {}) matches no order; needs manual review",
                    chargeId, userId, payload);
            return false;
        }
        if (order.userId() != userId) {
            log.warn("Stars charge {} paid by user {} for order {} of user {}", chargeId, userId, order.id(),
                    order.userId());
        }
        OffsetDateTime now = now();
        if (order.chargeId() != null && !order.chargeId().equals(chargeId)) {
            if (order.subscriptionId() != null || isSubscription(order, subscriptionExpiresAt)) {
                return renew(order, botId, userId, chargeId, totalAmount, subscriptionExpiresAt, now);
            }
            log.error("Stars charge {} arrived for order {} already paid by charge {}; needs manual review",
                    chargeId, order.id(), order.chargeId());
            return false;
        }
        if (!orders.markPaid(order.id(), chargeId, now)) {
            if (orders.findByChargeId(chargeId).isEmpty()) {
                log.error("Stars charge {} could not be recorded on order {} in status {}; needs manual review",
                        chargeId, order.id(), order.status());
            }
            return false;
        }
        StarOrder paid = orders.findById(order.id()).orElseThrow();
        if (isSubscription(paid, subscriptionExpiresAt)) {
            paid = ensureSubscription(paid, subscriptionExpiresAt, now);
        }
        deliver(paid);
        return true;
    }

    /**
     * Fires {@code onPaid} (or {@code onSubscriptionRenewed} for a renewal) and marks the order
     * fulfilled. A listener failure is logged and leaves the order unfulfilled for the
     * reconciler to redeliver.
     */
    public void deliver(StarOrder paidOrder) {
        StarOrder o = orders.findById(paidOrder.id()).orElse(null);
        if (o == null || o.status() != StarOrderStatus.PAID || o.fulfilledAt() != null) {
            return;
        }
        try {
            if (o.kind() == StarOrderKind.RENEWAL) {
                String sid = o.subscriptionId();
                StarSubscription sub = subs.findById(sid)
                        .orElseThrow(() -> new IllegalStateException("subscription " + sid + " is missing"));
                service.events().onSubscriptionRenewed(sub, o);
            } else {
                if (isSubscription(o, null)) {
                    o = ensureSubscription(o, null, now());   // repairs a crash between markPaid and this step
                }
                service.events().onPaid(o);
            }
            orders.markFulfilled(o.id(), now());
        } catch (RuntimeException e) {
            log.warn("Delivering Stars order {} failed; the reconciler will retry", o.id(), e);
        }
    }

    private boolean renew(StarOrder original, Long botId, long userId, String chargeId, int totalAmount,
                          OffsetDateTime subscriptionExpiresAt, OffsetDateTime now) {
        StarOrder linked = ensureSubscription(original, null, now);
        StarSubscription sub = subs.findById(linked.subscriptionId()).orElseThrow();
        OffsetDateTime target = subscriptionExpiresAt != null ? subscriptionExpiresAt
                : later(sub.expiresAt(), now).plus(SUBSCRIPTION_PERIOD);
        // Extend before recording: the store only moves expiry forward, which keeps this
        // idempotent, and a crash in between is healed when the reconciler records the charge.
        // An older charge recovered late must not shorten the subscription or replace the
        // latest charge id.
        subs.extend(sub.id(), chargeId, target);
        long credits = service.catalog().find(original.productCode()).map(StarProduct::credits)
                .orElse(original.credits());
        StarOrder renewal = new StarOrder(StarOrderIds.newId(), botId, userId, original.productCode(),
                StarOrderKind.RENEWAL, totalAmount, credits, StarOrderStatus.PAID, chargeId, sub.id(),
                now, now, now, null, null);
        if (!orders.insertPaidIfChargeAbsent(renewal)) {
            return false;
        }
        deliver(renewal);
        return true;
    }

    /**
     * Makes sure a subscription row exists for a paid subscription order and that the order
     * points at it. The row id is the order id, so a retry after a crash finds the same row.
     */
    private StarOrder ensureSubscription(StarOrder order, OffsetDateTime expiresAt, OffsetDateTime now) {
        String sid = order.subscriptionId() != null ? order.subscriptionId() : order.id();
        if (subs.findById(sid).isEmpty()) {
            OffsetDateTime paidAt = order.paidAt() != null ? order.paidAt() : now;
            subs.save(new StarSubscription(sid, order.botId(), order.userId(), order.productCode(),
                    order.chargeId(), order.chargeId(), StarSubscriptionStatus.ACTIVE,
                    expiresAt != null ? expiresAt : paidAt.plus(SUBSCRIPTION_PERIOD), now));
        }
        if (order.subscriptionId() != null) {
            return order;
        }
        // Conditional write of the link column only: a full-row save here could revert a
        // refund or fulfilment that landed since the order was read.
        orders.linkSubscription(order.id(), sid);
        return orders.findById(order.id()).orElse(order.withSubscriptionId(sid));
    }

    private boolean isSubscription(StarOrder order, OffsetDateTime subscriptionExpiresAt) {
        if (order.subscriptionId() != null) {
            return true;
        }
        if (order.kind() != StarOrderKind.PURCHASE) {
            return false;
        }
        // A product removed from the catalog falls back to Telegram's own subscription signal.
        return service.catalog().find(order.productCode()).map(StarProduct::subscription)
                .orElse(subscriptionExpiresAt != null);
    }

    private void handlePreCheckout(TelegramBotModule module, JsonNode update) {
        JsonNode q = update.path("pre_checkout_query");
        String rejectDefault = service.properties().getPreCheckoutRejectMessage();
        String reject;
        CompletableFuture<Optional<String>> check = null;
        try {
            check = CompletableFuture.supplyAsync(() -> check(q, rejectDefault), validationExecutor);
            reject = check.get(service.properties().getPreCheckoutTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    .orElse(null);
        } catch (TimeoutException e) {
            check.cancel(true);
            log.warn("Pre-checkout {} timed out; rejecting", q.path("id").asText());
            reject = rejectDefault;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reject = rejectDefault;
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Pre-checkout {} failed; rejecting", q.path("id").asText(),
                    e instanceof ExecutionException ? e.getCause() : e);
            reject = rejectDefault;
        }
        try {
            module.getBot().answerPreCheckoutQuery(q.path("id").asText(), reject == null, reject);
        } catch (RuntimeException e) {
            log.error("Answering pre-checkout {} failed", q.path("id").asText(), e);
        }
    }

    /** Built-in checks in spec order, then the host validator. Empty = accept. */
    private Optional<String> check(JsonNode q, String rejectDefault) {
        StarOrder o = StarOrderIds.parsePayload(q.path("invoice_payload").asText(null))
                .flatMap(orders::findById).orElse(null);
        if (o == null
                || o.status() != StarOrderStatus.CREATED
                || !o.expiresAt().isAfter(now())
                || q.path("from").path("id").asLong() != o.userId()
                || !"XTR".equals(q.path("currency").asText())
                || q.path("total_amount").asInt() != o.stars()) {
            return Optional.of(rejectDefault);
        }
        if (validator != null) {
            Optional<String> veto = validator.validate(o, q);
            if (veto.isPresent()) {
                return Optional.of(veto.get().isBlank() ? rejectDefault : veto.get());
            }
        }
        return Optional.empty();
    }

    private void handleSuccessfulPayment(Long botId, JsonNode update) {
        JsonNode message = update.path("message");
        JsonNode sp = message.path("successful_payment");
        String chargeId = sp.path("telegram_payment_charge_id").asText(null);
        OffsetDateTime expiresAt = sp.hasNonNull("subscription_expiration_date")
                ? OffsetDateTime.ofInstant(Instant.ofEpochSecond(sp.path("subscription_expiration_date").asLong()),
                ZoneOffset.UTC)
                : null;
        try {
            completePayment(botId, message.path("from").path("id").asLong(), sp.path("invoice_payload").asText(null),
                    chargeId, sp.path("total_amount").asInt(), expiresAt);
        } catch (RuntimeException e) {
            log.error("Recording Stars charge {} failed; the reconciler will recover it", chargeId, e);
        }
    }

    private void handleRefundedPayment(JsonNode update) {
        String chargeId = update.path("message").path("refunded_payment").path("telegram_payment_charge_id")
                .asText(null);
        Optional<StarOrder> order = chargeId == null ? Optional.empty() : orders.findByChargeId(chargeId);
        if (order.isEmpty()) {
            log.warn("Refund for unknown Stars charge {} ignored", chargeId);
            return;
        }
        if (orders.markRefunded(order.get().id(), now())) {
            try {
                service.events().onRefunded(orders.findById(order.get().id()).orElseThrow(), RefundSource.EXTERNAL);
            } catch (RuntimeException e) {
                log.error("onRefunded listener failed for Stars order {} (external refund)", order.get().id(), e);
            }
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(service.clock().instant(), ZoneOffset.UTC);
    }

    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }
}
