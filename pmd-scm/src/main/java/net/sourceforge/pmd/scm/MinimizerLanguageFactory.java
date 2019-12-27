/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageRegistry;

public final class MinimizerLanguageFactory {
    public static final MinimizerLanguageFactory INSTANCE = new MinimizerLanguageFactory();

    private final Map<String, MinimizerLanguage> languages = new LinkedHashMap<>();

    private final String supportedLanguageNames;

    private String createLanguageHelp(List<MinimizerLanguage> handlers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < handlers.size(); ++i) {
            MinimizerLanguage lang = handlers.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(lang.getTerseName());
            if (lang.getLanguageVersions().size() > 1) {
                sb.append(" (");
                for (int j = 0; j < lang.getLanguageVersions().size(); ++j) {
                    if (j > 0) {
                        sb.append(", ");
                    }
                    sb.append(lang.getLanguageVersions().get(j));
                }
                sb.append(")");
            }
        }
        return sb.toString();
    }

    private MinimizerLanguageFactory() {
        List<MinimizerLanguage> handlers = new ArrayList<>();

        for (Language language: LanguageRegistry.getLanguages()) {
            MinimizerLanguage handler = new MinimizerLanguageModuleAdapter(language);
            handlers.add(handler);
            languages.put(handler.getTerseName().toLowerCase(Locale.ROOT), handler);
        }

        supportedLanguageNames = createLanguageHelp(handlers);
    }

    public String getSupportedLanguagesWithVersions() {
        return supportedLanguageNames;
    }

    public MinimizerLanguage getLanguage(String name) {
        return languages.get(name.toLowerCase(Locale.ROOT));
    }
}
