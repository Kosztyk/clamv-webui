package info.trizub.clamav.webclient.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** Invalid scanner paths and malformed API input must not become HTTP 500 errors. */
@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidRequest(IllegalArgumentException ex) {
        return Map.of("error", "invalid_request", "message", "Invalid scan request or path policy violation");
    }
}
