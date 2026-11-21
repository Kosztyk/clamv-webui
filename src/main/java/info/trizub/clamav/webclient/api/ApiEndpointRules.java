package info.trizub.clamav.webclient.api;

import info.trizub.clamav.webclient.model.ClamdEndpoint;
import info.trizub.clamav.webclient.service.EndpointService;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared endpoint validation for legacy and v1 APIs. Resolve an unknown ID as 404, a missing
 * default as 503, and a disabled scan engine as 400. Authentication and token scopes remain the
 * responsibility of the calling controller.
 */
public final class ApiEndpointRules {
  private ApiEndpointRules() {}

  public static ClamdEndpoint resolve(EndpointService endpoints, Long id) {
    final ClamdEndpoint endpoint;
    try {
      endpoint = id == null ? endpoints.defaultEndpointOrEnsure() : endpoints.get(id);
    } catch (NoSuchElementException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found");
    }
    if (endpoint == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No endpoint configured");
    }
    return endpoint;
  }

  public static ClamdEndpoint requireEnabled(ClamdEndpoint endpoint) {
    if (!endpoint.isEnabled()) throw new IllegalArgumentException("Endpoint is disabled");
    return endpoint;
  }
}
