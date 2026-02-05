package info.trizub.clamav.webclient.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

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

    @GetMapping("/setLocale")
    public String setLocale(@RequestParam String lang, HttpServletRequest req) {
        localeResolver.setLocale(req, null, Locale.forLanguageTag(lang));
        return "redirect:/dashboard";
    }
}
