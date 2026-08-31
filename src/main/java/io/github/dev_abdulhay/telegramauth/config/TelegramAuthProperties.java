package io.github.dev_abdulhay.telegramauth.config;

import io.github.dev_abdulhay.telegramauth.flow.CodeConfirmation;
import io.github.dev_abdulhay.telegramauth.flow.DefaultAuthFlow;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global, type-agnostic starter properties. Per-type infrastructure (bot token,
 * session TTL, command registry) still lives in code via
 * {@code TelegramBotModule}; only the flow's behavioural switches are bindable,
 * because those are what operators tune per environment.
 */
@ConfigurationProperties(prefix = "telegram.auth")
public class TelegramAuthProperties {

    /** Master switch; auto-config stays inert if {@code false}. */
    private boolean enabled = false;

    /** Spring cron for the per-module expired-session sweep. */
    private String cleanupCron = "0 */5 * * * *";

    /** Defaults for every {@link DefaultAuthFlow}. */
    private final Flow flow = new Flow();

    /**
     * Per-flow overrides, keyed by a name the host chooses. Anything left unset
     * here falls back to {@link #getFlow()} and then to the built-in defaults,
     * so a host running several user types states only what actually differs.
     */
    private final Map<String, Flow> flows = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }

    public Flow getFlow() { return flow; }
    public Map<String, Flow> getFlows() { return flows; }

    /**
     * Bindable mirror of {@link DefaultAuthFlow.Options}.
     *
     * <p>Every field is a wrapper type on purpose: {@code null} means "not
     * configured", which is what makes the three-level fallback
     * (named group → {@code flow} → built-in default) possible at all. With
     * primitives, a value equal to the default would be indistinguishable from
     * an absent one.
     */
    public static class Flow {

        private Boolean requireContact;
        private Boolean requireApproval;
        private CodeConfirmation codeConfirmation;
        private Integer codeButtons;
        private Integer maxCodeAttempts;
        private Duration codeCooldown;
        private Duration codeCooldownMax;
        private Integer codeCooldownThreshold;

        public Boolean getRequireContact() { return requireContact; }
        public void setRequireContact(Boolean v) { this.requireContact = v; }
        public Boolean getRequireApproval() { return requireApproval; }
        public void setRequireApproval(Boolean v) { this.requireApproval = v; }
        public CodeConfirmation getCodeConfirmation() { return codeConfirmation; }
        public void setCodeConfirmation(CodeConfirmation v) { this.codeConfirmation = v; }
        public Integer getCodeButtons() { return codeButtons; }
        public void setCodeButtons(Integer v) { this.codeButtons = v; }
        public Integer getMaxCodeAttempts() { return maxCodeAttempts; }
        public void setMaxCodeAttempts(Integer v) { this.maxCodeAttempts = v; }
        public Duration getCodeCooldown() { return codeCooldown; }
        public void setCodeCooldown(Duration v) { this.codeCooldown = v; }
        public Duration getCodeCooldownMax() { return codeCooldownMax; }
        public void setCodeCooldownMax(Duration v) { this.codeCooldownMax = v; }
        public Integer getCodeCooldownThreshold() { return codeCooldownThreshold; }
        public void setCodeCooldownThreshold(Integer v) { this.codeCooldownThreshold = v; }

        /** Resolves against the built-in defaults only. */
        public DefaultAuthFlow.Options toOptions() {
            return toOptions(null);
        }

        /**
         * Resolves each field against {@code base} and then the built-in defaults.
         * Validation stays in {@link DefaultAuthFlow.Options.Builder#build()}, so a
         * bad value fails the application context with the same message a
         * hand-written builder would produce.
         *
         * @param base the {@code telegram.auth.flow} group, or {@code null}
         */
        public DefaultAuthFlow.Options toOptions(Flow base) {
            DefaultAuthFlow.Options d = DefaultAuthFlow.Options.defaults();
            return DefaultAuthFlow.Options.builder()
                    .requireContact(pick(requireContact, base == null ? null : base.requireContact, d.requireContact()))
                    .requireApproval(pick(requireApproval, base == null ? null : base.requireApproval, d.requireApproval()))
                    .codeConfirmation(pick(codeConfirmation, base == null ? null : base.codeConfirmation, d.codeConfirmation()))
                    .codeButtons(pick(codeButtons, base == null ? null : base.codeButtons, d.codeButtons()))
                    .maxCodeAttempts(pick(maxCodeAttempts, base == null ? null : base.maxCodeAttempts, d.maxCodeAttempts()))
                    .codeCooldown(pick(codeCooldown, base == null ? null : base.codeCooldown, d.codeCooldown()))
                    .codeCooldownMax(pick(codeCooldownMax, base == null ? null : base.codeCooldownMax, d.codeCooldownMax()))
                    .codeCooldownThreshold(pick(codeCooldownThreshold, base == null ? null : base.codeCooldownThreshold,
                            d.codeCooldownThreshold()))
                    .build();
        }

        private static <T> T pick(T own, T base, T builtIn) {
            if (own != null) return own;
            if (base != null) return base;
            return builtIn;
        }
    }
}
