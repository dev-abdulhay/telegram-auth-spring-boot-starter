package io.github.dev_abdulhay.telegramauth.managedbots;

/**
 * How a {@code /start mb_<id>} tap was resolved.
 *
 * <p>{@link #UNKNOWN} is the one that matters for routing: the payload is not an
 * intent this store knows, so the update is <b>not</b> ours and must fall through
 * to whatever else handles {@code /start} — a login token can begin with
 * {@code mb_} by chance.
 */
public enum IntentClaim { CLAIMED, RECLAIMED, COMPLETED, OTHER_OWNER, CLOSED, UNKNOWN }
