package io.github.dev_abdulhay.telegramauth.payments;

import java.util.List;
import java.util.Optional;

/** {@link StarCatalog} backed by {@code telegram.payments.products}, in declaration order. */
public class PropertiesStarCatalog implements StarCatalog {

    private final List<StarProduct> products;

    public PropertiesStarCatalog(TelegramPaymentsProperties p) {
        this.products = p.getProducts().entrySet().stream()
                .map(e -> new StarProduct(e.getKey(), e.getValue().getTitle(), e.getValue().getDescription(),
                        e.getValue().getStars(), e.getValue().getCredits(), e.getValue().isSubscription()))
                .toList();
    }

    @Override
    public Optional<StarProduct> find(String code) {
        return products.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    @Override
    public List<StarProduct> all() {
        return products;
    }
}
