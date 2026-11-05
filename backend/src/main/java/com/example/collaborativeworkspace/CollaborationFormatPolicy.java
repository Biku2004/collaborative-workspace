package com.example.collaborativeworkspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Rich collaboration stays disabled by default until the full integration gate passes. */
@Component
@Profile("dev")
public final class CollaborationFormatPolicy {
    private final boolean richEnabled;
    public CollaborationFormatPolicy(@Value("${workspace.rich.enabled:false}") boolean richEnabled) { this.richEnabled = richEnabled; }
    public boolean supports(String format) {
        return "PLAIN_TEXT_V1".equals(format) || (richEnabled && "RICH_TEXT_V1".equals(format));
    }
}
