package io.github.dev_abdulhay.telegramauth.payments;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertiesStarCatalogTest {

    private static TelegramPaymentsProperties.Product product(String title, int stars, boolean sub) {
        TelegramPaymentsProperties.Product p = new TelegramPaymentsProperties.Product();
        p.setTitle(title);
        p.setDescription("desc");
        p.setStars(stars);
        p.setCredits(5);
        p.setSubscription(sub);
        return p;
    }

    @Test
    void productsKeepYamlOrderAndCode() {
        TelegramPaymentsProperties props = new TelegramPaymentsProperties();
        props.getProducts().put("zeta", product("Z", 1, false));
        props.getProducts().put("alpha", product("A", 2, true));

        PropertiesStarCatalog catalog = new PropertiesStarCatalog(props);

        assertThat(catalog.all()).extracting(StarProduct::code).containsExactly("zeta", "alpha");
        StarProduct alpha = catalog.find("alpha").orElseThrow();
        assertThat(alpha.title()).isEqualTo("A");
        assertThat(alpha.stars()).isEqualTo(2);
        assertThat(alpha.credits()).isEqualTo(5);
        assertThat(alpha.subscription()).isTrue();
    }

    @Test
    void unknownCodeIsEmpty() {
        assertThat(new PropertiesStarCatalog(new TelegramPaymentsProperties()).find("nope")).isEmpty();
    }

    @Test
    void validateRejectsLongTitle() {
        TelegramPaymentsProperties props = new TelegramPaymentsProperties();
        props.getProducts().put("pack", product("x".repeat(33), 10, false));
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("products.pack.title");
    }

    @Test
    void validateRejectsSubscriptionAbove10000() {
        TelegramPaymentsProperties props = new TelegramPaymentsProperties();
        props.getProducts().put("vip", product("VIP", 10001, true));
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("products.vip.stars");
    }

    @Test
    void validateRejectsTimeoutOf10s() {
        TelegramPaymentsProperties props = new TelegramPaymentsProperties();
        props.setPreCheckoutTimeout(Duration.ofSeconds(10));
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pre-checkout-timeout");
    }

    @Test
    void validateRejectsMinAboveMax() {
        TelegramPaymentsProperties props = new TelegramPaymentsProperties();
        props.getTopUp().setMinStars(100);
        props.getTopUp().setMaxStars(50);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-up.min-stars");
    }

    @Test
    void validateRejectsLongTopUpTitleWhenEnabled() {
        TelegramPaymentsProperties props = new TelegramPaymentsProperties();
        props.getTopUp().setTitle("x".repeat(33));
        props.validate();   // disabled: not checked
        props.getTopUp().setEnabled(true);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-up.title");
    }

    @Test
    void defaultsAreAsSpecified() {
        TelegramPaymentsProperties p = new TelegramPaymentsProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getOrderTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.getPreCheckoutTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.getPreCheckoutRejectMessage()).isEqualTo("This order is no longer available, please try again");
        assertThat(p.getOrderRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(p.getReconcile().isEnabled()).isTrue();
        assertThat(p.getReconcile().getInterval()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.getReconcile().getSubscriptionGrace()).isEqualTo(Duration.ofHours(1));
        assertThat(p.getSupport().isEnabled()).isTrue();
        assertThat(p.getSupport().getPaysupportText()).startsWith("For payment issues");
        assertThat(p.getSupport().getTermsText()).isEqualTo("By paying you agree to the terms of this service.");
        assertThat(p.getTopUp().isEnabled()).isFalse();
        assertThat(p.getTopUp().getMinStars()).isEqualTo(10);
        assertThat(p.getTopUp().getMaxStars()).isEqualTo(10000);
        assertThat(p.getTopUp().getCreditsPerStar()).isEqualTo(1L);
        assertThat(p.getTopUp().getTitle()).isEqualTo("Balance top-up");
        assertThat(p.getTopUp().getDescription()).isEqualTo("Top up your balance");
        assertThat(p.getProducts()).isEmpty();
        assertThat(p.getBalance().isEnabled()).isFalse();
        assertThat(p.getBalance().getScope()).isEqualTo(BalanceScope.GLOBAL);
        assertThat(p.getBalance().getRefundPolicy()).isEqualTo(RefundPolicy.REQUIRE_BALANCE);
        p.validate();
    }
}
