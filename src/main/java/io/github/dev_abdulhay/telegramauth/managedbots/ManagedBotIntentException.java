package io.github.dev_abdulhay.telegramauth.managedbots;

/**
 * A managed-bot intent operation the host can reasonably branch on: the intent is
 * gone, the bot belongs to someone else, another assignment won the race.
 *
 * <p>Deliberately <em>not</em> used for misconfiguration — a missing
 * {@link ManagedBotIntentStore} bean is an {@link IllegalStateException}, the same
 * way a missing encryption key is, because no runtime branch can recover from it.
 */
public class ManagedBotIntentException extends RuntimeException {

    public enum Reason {
        INTENT_NOT_FOUND, INTENT_NOT_CLAIMED, INTENT_CLOSED,
        BOT_NOT_FOUND, OWNER_MISMATCH, BOT_ALREADY_ASSIGNED
    }

    private final Reason reason;

    public ManagedBotIntentException(Reason reason, String message) {
        this(reason, message, null);
    }

    public ManagedBotIntentException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
