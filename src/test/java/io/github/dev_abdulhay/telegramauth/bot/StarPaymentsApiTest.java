package io.github.dev_abdulhay.telegramauth.bot;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StarPaymentsApiTest {

    private WireMockServer server;
    private TelegramBot bot;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
        bot = new TelegramBot(HttpClient.newHttpClient(), "123:ABC", "http://localhost:" + server.port());
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void stub(String method, String body) {
        server.stubFor(post(urlPathEqualTo("/bot123:ABC/" + method))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private String lastBody(String method) {
        LoggedRequest req = server.findAll(postRequestedFor(urlPathEqualTo("/bot123:ABC/" + method))).get(0);
        return req.getBodyAsString();
    }

    @Test
    void sendInvoiceSendsXtrAndSinglePrice() throws Exception {
        stub("sendInvoice", "{\"ok\":true,\"result\":{\"message_id\":7}}");

        assertThat(bot.sendInvoice(55L, "Pack", "Desc", "so:abc", 100).get("message_id").asInt()).isEqualTo(7);

        String body = lastBody("sendInvoice");
        assertThat(body).contains("chat_id=55").contains("currency=XTR").contains("payload=so%3Aabc");
        assertThat(body).containsPattern("provider_token=(&|$)");
        String prices = URLDecoder.decode(body.replaceAll(".*prices=([^&]*).*", "$1"), StandardCharsets.UTF_8);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(prices))
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree("[{\"label\":\"Pack\",\"amount\":100}]"));
    }

    @Test
    void createInvoiceLinkReturnsUrlAndPassesSubscriptionPeriod() {
        stub("createInvoiceLink", "{\"ok\":true,\"result\":\"https://t.me/$abc\"}");

        assertThat(bot.createInvoiceLink("Pro", "Monthly", "so:x", 50, 2592000)).isEqualTo("https://t.me/$abc");
        assertThat(lastBody("createInvoiceLink")).contains("subscription_period=2592000").contains("currency=XTR");
    }

    @Test
    void createInvoiceLinkOmitsPeriodWhenNull() {
        stub("createInvoiceLink", "{\"ok\":true,\"result\":\"https://t.me/$abc\"}");

        bot.createInvoiceLink("Pro", "Monthly", "so:x", 50, null);

        assertThat(lastBody("createInvoiceLink")).doesNotContain("subscription_period");
    }

    @Test
    void answerPreCheckoutRejectionCarriesMessage() {
        stub("answerPreCheckoutQuery", "{\"ok\":true,\"result\":true}");

        bot.answerPreCheckoutQuery("q1", false, "Out of stock");

        assertThat(lastBody("answerPreCheckoutQuery"))
                .contains("pre_checkout_query_id=q1").contains("ok=false").contains("error_message=Out+of+stock");
    }

    @Test
    void answerPreCheckoutOkOmitsMessage() {
        stub("answerPreCheckoutQuery", "{\"ok\":true,\"result\":true}");

        bot.answerPreCheckoutQuery("q1", true, "ignored");

        assertThat(lastBody("answerPreCheckoutQuery")).contains("ok=true").doesNotContain("error_message");
    }

    @Test
    void refundAndEditSubscriptionPostUserAndCharge() {
        stub("refundStarPayment", "{\"ok\":true,\"result\":true}");
        stub("editUserStarSubscription", "{\"ok\":true,\"result\":true}");

        bot.refundStarPayment(9L, "chg1");
        bot.editUserStarSubscription(9L, "chg1", true);

        assertThat(lastBody("refundStarPayment")).contains("user_id=9").contains("telegram_payment_charge_id=chg1");
        assertThat(lastBody("editUserStarSubscription"))
                .contains("user_id=9").contains("telegram_payment_charge_id=chg1").contains("is_canceled=true");
    }

    @Test
    void getStarTransactionsReturnsResultObject() {
        stub("getStarTransactions", "{\"ok\":true,\"result\":{\"transactions\":[]}}");

        assertThat(bot.getStarTransactions(0, 100).has("transactions")).isTrue();
        assertThat(lastBody("getStarTransactions")).contains("offset=0").contains("limit=100");
    }

    @Test
    void getMyStarBalanceReadsAmount() {
        stub("getMyStarBalance", "{\"ok\":true,\"result\":{\"amount\":42}}");

        assertThat(bot.getMyStarBalance()).isEqualTo(42L);
    }

    @Test
    void apiErrorThrowsTelegramApiException() {
        stub("getMyStarBalance", "{\"ok\":false,\"error_code\":400,\"description\":\"BAD\"}");

        assertThatThrownBy(() -> bot.getMyStarBalance()).isInstanceOf(TelegramApiException.class);
    }
}
