package com.aliyun.autowonder.common.i18n;

import java.util.Locale;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/** Renders explicitly owned system text, leaving diagnostics and argument values untouched. */
public final class SystemMessages {
    private static final ResourceBundleMessageSource MESSAGES = new ResourceBundleMessageSource();
    static {
        MESSAGES.setBasename("i18n/messages");
        MESSAGES.setDefaultEncoding("UTF-8");
        MESSAGES.setFallbackToSystemLocale(false);
        MESSAGES.setDefaultLocale(Locale.CHINA);
        MESSAGES.setAlwaysUseMessageFormat(true);
    }

    private SystemMessages() {}

    public static String render(String key, Object[] args, String diagnostic) {
        var context = LocaleContextHolder.getLocaleContext();
        return render(key, args, diagnostic, context == null ? Locale.CHINA : context.getLocale());
    }

    public static String render(String key, Object[] args, String diagnostic, Locale locale) {
        if (key == null) return diagnostic;
        try {
            return MESSAGES.getMessage(key, args, supportedLocale(locale));
        } catch (NoSuchMessageException ignored) {
            return diagnostic;
        }
    }

    public static Locale resolveAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return Locale.CHINA;
        try {
            for (var range : Locale.LanguageRange.parse(header)) {
                if (range.getWeight() <= 0 || range.getRange().contains("*")) continue;
                String language = range.getRange().split("-", 2)[0];
                if (language.equals("en")) return Locale.US;
                if (language.equals("zh")) return Locale.CHINA;
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed client headers use the same Chinese fallback as unsupported languages.
        }
        return Locale.CHINA;
    }

    private static Locale supportedLocale(Locale locale) {
        return locale != null && "en".equals(locale.getLanguage()) ? Locale.US : Locale.CHINA;
    }
}
