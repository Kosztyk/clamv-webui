package info.trizub.clamav.webclient.web;

import jakarta.servlet.http.HttpServletRequest;
import info.trizub.clamav.webclient.identity.IdentityProperties;
import info.trizub.clamav.webclient.identity.ExternalOidcUser;
import info.trizub.clamav.webclient.identity.ExternalLdapPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Ensures UI templates have access to the current request URI for tab highlighting.
 * Avoids relying on Thymeleaf's #request utility object (which may not be available depending on context).
 */
@ControllerAdvice
public class UiModelAdvice {
    private final IdentityProperties identities;
    public UiModelAdvice(IdentityProperties identities) { this.identities = identities; }
    @Value("${clamav.app.version:2.0.0}") private String appVersion;
    @ModelAttribute("appVersion") public String appVersion(){return appVersion;}
    @ModelAttribute("displayName") public String displayName(Authentication authentication) {
        if (authentication == null) return "";
        Object principal = authentication.getPrincipal();
        if (principal instanceof ExternalLdapPrincipal ldap) return ldap.displayName();
        if (principal instanceof ExternalOidcUser oidc) {
            Object preferred = oidc.delegate().getAttributes().get("preferred_username");
            if (!(preferred instanceof String) || ((String) preferred).isBlank())
                preferred = oidc.delegate().getAttributes().get("name");
            if (preferred instanceof String s && !s.isBlank()) return s;
        }
        return authentication.getName();
    }
    @ModelAttribute("ldapEnabled") public boolean ldapEnabled() { return identities.getLdap().isEnabled(); }
    @ModelAttribute("oidcEnabled") public boolean oidcEnabled() { return identities.getOidc().isEnabled(); }
    @ModelAttribute("oidcLabel") public String oidcLabel() { return identities.getOidc().getButtonLabel(); }
    @ModelAttribute("oidcLoginUrl") public String oidcLoginUrl() {
        if (!identities.getOidc().isEnabled()) return "";
        String registration = identities.getOidc().getRegistrationId();
        if (!registration.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Unsafe OIDC registration ID");
        return "/oauth2/authorization/" + registration;
    }

    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
