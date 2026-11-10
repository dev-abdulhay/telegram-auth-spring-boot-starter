package io.github.dev_abdulhay.telegramauth.payments;

import java.util.List;
import java.util.function.Consumer;

/**
 * Fans each event out to every delegate in order. A failing delegate does not stop the
 * others; the first failure is rethrown afterwards (later ones attached as suppressed)
 * so the order stays unfulfilled and is redelivered.
 */
public final class CompositeStarPaymentEvents implements StarPaymentEvents {

    private final List<StarPaymentEvents> delegates;

    public CompositeStarPaymentEvents(List<StarPaymentEvents> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void onPaid(StarOrder order) {
        each(d -> d.onPaid(order));
    }

    @Override
    public void onRefunded(StarOrder order, RefundSource source) {
        each(d -> d.onRefunded(order, source));
    }

    @Override
    public void onSubscriptionRenewed(StarSubscription sub, StarOrder renewal) {
        each(d -> d.onSubscriptionRenewed(sub, renewal));
    }

    @Override
    public void onSubscriptionExpired(StarSubscription sub) {
        each(d -> d.onSubscriptionExpired(sub));
    }

    private void each(Consumer<StarPaymentEvents> call) {
        RuntimeException first = null;
        for (StarPaymentEvents d : delegates) {
            try {
                call.accept(d);
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else if (e != first) {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
