package info.trizub.clamav.webclient.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import java.util.Map;

/** Retired forms must never bypass central validation or overwrite saved values. */
final class ConfigurationNavigation {
    private ConfigurationNavigation() { }
    static ResponseEntity<?> moved(HttpServletRequest request,String section) {
        String target=request.getContextPath()+"/admin/configuration-center?section="+section;
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(Map.of("ok",false,"saved",false,"configurationCenter",target,
                        "message","Settings are managed only in Configuration Center. Review and apply changes there; this request did not change configuration."));
    }
}
