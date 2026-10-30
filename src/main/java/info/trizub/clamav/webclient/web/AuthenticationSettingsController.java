package info.trizub.clamav.webclient.web;

import info.trizub.clamav.webclient.identity.AuthenticationSettingsService;
import info.trizub.clamav.webclient.identity.AuthenticationProviderProber;
import info.trizub.clamav.webclient.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.*;
import org.springframework.dao.DataAccessException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;

/** Only admins can update providers; all writes are CSRF protected by the main security chain. */
@Controller
@RequestMapping("/admin/settings/authentication")
public class AuthenticationSettingsController {
    private final AuthenticationSettingsService settings;
    private final AuthenticationProviderProber prober;
    private final AuditService audit;
    public AuthenticationSettingsController(AuthenticationSettingsService settings, AuthenticationProviderProber prober, AuditService audit) {
        this.settings=settings;this.prober=prober;this.audit=audit;
    }
    @GetMapping
    public String form(Model model) {
        Map<String,String> ldap=new LinkedHashMap<>(settings.values("ldap"));
        Map<String,String> oidc=new LinkedHashMap<>(settings.values("oidc"));
        // For non-JavaScript browsers, restore entered non-secret values after Test / validation.
        Object draftProvider=model.asMap().get("authDraftProvider");
        Object draft=model.asMap().get("authDraftValues");
        if (draftProvider instanceof String source && draft instanceof Map<?,?> fields) {
            Map<String,String> target="ldap".equals(source)?ldap:"oidc".equals(source)?oidc:null;
            if (target!=null) {
                fields.forEach((key,value)->{
                    if (key instanceof String name && value instanceof String text && target.containsKey(name))
                        target.put(name,text);
                });
                for (String name:("ldap".equals(source)?List.of("enabled","startTls","nestedGroups","pooled"):List.of("enabled")))
                    target.put(name,Boolean.toString(fields.containsKey(name)));
            }
        }
        model.addAttribute("ldap",ldap);model.addAttribute("oidc",oidc);
        model.addAttribute("ldapActive",settings.values("ldap").get("enabled"));
        model.addAttribute("oidcActive",settings.values("oidc").get("enabled"));
        model.addAttribute("ldapStored",settings.uiManaged("ldap"));model.addAttribute("oidcStored",settings.uiManaged("oidc"));
        model.addAttribute("ldapSecretSet",settings.hasSecret("ldap"));model.addAttribute("oidcSecretSet",settings.hasSecret("oidc"));
        return "pages/authentication";
    }
    /** JSON calls keep the draft in the browser; a failed Test must never erase the form. */
    @PostMapping(value="/{provider}/{action}", headers="X-Requested-With=XMLHttpRequest")
    @ResponseBody
    public ResponseEntity<Map<String,Object>> handleAjax(@PathVariable String provider, @PathVariable String action,
                         @RequestParam Map<String,String> parameters, HttpServletRequest request,
                         Authentication auth) {
        ActionResult result=execute(provider,action,parameters,request,auth);
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("ok",result.ok());
        data.put("message",result.message());
        data.put("provider",provider);
        data.put("saved",result.ok() && "save".equals(action));
        if (result.ok() && "save".equals(action)) {
            data.put("enabled",settings.values(provider).get("enabled"));
            data.put("secretConfigured",settings.hasSecret(provider));
        }
        return ResponseEntity.status(result.ok()?HttpStatus.OK:HttpStatus.BAD_REQUEST).body(data);
    }

    @PostMapping(value="/{provider}/{action}", headers="X-Requested-With!=XMLHttpRequest")
    public String handle(@PathVariable String provider, @PathVariable String action,
                         @RequestParam Map<String,String> parameters, HttpServletRequest request,
                         Authentication auth, RedirectAttributes redirect) {
        ActionResult result=execute(provider,action,parameters,request,auth);
        redirect.addFlashAttribute(result.ok()?"authSuccess":"authError",result.message());
        // On a non-JavaScript submission, keep non-secret draft fields on validation failure.
        // Never put a password into FlashMap, a URL, or rendered HTML.
        if (!result.ok() || "test".equals(action)) {
            Map<String,String> safeDraft=new LinkedHashMap<>(parameters);
            safeDraft.remove("secret");safeDraft.remove("_csrf");
            redirect.addFlashAttribute("authDraftProvider",provider);
            redirect.addFlashAttribute("authDraftValues",safeDraft);
        }
        return "redirect:/admin/settings/authentication#"+provider;
    }

