package io.github.dev_abdulhay.telegramauth.payments.balance;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

/** Base repository for any {@link BaseLedgerEntry} subtype. */
@NoRepositoryBean
public interface BaseLedgerEntryRepository<E extends BaseLedgerEntry> extends JpaRepository<E, String> {

    List<E> findByAccountKeyOrderByCreatedAtDesc(String accountKey, Pageable page);
}
