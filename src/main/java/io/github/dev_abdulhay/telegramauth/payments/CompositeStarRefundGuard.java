package io.github.dev_abdulhay.telegramauth.payments;

import java.util.List;
import java.util.Optional;

/** Asks every guard in order; the first veto wins and the remaining guards are not consulted. */
final class CompositeStarRefundGuard implements StarRefundGuard {

    private final List<StarRefundGuard> delegates;

    private CompositeStarRefundGuard(List<StarRefundGuard> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    /** @return {@code null} for no guards (every refund allowed), the guard itself for one */
    static StarRefundGuard of(List<StarRefundGuard> guards) {
        if (guards.isEmpty()) {
            return null;
        }
        return guards.size() == 1 ? guards.get(0) : new CompositeStarRefundGuard(guards);
    }

    @Override
    public Optional<String> check(StarOrder order) {
        for (StarRefundGuard g : delegates) {
            Optional<String> veto = g.check(order);
            if (veto.isPresent()) {
                return veto;
            }
        }
        return Optional.empty();
    }
}
