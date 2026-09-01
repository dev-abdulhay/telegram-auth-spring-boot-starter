package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates bots on behalf of users and keeps custody of their tokens.
 *
 * <p>Telegram offers no way to delete a managed bot, so {@link #decommission(long)}
 * revokes the token and forgets the bot locally; the bot itself keeps existing and
 * stays owned by the user, who removes it through BotFather.
 *
 * <p><b>Echoes of our own token changes.</b> {@code replaceManagedBotToken} is
 * itself a token change, so Telegram sends us a {@code managed_bot} update for
 * every rotation and decommission we perform. Left alone that update would make
 * {@link #rotateToken(long)} announce twice and, worse, make {@link #decommission(long)}
 * re-fetch and re-store the very bot it just forgot. Both are suppressed by a
 * short-lived record of the changes we initiated ourselves.
 *
 * <p>That record is <b>JVM-local and not replicated</b>, like the flow's
 * pending-login map and {@code CodeStrikeTracker}'s strikes: an echo delivered to
 * a different instance, or after a restart, is still processed as if the owner had
 * done it. Single-instance deployments — what this library targets today — are
 * unaffected.
 */
public class ManagedBotService {

    private static final Logger log = LoggerFactory.getLogger(ManagedBotService.class);
    /** Telegram's ceiling for {@code added_user_ids}. */
    private static final int MAX_ADDED_USERS = 10;
    /**
     * How long we keep expecting Telegram's echo of a change we made. Generous
     * against a lagging poll loop, short enough that a genuine owner-initiated
     * rotation right after ours is not swallowed.
     */
    private static final Duration ECHO_TTL = Duration.ofMinutes(5);
    /** Hard ceiling on tracked bots, mirroring {@code CodeStrikeTracker}'s bound. */
    private static final int MAX_ECHOES = 10_000;

    /**
     * One self-initiated token change we expect Telegram to echo back.
     *
     * @param oneShot   {@code true} to suppress a single update, {@code false} to
     *                  suppress every update until {@code expiresAt}. Both the
     *                  changes we make — rotation and decommission — are a single
     *                  {@code replaceManagedBotToken} call and so produce exactly
     *                  one echo, so both register one-shot entries; the blanket
     *                  mode is kept only for a change that would echo more than once
     * @param expiresAt when the entry stops suppressing, whether or not it was used
     */
    private record Echo(boolean oneShot, Instant expiresAt) {}

    private final ConcurrentHashMap<Long, Echo> selfInitiated = new ConcurrentHashMap<>();

    private final TelegramBotModule module;
    private final ManagedBotTokenStore store;
    private final TokenEncryptor encryptor;
    private final ManagedBotEvents events;
    private final int tokenFetchRetries;
    private final Duration tokenFetchBackoff;

    public ManagedBotService(TelegramBotModule module, ManagedBotTokenStore store,
                             TokenEncryptor encryptor, ManagedBotEvents events,
                             int tokenFetchRetries, Duration tokenFetchBackoff) {
        this.module = module;
        this.store = store;
        this.encryptor = encryptor;
        this.events = events;
        this.tokenFetchRetries = Math.max(1, tokenFetchRetries);
        this.tokenFetchBackoff = tokenFetchBackoff == null ? Duration.ZERO : tokenFetchBackoff;
    }

    /**
     * Link that asks a user to create a bot managed by this one. The username is
     * only a suggestion: the user can change it in the confirmation dialog, and
     * the Bot API cannot tell us whether it is still free.
     */
    public String createLink(String suggestedUsername, String suggestedName) {
        return ManagedBotLink.build(module.getUsername(), suggestedUsername, suggestedName);
    }

    /** The stored token, decrypted. Reads locally — never calls Telegram. */
    public Optional<String> findToken(long botUserId) {
        return store.findByBotUserId(botUserId)
                .map(b -> encryptor.decrypt(b.encryptedToken()));
    }

    /**
     * Revokes the current token, stores the replacement, and announces the rotation.
     *
     * <p>Telegram echoes this change back as a {@code managed_bot} update; that one
     * echo is swallowed so the host sees exactly one {@code onTokenRotated}. A later,
     * genuinely owner-initiated rotation is announced normally.
     *
     * @throws IllegalArgumentException if the bot is unknown to the store
     */
    public String rotateToken(long botUserId) {
        ManagedBot existing = store.findByBotUserId(botUserId).orElseThrow(
                () -> new IllegalArgumentException("unknown managed bot " + botUserId));
        expectEcho(botUserId, true);
        String fresh = module.getBot().replaceManagedBotToken(botUserId);
        ManagedBot saved = persist(existing.botUserId(), existing.username(), existing.firstName(),
                existing.ownerUserId(), fresh, existing.createdAt());
        events.onTokenRotated(saved);
        return fresh;
    }

    public BotAccess getAccessSettings(long botUserId) {
        JsonNode result = module.getBot().getManagedBotAccessSettings(botUserId);
        List<ManagedBotUser> added = new ArrayList<>();
        for (JsonNode u : result.path("added_users")) {
            added.add(new ManagedBotUser(u.path("id").asLong(),
                    u.path("username").asText(null), u.path("first_name").asText(null)));
        }
        return new BotAccess(result.path("is_access_restricted").asBoolean(false), added);
    }

    /**
     * @param addedUserIds at most 10 users besides the owner; Telegram ignores them
     *                     when {@code restricted} is false. An <b>empty</b> list
     *                     clears the allow-list; {@code null} leaves it untouched.
     */
    public void setAccessSettings(long botUserId, boolean restricted, List<Long> addedUserIds) {
        if (addedUserIds != null && addedUserIds.size() > MAX_ADDED_USERS) {
            throw new IllegalArgumentException(
                    "Telegram accepts at most 10 added users but got " + addedUserIds.size());
        }
        module.getBot().setManagedBotAccessSettings(botUserId, restricted, addedUserIds);
    }

    /**
     * Revokes the token and forgets the bot. Revocation runs first: deleting the
     * row first would destroy the credentials the revocation needs and leave a bot
     * we can no longer reach. A failed revocation still clears local state.
     *
     * <p>Telegram's echo of the revocation is suppressed, otherwise the incoming
     * update would look like an unknown bot and we would fetch its brand-new token
     * and re-create the row we just deleted. The suppression is <b>one-shot</b>:
     * the revocation is one token change and so echoes exactly once, and an owner
     * who re-authorises the same bot moments later is doing something genuine that
     * must still be treated as a creation.
     *
     * <p><b>Lenient about unknown ids</b> — unlike {@link #rotateToken(long)}, which
     * throws. That is deliberate: a bot whose token fetch failed exists on Telegram
     * but has no row here, and {@code decommission} is the only way to revoke it.
     */
    public void decommission(long botUserId) {
        expectEcho(botUserId, true);
        try {
            module.getBot().replaceManagedBotToken(botUserId);
        } catch (RuntimeException e) {
            log.warn("could not revoke the token of managed bot {}; forgetting it anyway", botUserId, e);
        }
        store.deleteByBotUserId(botUserId);
        events.onDecommissioned(botUserId);
    }

    /**
     * Processes one {@code managed_bot} update: fetch the token, store it, and only
     * then publish, so a listener that calls {@link #findToken(long)} always finds it.
     *
     * <p>The update says nothing about what changed, so the store decides: an unknown
     * bot is a creation, a known one a rotation. Re-fetching every time makes a
     * re-delivered update harmless.
     *
     * <p>Updates that merely echo a change we made ourselves are dropped — see the
     * class javadoc.
     */
    public void handleUpdate(JsonNode update) {
        JsonNode managed = update.path("managed_bot");
        JsonNode botNode = managed.path("bot");
        long botUserId = botNode.path("id").asLong();
        long ownerUserId = managed.path("user").path("id").asLong();
        if (botUserId == 0) {
            log.warn("managed_bot update without a bot id, ignoring");
            return;
        }
        if (swallowEcho(botUserId)) {
            log.debug("ignoring the managed_bot update echoing our own token change for bot {}", botUserId);
            return;
        }

        String token;
        try {
            token = fetchTokenWithRetries(botUserId);
        } catch (RuntimeException e) {
            log.warn("giving up on the token of managed bot {} after {} attempts",
                    botUserId, tokenFetchRetries, e);
            events.onTokenFetchFailed(botUserId, ownerUserId, e);
            return;
        }

        storeAndAnnounce(botUserId, ownerUserId,
                botNode.path("username").asText(null),
                botNode.path("first_name").asText(null),
                token);
    }

    /**
     * Fetches this bot's token from Telegram, stores it and publishes the matching
     * event — the same work {@link #handleUpdate(JsonNode)} does, without an update.
     *
     * <p>This is the <b>recovery entry point</b>. When the fetch behind an update
     * exhausts its retries, or the process dies after Telegram's offset advanced but
     * before the handler ran, the bot exists on Telegram with no token stored here
     * and no update will be re-delivered. Call this from
     * {@link ManagedBotEvents#onTokenFetchFailed} (after a delay — the failure was
     * usually rate limiting) or from a reconciliation job.
     *
     * <p>Unlike {@code handleUpdate} it <b>throws</b> rather than publishing
     * {@code onTokenFetchFailed} again, so recovering from inside that callback
     * cannot loop.
     *
     * @param ownerUserId the bot's owner, <b>always written</b> — unlike the username
     *                    and first name, which this entry point cannot supply and
     *                    which are therefore kept from the existing row. Recovering
     *                    a bot the store already knows means passing the owner it
     *                    already holds, or the row is rewritten with the wrong one
     * @return the stored bot
     * @throws io.github.dev_abdulhay.telegramauth.bot.TelegramApiException if the
     *         token could not be fetched after every configured attempt
     */
    public ManagedBot fetchAndStore(long botUserId, long ownerUserId) {
        return storeAndAnnounce(botUserId, ownerUserId, null, null, fetchTokenWithRetries(botUserId));
    }

    /**
     * Saves first and publishes second, so a listener calling {@link #findToken(long)}
     * always finds the token. An unknown bot is a creation, a known one a rotation.
     *
     * @param username  {@code null} keeps whatever the store already holds; a
     *                  {@code managed_bot} update need not carry the field
     * @param firstName as {@code username}
     */
    private ManagedBot storeAndAnnounce(long botUserId, long ownerUserId,
                                        String username, String firstName, String rawToken) {
        Optional<ManagedBot> known = store.findByBotUserId(botUserId);
        ManagedBot saved = persist(botUserId,
                username != null ? username : known.map(ManagedBot::username).orElse(null),
                firstName != null ? firstName : known.map(ManagedBot::firstName).orElse(null),
                ownerUserId, rawToken,
                known.map(ManagedBot::createdAt).orElse(null));
        if (known.isEmpty()) {
            events.onCreated(saved);
        } else {
            events.onTokenRotated(saved);
        }
        return saved;
    }

    /**
     * Records that we are about to change this bot's token, so Telegram's echo of
     * that change is not mistaken for the owner's doing. Registered <em>before</em>
     * the API call: the echo can be in flight before the call even returns.
     *
     * @param oneShot suppress a single update rather than every update until the
     *                entry expires; one call to {@code replaceManagedBotToken}
     *                echoes once, so both callers pass {@code true}
     */
    private void expectEcho(long botUserId, boolean oneShot) {
        purgeEchoes();
        evictOldestEchoIfFull(botUserId);
        selfInitiated.put(botUserId, new Echo(oneShot, Instant.now().plus(ECHO_TTL)));
    }

    /** @return {@code true} when this update only echoes a change we made ourselves */
    private boolean swallowEcho(long botUserId) {
        Echo echo = selfInitiated.get(botUserId);
        if (echo == null) return false;
        if (echo.expiresAt().isBefore(Instant.now())) {
            selfInitiated.remove(botUserId, echo);
            return false;
        }
        if (echo.oneShot()) selfInitiated.remove(botUserId, echo);
        return true;
    }

    /** Drops expired entries, so the map cannot grow without bound. */
    private void purgeEchoes() {
        if (selfInitiated.isEmpty()) return;
        Instant now = Instant.now();
        selfInitiated.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private void evictOldestEchoIfFull(long incomingBotUserId) {
        if (selfInitiated.size() < MAX_ECHOES || selfInitiated.containsKey(incomingBotUserId)) return;
        selfInitiated.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().expiresAt()))
                .ifPresent(oldest -> {
                    log.warn("managed-bot echo map at capacity ({}), evicting the oldest entry", MAX_ECHOES);
                    selfInitiated.remove(oldest.getKey(), oldest.getValue());
                });
    }

    private String fetchTokenWithRetries(long botUserId) {
        RuntimeException last = null;
        Duration wait = tokenFetchBackoff;
        for (int attempt = 1; attempt <= tokenFetchRetries; attempt++) {
            try {
                return module.getBot().getManagedBotToken(botUserId);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < tokenFetchRetries && !wait.isZero() && !wait.isNegative()) {
                    try {
                        Thread.sleep(wait.toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    wait = wait.multipliedBy(2);
                }
            }
        }
        throw last;
    }

    private ManagedBot persist(long botUserId, String username, String firstName,
                               long ownerUserId, String rawToken, OffsetDateTime createdAt) {
        OffsetDateTime now = OffsetDateTime.now();
        ManagedBot bot = new ManagedBot(botUserId, username, firstName, ownerUserId,
                encryptor.encrypt(rawToken), createdAt == null ? now : createdAt, now);
        store.save(bot);
        return bot;
    }
}
