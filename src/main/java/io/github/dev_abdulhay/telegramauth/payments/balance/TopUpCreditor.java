package io.github.dev_abdulhay.telegramauth.payments.balance;

import io.github.dev_abdulhay.telegramauth.payments.BalanceScope;
import io.github.dev_abdulhay.telegramauth.payments.RefundPolicy;
import io.github.dev_abdulhay.telegramauth.payments.RefundSource;
import io.github.dev_abdulhay.telegramauth.payments.StarOrder;
import io.github.dev_abdulhay.telegramauth.payments.StarPaymentEvents;
import io.github.dev_abdulhay.telegramauth.payments.StarRefundGuard;
import io.github.dev_abdulhay.telegramauth.payments.StarSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

import java.util.Optional;

/**
 * Moves the credits of paid Stars orders into the {@link BalanceLedger} and takes them back on
 * refund. Ledger refKeys are derived from the Telegram charge id ({@code "pay:"} /
 * {@code "refund:"}), so redelivered events apply exactly once.
 *
 * <p>Also the {@link StarRefundGuard}: under {@link RefundPolicy#REQUIRE_BALANCE} a bot refund is
 * refused once the credited amount is no longer on the balance. Runs before other listeners
 * so host code observing {@code onPaid} already sees the credited balance.
 */
public class TopUpCreditor implements StarPaymentEvents, StarRefundGuard, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TopUpCreditor.class);
    static final String SPENT = "Credits from this payment were already spent";

    private final BalanceLedger ledger;
    private final BalanceScope scope;
    private final RefundPolicy policy;

    public TopUpCreditor(BalanceLedger ledger, BalanceScope scope, RefundPolicy policy) {
        this.ledger = ledger;
        this.scope = scope;
        this.policy = policy;
    }

    /** The account credits land on: the bot's own balance under {@code PER_BOT}, else the global one. */
    public BalanceAccountKey keyFor(Long botId, long userId) {
        return new BalanceAccountKey(scope == BalanceScope.PER_BOT ? botId : null, userId);
    }

    @Override
    public void onPaid(StarOrder order) {
        credit(order);
    }

    @Override
    public void onSubscriptionRenewed(StarSubscription sub, StarOrder renewal) {
        credit(renewal);
    }

    @Override
    public void onRefunded(StarOrder order, RefundSource source) {
        if (order.credits() <= 0) {
            return;
        }
        BalanceAccountKey key = keyFor(order.botId(), order.userId());
        String ref = "refund:" + order.chargeId();
        String reason = reason("refund:", order);
        if (source == RefundSource.EXTERNAL || policy == RefundPolicy.ALLOW_NEGATIVE) {
            ledger.debitAllowNegative(key, order.credits(), ref, reason);
            return;
        }
        try {
            ledger.debit(key, order.credits(), ref, reason);
        } catch (InsufficientBalanceException e) {
            // the Stars are already returned, so the debit must be recorded anyway
            log.warn("Credits of refunded Stars order {} were spent after the refund check; balance of {} goes "
                    + "negative", order.id(), key.asString());
            ledger.debitAllowNegative(key, order.credits(), ref, reason);
        }
    }

    @Override
    public Optional<String> check(StarOrder order) {
        if (policy != RefundPolicy.REQUIRE_BALANCE || order.credits() <= 0) {
            return Optional.empty();
        }
        return ledger.balance(keyFor(order.botId(), order.userId())) < order.credits()
                ? Optional.of(SPENT) : Optional.empty();
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    private void credit(StarOrder order) {
        if (order.credits() <= 0) {
            return;
        }
        ledger.credit(keyFor(order.botId(), order.userId()), order.credits(), "pay:" + order.chargeId(),
                reason("", order));
    }

    private static String reason(String prefix, StarOrder order) {
        String r = prefix + order.kind() + ":" + order.productCode();
        return r.length() > LedgerArgs.MAX_REASON ? r.substring(0, LedgerArgs.MAX_REASON) : r;
    }
}
