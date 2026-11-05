package io.github.dev_abdulhay.telegramauth.managedbots;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedBotLinkTest {

    @Test
    void buildsTheFullLinkWithUsernameAndEncodedName() {
        assertThat(ManagedBotLink.build("manager_bot", "tenant_shop_bot", "Shop Login"))
                .isEqualTo("https://t.me/newbot/manager_bot/tenant_shop_bot?name=Shop+Login");
    }

    @Test
    void omitsThePartsThatWereNotSuggested() {
        assertThat(ManagedBotLink.build("manager_bot", null, null))
                .isEqualTo("https://t.me/newbot/manager_bot");
        assertThat(ManagedBotLink.build("manager_bot", "tenant_shop_bot", null))
                .isEqualTo("https://t.me/newbot/manager_bot/tenant_shop_bot");
    }

    @Test
    void acceptsTheBotSuffixInAnyCase() {
        assertThat(ManagedBotLink.build("manager_bot", "TenantShopBOT", null))
                .isEqualTo("https://t.me/newbot/manager_bot/TenantShopBOT");
    }

    @Test
    void rejectsUsernamesTelegramWouldNeverAccept() {
        // too short (< 5), no bot suffix, illegal character, too long (> 32)
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "abot", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "tenant_shop", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "tenant-shop-bot", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManagedBotLink.build("manager_bot", "t".repeat(30) + "bot", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresAManagerUsername() {
        assertThatThrownBy(() -> ManagedBotLink.build(" ", "tenant_shop_bot", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
