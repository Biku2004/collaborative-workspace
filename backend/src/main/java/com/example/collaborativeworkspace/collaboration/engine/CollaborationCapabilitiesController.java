package com.example.collaborativeworkspace.collaboration.engine;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** UI discovery only: all document operations still enforce the format policy themselves. */
@RestController
@Profile("dev")
public class CollaborationCapabilitiesController {
    private final CollaborationFormatPolicy formats;
    public CollaborationCapabilitiesController(CollaborationFormatPolicy formats) { this.formats = formats; }
    public record Capabilities(int version, List<String> documentFormats) {}
    @GetMapping("/api/capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Capabilities(1,
                List.of("PLAIN_TEXT_V1", "RICH_TEXT_V1").stream().filter(formats::supports).toList()));
    }
}
