package info.trizub.clamav.webclient.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Legacy routes kept for backwards compatibility with the original project.
 * The application UI has been expanded; use /dashboard, /scan, /jobs, /admin/*.
 */
@Controller
public class ClamAVWebClientController {

    private final LocaleResolver localeResolver;

    public ClamAVWebClientController(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @GetMapping(value = {"/settings"})
    public String settings(Authentication auth) {
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return "redirect:/admin/settings";
        }
        return "redirect:/dashboard";
    }

    @GetMapping({"/ping","/version","/stats","/reload"})
    public String legacyOps() {
        return "redirect:/dashboard";
    }

    @GetMapping({"/scanFolder","/scanFile"})
    public String legacyScan() {
        return "redirect:/scan";
    }

    private static final Set<String> LANGUAGES = Set.of("en", "uk", "ro", "fr", "es", "de");

    @GetMapping("/setLocale")
    public String setLocale(@RequestParam String lang,
            @RequestParam(required = false) String returnTo,
            HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
            Authentication authentication) {
        String selected = lang == null ? "" : lang.toLowerCase(Locale.ROOT);
        if (!LANGUAGES.contains(selected)) selected = "en";
        localeResolver.setLocale(req, res, Locale.forLanguageTag(selected));
        // Only accept plain same-site paths; no external/protocol-relative URLs.
        if (returnTo != null && returnTo.matches("/[A-Za-z0-9/_-]*")
                && !returnTo.startsWith("//") && !returnTo.startsWith("/setLocale")) {
            return "redirect:" + returnTo;
        }
        return authentication == null ? "redirect:/login" : "redirect:/dashboard";
    }
}
