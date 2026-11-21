package services;

import java.net.URI;
import java.util.Locale;

final class ImageUrlPolicy {
    private ImageUrlPolicy() {}
    static boolean allowed(String value, PredictionSettings settings) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && settings.getAllowedImageHosts().stream().anyMatch(host ->
                    uri.getHost().toLowerCase(Locale.ROOT).equals(host.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) { return false; }
    }
}
