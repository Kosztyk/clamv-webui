package info.trizub.clamav.webclient.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

/** Compatibility routes only: identity has one editor, review path and source. */
@Controller
@RequestMapping("/admin/settings/authentication")
public class AuthenticationSettingsController {
    @GetMapping
    public String page() {
        return "redirect:/admin/configuration-center?category=identity";
    }
    @PostMapping("/{provider}/{action}") @ResponseBody
    public ResponseEntity<?> retired(@PathVariable String provider,HttpServletRequest request) {
        return ConfigurationNavigation.moved(request,provider.equals("oidc")?"oidc":"ldap");
    }
}
