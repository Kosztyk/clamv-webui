package info.trizub.clamav.webclient.web;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.NoSuchElementException;

@ControllerAdvice(basePackages = "info.trizub.clamav.webclient.web")
public class GlobalExceptionHandler {

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

    private static String safeMsg(String msg) {
        if (msg == null || msg.isBlank()) return "(no details)";
        // Prevent very long exception messages from spamming the UI
        return msg.length() > 500 ? msg.substring(0, 500) + "..." : msg;
    }
}
