package io.github.dev_abdulhay.telegramauth.managedbots;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Creates bots on behalf of users and keeps custody of their tokens.
 *
 * <p>Telegram offers no way to delete a managed bot, so {@link #decommission(long)}
 * revokes the token and forgets the bot locally; the bot itself keeps existing and
 * stays owned by the user, who removes it through BotFather.
 */
public class ManagedBotService {

    private static final Logger log = LoggerFactory.getLogger(ManagedBotService.class);
    /** Telegram's ceiling for {@code added_user_ids}. */
    private static final int MAX_ADDED_USERS = 10;

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

    /** Revokes the current token, stores the replacement, and announces the rotation. */
    public String rotateToken(long botUserId) {
        ManagedBot existing = store.findByBotUserId(botUserId).orElseThrow(
                () -> new IllegalArgumentException("unknown managed bot " + botUserId));
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
     *                     when {@code restricted} is false
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
     */
    public void decommission(long botUserId) {
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

        Optional<ManagedBot> known = store.findByBotUserId(botUserId);
        String token;
        try {
            token = fetchTokenWithRetries(botUserId);
        } catch (RuntimeException e) {
            log.warn("giving up on the token of managed bot {} after {} attempts",
                    botUserId, tokenFetchRetries, e);
            events.onTokenFetchFailed(botUserId, ownerUserId, e);
            return;
        }

        ManagedBot saved = persist(botUserId,
                botNode.path("username").asText(null),
                botNode.path("first_name").asText(null),
                ownerUserId, token,
                known.map(ManagedBot::createdAt).orElse(null));
        if (known.isEmpty()) {
            events.onCreated(saved);
        } else {
            events.onTokenRotated(saved);
        }
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
