package dev.moon.hotwire.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public record CacheKey(long high, long low) implements Comparable<CacheKey> {
    @Override
    public int compareTo(CacheKey other) {
        int first = Long.compareUnsigned(this.high, other.high);
        return first != 0 ? first : Long.compareUnsigned(this.low, other.low);
    }

    public static Builder of(String recipe) {
        return new Builder().add(recipe);
    }

    public static final class Builder {
        private final MessageDigest digest;

        private Builder() {
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("this Java has no SHA-256", e);
            }
        }

        public Builder add(byte[] input) {
            this.digest.update(ByteBuffer.allocate(4).putInt(input.length).array());
            this.digest.update(input);
            return this;
        }

        public Builder add(String input) {
            return this.add(input.getBytes(StandardCharsets.UTF_8));
        }

        public Builder add(long input) {
            return this.add(ByteBuffer.allocate(8).putLong(input).array());
        }

        public CacheKey build() {
            ByteBuffer hash = ByteBuffer.wrap(this.digest.digest());
            return new CacheKey(hash.getLong(), hash.getLong());
        }
    }
}
