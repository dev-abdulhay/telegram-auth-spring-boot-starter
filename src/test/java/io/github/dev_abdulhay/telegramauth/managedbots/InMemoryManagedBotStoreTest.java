package io.github.dev_abdulhay.telegramauth.managedbots;

class InMemoryManagedBotStoreTest extends ManagedBotStoreContract {

    private final InMemoryManagedBotStore store = new InMemoryManagedBotStore();

    @Override
    protected ManagedBotTokenStore store() {
        return store;
    }
}
