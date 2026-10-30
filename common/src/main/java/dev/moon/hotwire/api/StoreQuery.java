package dev.moon.hotwire.api;

public record StoreQuery(StoreKind kind, String words, String category, int offset, String gameVersion,
        String loader) {
    public static StoreQuery of(String words) {
        return new StoreQuery(StoreKind.MOD, words, "", 0, "", "");
    }

    public StoreQuery withKind(StoreKind other) {
        return new StoreQuery(other, this.words, this.category, this.offset, this.gameVersion, this.loader);
    }

    public StoreQuery withCategory(String other) {
        return new StoreQuery(this.kind, this.words, other, this.offset, this.gameVersion, this.loader);
    }

    public StoreQuery withOffset(int other) {
        return new StoreQuery(this.kind, this.words, this.category, other, this.gameVersion, this.loader);
    }

    public StoreQuery withBrowsing(String otherVersion, String otherLoader) {
        return new StoreQuery(this.kind, this.words, this.category, this.offset, otherVersion, otherLoader);
    }
}
