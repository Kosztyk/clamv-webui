package info.trizub.clamav.webclient.service;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Backward-compatible message helper. The original project embedded most of the logic here;
 * in v2 the business logic lives in dedicated services.
 */
@Service
public class ClamAVWebClientService {

    private final MessageSource messageSource;

    public ClamAVWebClientService(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String getMessage(String message, @Nullable Object[] params) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(message, params, locale);
    }

    public String getMessage(String message) {
        return getMessage(message, null);
    }
}
