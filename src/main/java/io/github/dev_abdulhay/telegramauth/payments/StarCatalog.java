package io.github.dev_abdulhay.telegramauth.payments;

import java.util.List;
import java.util.Optional;

/** Lookup of products that can be sold for Stars. */
public interface StarCatalog {

    Optional<StarProduct> find(String code);

    List<StarProduct> all();
}
