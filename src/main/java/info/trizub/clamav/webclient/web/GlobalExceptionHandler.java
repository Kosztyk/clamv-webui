package info.trizub.clamav.webclient.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.NoSuchElementException;

@ControllerAdvice(basePackages = "info.trizub.clamav.webclient.web")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleUploadTooLarge(MaxUploadSizeExceededException ex, RedirectAttributes ra) {
        ra.addFlashAttribute("errorMsg", "Upload too large: " + safeMsg(ex.getMessage()));
        return "redirect:/scan";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public String handleBadRequest(IllegalArgumentException ex, RedirectAttributes ra) {
        ra.addFlashAttribute("errorMsg", safeMsg(ex.getMessage()));
        return "redirect:/scan";
    }

    @ExceptionHandler(NoSuchElementException.class)
    public String handleNotFound(NoSuchElementException ex, RedirectAttributes ra) {
        ra.addFlashAttribute("errorMsg", "Not found");
        return "redirect:/dashboard";
    }

    @ExceptionHandler(Exception.class)
    public String handleAny(Exception ex, RedirectAttributes ra) {
        // Keep the app usable: show a friendly message and log the root cause.
        log.error("Unhandled exception", ex);
        ra.addFlashAttribute("errorMsg", "Unexpected error: " + safeMsg(ex.getMessage()));
        // Use a neutral landing page.
        return "redirect:/dashboard";
    }

    private static String safeMsg(String msg) {
        if (msg == null || msg.isBlank()) return "(no details)";
        // Prevent very long exception messages from spamming the UI
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}
