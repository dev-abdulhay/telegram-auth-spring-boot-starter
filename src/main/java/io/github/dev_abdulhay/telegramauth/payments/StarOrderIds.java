package io.github.dev_abdulhay.telegramauth.payments;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Order id generation and invoice-payload parsing. */
public final class StarOrderIds {

    private static final Pattern PAYLOAD = Pattern.compile("so:([0-9a-f]{32})");

    private StarOrderIds() {
    }

    /** A UUID without dashes: 32 lowercase hex characters. */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** The order id inside an invoice payload, or empty for anything not produced by {@link StarOrder#payload()}. */
    public static Optional<String> parsePayload(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        var m = PAYLOAD.matcher(payload);
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
