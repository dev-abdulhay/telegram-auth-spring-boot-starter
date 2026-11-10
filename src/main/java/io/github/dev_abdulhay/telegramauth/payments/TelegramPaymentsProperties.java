package io.github.dev_abdulhay.telegramauth.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Telegram Stars payments settings. Its own namespace, independent of
 * {@code telegram.auth} and {@code telegram.managed-bots}.
 */
@ConfigurationProperties(prefix = "telegram.payments")
public class TelegramPaymentsProperties {

    /** Opt-in switch for the whole feature. */
    private boolean enabled = false;

    /** How long a CREATED order can still be paid. */
    private Duration orderTtl = Duration.ofMinutes(30);

    /** Time allowed to answer a pre-checkout query; Telegram's hard limit is 10 seconds. */
    private Duration preCheckoutTimeout = Duration.ofSeconds(5);

    private String preCheckoutRejectMessage = "This order is no longer available, please try again";

    /** How long EXPIRED orders are kept before the purge removes them. */
    private Duration orderRetention = Duration.ofDays(90);

    private Reconcile reconcile = new Reconcile();
    private Support support = new Support();
    private TopUp topUp = new TopUp();
    private Map<String, Product> products = new LinkedHashMap<>();
    private Balance balance = new Balance();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getOrderTtl() { return orderTtl; }
    public void setOrderTtl(Duration orderTtl) { this.orderTtl = orderTtl; }
    public Duration getPreCheckoutTimeout() { return preCheckoutTimeout; }
    public void setPreCheckoutTimeout(Duration preCheckoutTimeout) { this.preCheckoutTimeout = preCheckoutTimeout; }
    public String getPreCheckoutRejectMessage() { return preCheckoutRejectMessage; }
    public void setPreCheckoutRejectMessage(String m) { this.preCheckoutRejectMessage = m; }
    public Duration getOrderRetention() { return orderRetention; }
    public void setOrderRetention(Duration orderRetention) { this.orderRetention = orderRetention; }
    public Reconcile getReconcile() { return reconcile; }
    public void setReconcile(Reconcile reconcile) { this.reconcile = reconcile; }
    public Support getSupport() { return support; }
    public void setSupport(Support support) { this.support = support; }
    public TopUp getTopUp() { return topUp; }
    public void setTopUp(TopUp topUp) { this.topUp = topUp; }
    public Map<String, Product> getProducts() { return products; }
    public void setProducts(Map<String, Product> products) { this.products = products; }
    public Balance getBalance() { return balance; }
    public void setBalance(Balance balance) { this.balance = balance; }

    /**
     * Fails fast on values Telegram would reject at runtime.
     *
     * @throws IllegalStateException naming the offending property key
     */
    public void validate() {
        if (preCheckoutTimeout == null || preCheckoutTimeout.isZero() || preCheckoutTimeout.isNegative()
                || preCheckoutTimeout.compareTo(Duration.ofSeconds(10)) >= 0) {
            throw new IllegalStateException(
                    "telegram.payments.pre-checkout-timeout must be > 0 and < 10s, was " + preCheckoutTimeout);
        }
        if (topUp.minStars < 1) {
            throw new IllegalStateException("telegram.payments.top-up.min-stars must be >= 1");
        }
        if (topUp.minStars > topUp.maxStars) {
            throw new IllegalStateException("telegram.payments.top-up.min-stars must be <= max-stars");
        }
        if (topUp.creditsPerStar < 1) {
            throw new IllegalStateException("telegram.payments.top-up.credits-per-star must be >= 1");
        }
        products.forEach((code, p) -> {
            String key = "telegram.payments.products." + code;
            if (p.title == null || p.title.isEmpty() || p.title.length() > 32) {
                throw new IllegalStateException(key + ".title must be 1-32 characters");
            }
            if (p.description == null || p.description.isEmpty() || p.description.length() > 255) {
                throw new IllegalStateException(key + ".description must be 1-255 characters");
            }
            if (p.stars < 1) {
                throw new IllegalStateException(key + ".stars must be >= 1");
            }
            if (p.subscription && p.stars > 10000) {
                throw new IllegalStateException(key + ".stars must be <= 10000 for a subscription");
            }
            if (p.credits < 0) {
                throw new IllegalStateException(key + ".credits must be >= 0");
            }
        });
    }

    public static class Reconcile {
        private boolean enabled = true;
        private Duration interval = Duration.ofMinutes(15);
        private Duration subscriptionGrace = Duration.ofHours(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public Duration getSubscriptionGrace() { return subscriptionGrace; }
        public void setSubscriptionGrace(Duration subscriptionGrace) { this.subscriptionGrace = subscriptionGrace; }
    }

    public static class Support {
        private boolean enabled = true;
        private String paysupportText = "For payment issues, reply here and our team will help you. "
                + "Telegram support cannot help with purchases made in this bot.";
        private String termsText = "By paying you agree to the terms of this service.";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPaysupportText() { return paysupportText; }
        public void setPaysupportText(String paysupportText) { this.paysupportText = paysupportText; }
        public String getTermsText() { return termsText; }
        public void setTermsText(String termsText) { this.termsText = termsText; }
    }

    public static class TopUp {
        private boolean enabled = false;
        private int minStars = 10;
        private int maxStars = 10000;
        private long creditsPerStar = 1;
        private String title = "Balance top-up";
        private String description = "Top up your balance";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMinStars() { return minStars; }
        public void setMinStars(int minStars) { this.minStars = minStars; }
        public int getMaxStars() { return maxStars; }
        public void setMaxStars(int maxStars) { this.maxStars = maxStars; }
        public long getCreditsPerStar() { return creditsPerStar; }
        public void setCreditsPerStar(long creditsPerStar) { this.creditsPerStar = creditsPerStar; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class Product {
        private String title;
        private String description;
        private int stars;
        private long credits;
        private boolean subscription;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getStars() { return stars; }
        public void setStars(int stars) { this.stars = stars; }
        public long getCredits() { return credits; }
        public void setCredits(long credits) { this.credits = credits; }
        public boolean isSubscription() { return subscription; }
        public void setSubscription(boolean subscription) { this.subscription = subscription; }
    }

    public static class Balance {
        private boolean enabled = false;
        private BalanceScope scope = BalanceScope.GLOBAL;
        private RefundPolicy refundPolicy = RefundPolicy.REQUIRE_BALANCE;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public BalanceScope getScope() { return scope; }
        public void setScope(BalanceScope scope) { this.scope = scope; }
        public RefundPolicy getRefundPolicy() { return refundPolicy; }
        public void setRefundPolicy(RefundPolicy refundPolicy) { this.refundPolicy = refundPolicy; }
    }
}
