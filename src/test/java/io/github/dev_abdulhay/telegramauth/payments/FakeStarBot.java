package io.github.dev_abdulhay.telegramauth.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dev_abdulhay.telegramauth.bot.TelegramApiException;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBot;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records payment API calls instead of hitting Telegram; transactions are served from a list. */
class FakeStarBot extends TelegramBot {

    static final ObjectMapper MAPPER = new ObjectMapper();

    record Invoice(Long chatId, String title, String description, String payload, int stars, Integer period) {
    }

    record Answer(String queryId, boolean ok, String errorMessage) {
    }

    final List<Invoice> invoices = new CopyOnWriteArrayList<>();
    final List<Answer> answers = new CopyOnWriteArrayList<>();
    final List<String> refunds = new CopyOnWriteArrayList<>();
    final List<String> subscriptionEdits = new CopyOnWriteArrayList<>();
    final List<JsonNode> transactions = new CopyOnWriteArrayList<>();
    final List<Integer> offsets = new CopyOnWriteArrayList<>();
    volatile boolean failTransactions;
    volatile long balance;

    FakeStarBot() {
        super(HttpClient.newHttpClient(), "test-token");
    }

    static TelegramBotModule module(FakeStarBot bot, Long botUserId) {
        return TelegramBotModule.builder("test-token", "test_bot").bot(bot).botUserId(botUserId).build();
    }

    /** Adds an incoming user payment to the transaction history. */
    void addUserTransaction(String chargeId, long userId, String payload, int amount, long date,
                            Integer subscriptionPeriod) {
        var tx = MAPPER.createObjectNode().put("id", chargeId).put("amount", amount).put("date", date);
        var source = tx.putObject("source").put("type", "user").put("invoice_payload", payload);
        source.putObject("user").put("id", userId);
        if (subscriptionPeriod != null) {
            source.put("subscription_period", subscriptionPeriod);
        }
        transactions.add(tx);
    }

    @Override
    public JsonNode sendInvoice(long chatId, String title, String description, String payload, int stars) {
        invoices.add(new Invoice(chatId, title, description, payload, stars, null));
        return MAPPER.createObjectNode().put("message_id", 1);
    }

    @Override
    public String createInvoiceLink(String title, String description, String payload, int stars,
                                    Integer subscriptionPeriod) {
        invoices.add(new Invoice(null, title, description, payload, stars, subscriptionPeriod));
        return "https://t.me/$link";
    }

    @Override
    public void answerPreCheckoutQuery(String queryId, boolean ok, String errorMessage) {
        answers.add(new Answer(queryId, ok, errorMessage));
    }

    @Override
    public void refundStarPayment(long userId, String telegramPaymentChargeId) {
        refunds.add(userId + ":" + telegramPaymentChargeId);
    }

    @Override
    public void editUserStarSubscription(long userId, String telegramPaymentChargeId, boolean canceled) {
        subscriptionEdits.add(userId + ":" + telegramPaymentChargeId + ":" + canceled);
    }

    @Override
    public JsonNode getStarTransactions(int offset, int limit) {
        offsets.add(offset);
        if (failTransactions) {
            throw new TelegramApiException(500, "boom");
        }
        var result = MAPPER.createObjectNode();
        var arr = result.putArray("transactions");
        transactions.stream().skip(offset).limit(limit).forEach(arr::add);
        return result;
    }

    @Override
    public long getMyStarBalance() {
        return balance;
    }
}
