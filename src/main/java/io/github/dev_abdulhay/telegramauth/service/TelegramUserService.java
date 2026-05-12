package io.github.dev_abdulhay.telegramauth.service;

import io.github.dev_abdulhay.telegramauth.api.TelegramAuthRegisterHandler;
import io.github.dev_abdulhay.telegramauth.api.dto.RegisterContext;
import io.github.dev_abdulhay.telegramauth.api.dto.TelegramUserInfo;
import io.github.dev_abdulhay.telegramauth.entity.MTelegramUser;
import io.github.dev_abdulhay.telegramauth.entity.MTelegramUser.Status;
import io.github.dev_abdulhay.telegramauth.repository.TelegramUserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

public class TelegramUserService {

    private final TelegramUserRepository repo;
    private final TelegramAuthRegisterHandler registerHandler;

    public TelegramUserService(TelegramUserRepository repo,
                               TelegramAuthRegisterHandler registerHandler) {
        this.repo = repo;
        this.registerHandler = registerHandler;
    }

    @Transactional(readOnly = true)
    public Optional<MTelegramUser> findByTelegramId(Long tgId) {
        return repo.findByTelegramId(tgId);
    }

    @Transactional
    public MTelegramUser register(Long telegramId,
                                  String phone,
                                  String firstName,
                                  String lastName,
                                  String username,
                                  String languageCode) {
        MTelegramUser u = repo.findByTelegramId(telegramId).orElseGet(MTelegramUser::new);
        u.setTelegramId(telegramId);
        u.setPhone(phone);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setUsername(username);
        u.setLanguageCode(languageCode);
        u.setStatus(Status.ACTIVE);
        u.setUpdatedAt(OffsetDateTime.now());
        MTelegramUser saved = repo.save(u);

        if (registerHandler != null) {
            RegisterContext ctx = new RegisterContext();
            TelegramUserInfo info = new TelegramUserInfo(
                    saved.getTelegramId(), saved.getPhone(),
                    saved.getFirstName(), saved.getLastName(),
                    saved.getUsername(), saved.getLanguageCode(),
                    saved.getExternalUserId()
            );
            registerHandler.onFirstRegister(info, ctx);
            if (ctx.getExternalUserId() != null) {
                saved.setExternalUserId(ctx.getExternalUserId());
                saved = repo.save(saved);
            }
        }
        return saved;
    }
}