    private record ActionResult(boolean ok,String message) { }

    private ActionResult execute(String provider, String action,Map<String,String> parameters,
                                 HttpServletRequest request,Authentication auth) {
        if (!provider.equals("ldap") && !provider.equals("oidc")) throw new IllegalArgumentException("Unknown provider");
        if (!action.equals("save") && !action.equals("test")) throw new IllegalArgumentException("Unknown action");
        String replacement=parameters.getOrDefault("secret","");
        if (!replacement.isBlank() && !request.isSecure()) {
            audit.record(auth,request,"AUTH_PROVIDER_"+action.toUpperCase(Locale.ROOT),"provider="+provider,"FAILED",null);
            return new ActionResult(false,"Credentials were not submitted securely. Use HTTPS (and trusted reverse-proxy headers) before testing or saving a new bind/client secret. Other entered fields are unchanged.");
        }
        Map<String,String> draft=new HashMap<>(parameters);
        draft.remove("secret");draft.remove("_csrf");
        // Unchecked HTML checkbox inputs are absent. Treat those as explicitly disabled.
        for(String checkbox: provider.equals("ldap")?List.of("enabled","nestedGroups","startTls","pooled"):List.of("enabled"))
            draft.put(checkbox,Boolean.toString(parameters.containsKey(checkbox)));
        try {
            Map<String,String> normalized=settings.draft(provider,draft);
            String effective=replacement.isBlank()?settings.effectiveSecret(provider):replacement;
            if (action.equals("test") || "true".equals(normalized.get("enabled"))) {
                prober.test(provider,normalized,effective);
            } else {
                settings.validateDraft(provider,normalized,replacement);
            }
            if (action.equals("save")) {
                settings.save(provider,normalized,replacement,auth.getName());
                audit.record(auth,request,"AUTH_PROVIDER_UPDATED", "provider="+provider+";enabled="+normalized.get("enabled")+
                        ";adminGroupMapped="+!normalized.get("admin").isBlank(), "SUCCESS", null);
                return new ActionResult(true,provider.toUpperCase(Locale.ROOT)+" settings saved to the database and applied to new logins.");
            }
            audit.record(auth,request,"AUTH_PROVIDER_TEST", "provider="+provider,"SUCCESS",null);
            return new ActionResult(true,provider.toUpperCase(Locale.ROOT)+" connectivity test passed. Settings are NOT saved yet; click Save to persist them."
                +(provider.equals("oidc")?" Client-secret validity is confirmed only during sign-in.":""));
        } catch(Exception e) {
            audit.record(auth,request,"AUTH_PROVIDER_"+action.toUpperCase(Locale.ROOT),"provider="+provider,"FAILED",null);
            // Never reflect submitted secrets or external provider exception bodies.
            return new ActionResult(false,"Unable to "+action+" "+provider.toUpperCase(Locale.ROOT)+" configuration: "+safeReason(e)
                +". Your entered settings have not been discarded.");
        }
    }
    private static String safeReason(Exception e) {
        String message=e.getMessage();
        // Only expose selected, application-authored validation messages.
        // Raw provider and database exception bodies must never reach the frontend.
        if (message!=null && (message.startsWith("LDAP ") || message.startsWith("OIDC ") ||
                message.startsWith("At least one") || message.startsWith("Invalid ") ||
                message.startsWith("Use HTTPS"))) return message;
        for (Throwable cause=e; cause!=null; cause=cause.getCause()) {
            if (cause instanceof DataAccessException)
                return "Authentication settings database write/read failed; check PostgreSQL connectivity and Flyway V4 migration";
            if (cause instanceof AccessDeniedException || cause instanceof FileSystemException)
                return "Authentication encryption key could not be accessed. Check write permissions on the persistent /app/conf volume";
            if (cause instanceof IllegalStateException && cause.getMessage()!=null &&
                cause.getMessage().startsWith("Authentication encryption key"))
                return "Authentication encryption key is unavailable. Check /app/conf/auth-settings.key and the configured key file or secret";
        }
        return "Check the ClamAV container logs for an authentication settings failure. Existing configuration is unchanged";
    }
}
