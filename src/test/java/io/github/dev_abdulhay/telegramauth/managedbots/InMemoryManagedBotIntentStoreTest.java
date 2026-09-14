package io.github.dev_abdulhay.telegramauth.managedbots;

class InMemoryManagedBotIntentStoreTest extends ManagedBotIntentStoreContract {

    private final InMemoryManagedBotIntentStore store = new InMemoryManagedBotIntentStore();

    @Override
    protected ManagedBotIntentStore store() {
        return store;
    }
}
