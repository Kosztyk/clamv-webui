package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.config.WebhookNotificationConfiguration;
import info.trizub.clamav.webclient.config.WebhookNotificationProperties;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Dedicated transport: no redirects, automatic retries, cookies or receiver body buffering. */
@Service
public class WebhookDeliveryClient implements AutoCloseable {
    private final WebhookAddressPolicy policy;
    private final WebhookNotificationProperties properties;
    private final CloseableHttpClient client;
    private final ScheduledThreadPoolExecutor deadlines;

    public WebhookDeliveryClient(WebhookAddressPolicy policy, WebhookNotificationProperties properties) {
        this.policy = policy;
        this.properties = properties;
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(policy)
            .setMaxConnTotal(properties.getWorkers()).setMaxConnPerRoute(properties.getWorkers())
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(properties.getReadTimeout().toMillis())).build())
            .build();
        client = HttpClients.custom().setConnectionManager(connections)
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.getConnectTimeout().toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(properties.getReadTimeout().toMillis()))
                .setHardCancellationEnabled(true).build())
            .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
            .disableContentCompression().build();
        deadlines = new ScheduledThreadPoolExecutor(1,
            WebhookNotificationConfiguration.daemonThreads("webhook-deadline-"));
        deadlines.setRemoveOnCancelPolicy(true);
    }

    public URI validateUrl(String url) { return policy.validateUrl(url); }

    public void post(URI uri, String body) throws IOException {
        policy.validateUrl(uri.toString());
        HttpPost request = new HttpPost(uri);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        var deadline = deadlines.schedule(request::cancel, properties.getDeliveryTimeout().toMillis(), TimeUnit.MILLISECONDS);
        try {
            // Keep the response open explicitly so closing it cannot drain an unbounded receiver body.
            var response = CloseableHttpResponse.adapt(client.executeOpen(null, request, null));
            try {
                if (response.getCode() < 200 || response.getCode() >= 300) {
                    throw new IOException("Webhook receiver returned a non-success status");
                }
            } finally {
                response.close(CloseMode.IMMEDIATE);
            }
        } finally {
            deadline.cancel(false);
            request.cancel();
        }
    }

    @Override @PreDestroy public void close() {
        deadlines.shutdownNow();
        client.close(CloseMode.IMMEDIATE);
    }
}
