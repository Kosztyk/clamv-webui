package info.trizub.clamav.webclient.configuration;

/** Transport choice for authenticated configuration only; never changes API token security. */
public final class ConfigurationTransport {
    private ConfigurationTransport() { }

    public static void requireConfirmation(boolean secure,boolean acknowledgeHttp) {
        if(!secure && !acknowledgeHttp)
            throw new IllegalArgumentException("This connection uses HTTP. Select \"Allow configuration over HTTP on this trusted network\" before submitting credentials, or use HTTPS.");
    }
}
