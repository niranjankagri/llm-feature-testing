package com.niranjankagri.llmtesting.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The leave policy the assistant answers from, split into sections P1..P10 so answers can cite them. */
public final class Policy {

    private static final Pattern SECTION = Pattern.compile("(?m)^## (P\\d+) (.+)$");

    private final String markdown;
    private final Map<String, String> titles = new LinkedHashMap<>();

    private Policy(String markdown) {
        this.markdown = markdown.replace("\r\n", "\n");
        Matcher m = SECTION.matcher(this.markdown);
        while (m.find()) {
            titles.put(m.group(1), m.group(2).trim());
        }
    }

    /** The policy shipped with the app (src/main/resources/policy/leave-policy.md). */
    public static Policy load() {
        try (InputStream in = Policy.class.getResourceAsStream("/policy/leave-policy.md")) {
            if (in == null) {
                throw new IllegalStateException("leave-policy.md missing from the classpath");
            }
            return new Policy(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String markdown() {
        return markdown;
    }

    /** Section ids that exist, for checking the assistant's citations. */
    public Set<String> sectionIds() {
        return titles.keySet();
    }

    public String title(String sectionId) {
        return titles.get(sectionId);
    }
}
