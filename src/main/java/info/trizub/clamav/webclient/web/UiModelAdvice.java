package info.trizub.clamav.webclient.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Ensures UI templates have access to the current request URI for tab highlighting.
 * Avoids relying on Thymeleaf's #request utility object (which may not be available depending on context).
 */
@ControllerAdvice
public class UiModelAdvice {

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
