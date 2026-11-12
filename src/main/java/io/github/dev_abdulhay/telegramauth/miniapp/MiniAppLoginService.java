package io.github.dev_abdulhay.telegramauth.miniapp;

import io.github.dev_abdulhay.telegramauth.api.dto.AuthApproveResult;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthChannel;
import io.github.dev_abdulhay.telegramauth.api.dto.AuthContext;
import io.github.dev_abdulhay.telegramauth.api.dto.TelegramUserInfo;
import io.github.dev_abdulhay.telegramauth.bot.TelegramBotModule;
import io.github.dev_abdulhay.telegramauth.entity.BaseTelegramUser;
import io.github.dev_abdulhay.telegramauth.service.AbstractTelegramUserService;

import java.time.Clock;
import java.util.Map;

/**
 * Logs a user in from Mini App initData. The identity comes only from the verified
 * initData — never from anything else the client sends.
 */
public class MiniAppLoginService<U extends BaseTelegramUser> {

    private final TelegramBotModule module;
    private final AbstractTelegramUserService<U> userService;
    private final InitDataReplayStore replayStore;
    private final Clock clock;
    private final MiniAppInitDataValidator validator = new MiniAppInitDataValidator();

    public MiniAppLoginService(TelegramBotModule module, AbstractTelegramUserService<U> userService,
                               InitDataReplayStore replayStore, Clock clock) {
        this.module = module;
        this.userService = userService;
        this.replayStore = replayStore;
        this.clock = clock;
    }

    /**
     * @return the approve handler's payload
     * @throws MiniAppAuthException when the initData is missing, invalid, expired or already used,
     *                              or the user is blocked
     * @throws IllegalStateException when the module has no {@code miniApp(...)} options
     */
    public Map<String, Object> login(String rawInitData, String ip, String userAgent, String hostRef) {
        MiniAppOptions options = module.getMiniAppOptions()
                .orElseThrow(() -> new IllegalStateException("Mini App login is not enabled on this module"));
        MiniAppInitData data = validator.validate(rawInitData, module.getBotToken(), options, clock.instant());
        // claimed up front so a concurrent request with the same initData is refused at once;
        // every path that does not end in a login releases it, so the initData can be retried
        if (!replayStore.claim(data.hash(), data.authDate().plus(options.maxAge()))) {
            throw new MiniAppAuthException(MiniAppError.INIT_DATA_EXPIRED, "initData was already used");
        }

        TelegramUserInfo info = data.user();
        AuthApproveResult result;
        try {
            U user = userService.register(info.telegramId(), null, info.firstName(), info.lastName(),
                    info.username(), info.languageCode());
            if (user.getStatus() == BaseTelegramUser.Status.BLOCKED) {
                throw new MiniAppAuthException(MiniAppError.USER_BLOCKED, "user is blocked");
            }
            result = module.getMiniAppHandler().onApprove(info,
                    new AuthContext(ip, userAgent, hostRef, AuthChannel.MINI_APP, data.startParam()));
        } catch (Throwable e) {
            try {
                replayStore.release(data.hash());
            } catch (Throwable releaseFailure) {
                // the caller needs the original cause; the release failure rides along
                e.addSuppressed(releaseFailure);
            }
            throw e;
        }
        return result.payload();
    }
}
