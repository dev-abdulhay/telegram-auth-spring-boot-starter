package io.github.dev_abdulhay.telegramauth.payments;

import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public Stars API: sends invoices and invoice links, refunds, and cancels or resumes
 * subscriptions, on the main bot or on a registered tenant bot.
 *
 * <p>Every order is saved before the Bot API call, so a payment for an invoice whose send
 * looked failed can still be matched by the reconciler.
 */
public class StarPaymentService {

    /** 30 days, the only period Telegram accepts for Star subscriptions. */
    public static final int SUBSCRIPTION_PERIOD_SECONDS = 2592000;
    static final String TOP_UP_CODE = "top-up";

    private final TelegramBotModule main;
    private final StarCatalog catalog;
    private final StarOrderStore orders;
    private final StarSubscriptionStore subs;
    private final StarRefundGuard guard;
    private final StarPaymentEvents events;
    private final TelegramPaymentsProperties props;
    private final Clock clock;
    private final Map<Long, TelegramBotModule> tenants = new ConcurrentHashMap<>();

    /** @param guard optional refund veto; {@code null} allows every refund of a paid order */
    public StarPaymentService(TelegramBotModule main, StarCatalog catalog, StarOrderStore orders,
                              StarSubscriptionStore subs, StarRefundGuard guard, StarPaymentEvents events,
                              TelegramPaymentsProperties props, Clock clock) {
        this.main = Objects.requireNonNull(main, "main");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.subs = Objects.requireNonNull(subs, "subs");
        this.guard = guard;
        this.events = Objects.requireNonNull(events, "events");
        this.props = Objects.requireNonNull(props, "props");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Registers a tenant bot under its {@code botUserId}; a restarted tenant replaces the old module. */
    public void registerBot(TelegramBotModule tenant) {
        Long id = tenant.getBotUserId();
        if (id == null) {
            throw new IllegalArgumentException("tenant bot module needs a botUserId");
        }
        tenants.put(id, tenant);
    }

    public Map<Long, TelegramBotModule> tenantBots() {
        return Collections.unmodifiableMap(tenants);
    }

    /** {@code null} or the main bot's id selects the main bot; an unregistered id is refused. */
    public TelegramBotModule moduleFor(Long botId) {
        if (botId == null || botId.equals(main.getBotUserId())) {
            return main;
        }
        TelegramBotModule m = tenants.get(botId);
        if (m == null) {
            throw new StarPaymentException("unknown bot: " + botId);
        }
        return m;
    }

    /** The id stored on orders created through {@code m}; may be {@code null} for the main bot. */
    public Long botIdOf(TelegramBotModule m) {
        return m == main ? main.getBotUserId() : m.getBotUserId();
    }

    public StarOrder sendInvoice(long chatId, long userId, String productCode) {
        return sendInvoice(null, chatId, userId, productCode);
    }

    /** Sends an in-chat invoice for a one-off product. */
    public StarOrder sendInvoice(Long botId, long chatId, long userId, String productCode) {
        StarProduct p = product(productCode);
        if (p.subscription()) {
            throw new StarPaymentException("subscriptions need createInvoiceLink: " + productCode);
        }
        TelegramBotModule m = moduleFor(botId);
        StarOrder o = newOrder(m, userId, p.code(), StarOrderKind.PURCHASE, p.stars(), p.credits());
        m.getBot().sendInvoice(chatId, p.title(), p.description(), o.payload(), p.stars());
        return o;
    }

    public StarOrder sendTopUpInvoice(long chatId, long userId, int stars) {
        return sendTopUpInvoice(null, chatId, userId, stars);
    }

    /** Sends an invoice for a custom amount, credited at {@code top-up.credits-per-star}. */
    public StarOrder sendTopUpInvoice(Long botId, long chatId, long userId, int stars) {
        TelegramPaymentsProperties.TopUp t = props.getTopUp();
        if (!t.isEnabled()) {
            throw new StarPaymentException("top-up is disabled");
        }
        if (stars < t.getMinStars() || stars > t.getMaxStars()) {
            throw new StarPaymentException("top-up must be " + t.getMinStars() + ".." + t.getMaxStars()
                    + " stars, was " + stars);
        }
        TelegramBotModule m = moduleFor(botId);
        StarOrder o = newOrder(m, userId, TOP_UP_CODE, StarOrderKind.TOP_UP, stars, stars * t.getCreditsPerStar());
        m.getBot().sendInvoice(chatId, t.getTitle(), t.getDescription(), o.payload(), stars);
        return o;
    }

    public InvoiceLink createInvoiceLink(long userId, String productCode) {
        return createInvoiceLink(null, userId, productCode);
    }

    /** Creates an invoice link; subscription products get the 30-day subscription period. */
    public InvoiceLink createInvoiceLink(Long botId, long userId, String productCode) {
        StarProduct p = product(productCode);
        TelegramBotModule m = moduleFor(botId);
        StarOrder o = newOrder(m, userId, p.code(), StarOrderKind.PURCHASE, p.stars(), p.credits());
        String url = m.getBot().createInvoiceLink(p.title(), p.description(), o.payload(), p.stars(),
                p.subscription() ? SUBSCRIPTION_PERIOD_SECONDS : null);
        return new InvoiceLink(url, o);
    }

    /**
     * Refunds a paid order through the bot that was paid. The event fires only if this call
     * recorded the refund; a concurrent {@code refunded_payment} update fires its own.
     */
    public void refund(String orderId) {
        StarOrder o = orders.findById(orderId)
                .orElseThrow(() -> new StarPaymentException("unknown order: " + orderId));
        if (o.status() != StarOrderStatus.PAID) {
            throw new StarPaymentException("order " + orderId + " is " + o.status() + ", only PAID can be refunded");
        }
        if (guard != null) {
            guard.check(o).ifPresent(reason -> {
                throw new StarPaymentException("refund refused: " + reason);
            });
        }
        moduleFor(o.botId()).getBot().refundStarPayment(o.userId(), o.chargeId());
        if (orders.markRefunded(o.id(), now())) {
            events.onRefunded(orders.findById(o.id()).orElseThrow(), RefundSource.BOT);
        }
    }

    /** Stops auto-renewal; the subscription stays usable until {@code expiresAt}. */
    public void cancelSubscription(String subscriptionId) {
        editSubscription(subscriptionId, true);
    }

    public void resumeSubscription(String subscriptionId) {
        editSubscription(subscriptionId, false);
    }

    /** Stars balance of the given bot ({@code null} = main). */
    public long botStarBalance(Long botId) {
        return moduleFor(botId).getBot().getMyStarBalance();
    }

    public StarCatalog catalog() {
        return catalog;
    }

    public StarPaymentEvents events() {
        return events;
    }

    public Clock clock() {
        return clock;
    }

    public TelegramPaymentsProperties properties() {
        return props;
    }

    private void editSubscription(String subscriptionId, boolean canceled) {
        StarSubscription s = subs.findById(subscriptionId)
                .orElseThrow(() -> new StarPaymentException("unknown subscription: " + subscriptionId));
        if (s.status() == StarSubscriptionStatus.EXPIRED) {
            throw new StarPaymentException("subscription " + subscriptionId + " has expired");
        }
        moduleFor(s.botId()).getBot().editUserStarSubscription(s.userId(), s.lastChargeId(), canceled);
        subs.save(s.withStatus(canceled ? StarSubscriptionStatus.CANCELED : StarSubscriptionStatus.ACTIVE));
    }

    private StarProduct product(String code) {
        return catalog.find(code).orElseThrow(() -> new StarPaymentException("unknown product: " + code));
    }

    private StarOrder newOrder(TelegramBotModule m, long userId, String code, StarOrderKind kind, int stars,
                               long credits) {
        OffsetDateTime now = now();
        StarOrder o = new StarOrder(StarOrderIds.newId(), botIdOf(m), userId, code, kind, stars, credits,
                StarOrderStatus.CREATED, null, null, now, now.plus(props.getOrderTtl()), null, null, null);
        orders.save(o);
        return o;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
