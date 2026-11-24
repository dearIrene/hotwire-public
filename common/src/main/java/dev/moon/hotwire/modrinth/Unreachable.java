package dev.moon.hotwire.modrinth;

import dev.moon.hotwire.ui.Lang;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import javax.net.ssl.SSLException;
import org.jspecify.annotations.Nullable;

final class Unreachable {
    private Unreachable() {
    }

    static IOException describe(IOException failure) {
        String key = findKey(failure);
        return key == null ? failure : new IOException(Lang.get(key), failure);
    }

    static @Nullable String findKey(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            if (at instanceof SSLException) {
                return "hotwire.net.blocked";
            }
            if (at instanceof UnknownHostException || at instanceof UnresolvedAddressException) {
                return "hotwire.net.not_found";
            }
            if (at instanceof ConnectException || at instanceof HttpConnectTimeoutException) {
                return "hotwire.net.no_connection";
            }
            if (at instanceof HttpTimeoutException) {
                return "hotwire.net.slow";
            }
        }
        return null;
    }
}
