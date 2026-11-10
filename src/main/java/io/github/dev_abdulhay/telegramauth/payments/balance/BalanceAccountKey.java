package io.github.dev_abdulhay.telegramauth.payments.balance;

/**
 * Identifies one balance. {@code botId == null} is the global (cross-bot) balance of the user.
 */
public record BalanceAccountKey(Long botId, long userId) {

    /** Stable string form used as the stored account key: {@code "g:<user>"} or {@code "<bot>:<user>"}. */
    public String asString() {
        return (botId == null ? "g" : botId.toString()) + ":" + userId;
    }
}
