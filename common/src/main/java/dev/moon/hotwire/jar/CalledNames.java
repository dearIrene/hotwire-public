package dev.moon.hotwire.jar;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.hash.Funnels;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

public final class CalledNames {
    public static final CalledNames UNKNOWN = new CalledNames(null);
    private static final Funnel<CharSequence> FUNNEL = Funnels.stringFunnel(StandardCharsets.UTF_8);
    private static final double FALSE_YES = 0.01;
    private final @Nullable BloomFilter<CharSequence> names;

    private CalledNames(@Nullable BloomFilter<CharSequence> names) {
        this.names = names;
    }

    public static CalledNames of(Collection<String> names) {
        BloomFilter<CharSequence> filter = BloomFilter.create(FUNNEL, Math.max(1, names.size()), FALSE_YES);
        names.forEach(filter::put);
        return new CalledNames(filter);
    }

    public boolean canCall(String method) {
        BloomFilter<CharSequence> filter = this.names;
        return filter == null || filter.mightContain(method);
    }

    public boolean canCall(String owner, String method) {
        BloomFilter<CharSequence> filter = this.names;
        return filter == null || filter.mightContain(owner + '.' + method);
    }

    void writeTo(DataOutputStream out) throws IOException {
        BloomFilter<CharSequence> filter = this.names;
        out.writeBoolean(filter != null);
        if (filter != null) {
            filter.writeTo(out);
        }
    }

    static CalledNames readFrom(DataInputStream in) throws IOException {
        return in.readBoolean() ? new CalledNames(BloomFilter.readFrom(in, FUNNEL)) : UNKNOWN;
    }
}
