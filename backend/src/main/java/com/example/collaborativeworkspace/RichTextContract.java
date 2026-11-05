package com.example.collaborativeworkspace;

import java.io.IOException;
import java.util.Base64;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/** Versioned bootstrap data only; no application path creates rich documents yet. */
public final class RichTextContract {
    private static final int MAX_CONTRACT_BYTES = 65536, MAX_UPDATE_BYTES = 8192;
    private final byte[] initialUpdate;

    public RichTextContract(JsonMapper json) {
        try (var input = new ClassPathResource("contracts/rich-text-v1/contract.json").getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_CONTRACT_BYTES + 1);
            if (bytes.length > MAX_CONTRACT_BYTES) throw new IllegalStateException("Rich-text contract exceeds its size limit");
            var contract = json.readTree(bytes);
            if (contract == null || !contract.isObject() || !"RICH_TEXT_V1".equals(contract.path("format").asText()) ||
                    !"body".equals(contract.path("root").asText()) || !contract.path("initialUpdate").isTextual())
                throw new IllegalStateException("Invalid rich-text contract");
            String encoded = contract.path("initialUpdate").asText();
            byte[] update = Base64.getDecoder().decode(encoded);
            if (update.length == 0 || update.length > MAX_UPDATE_BYTES || !Base64.getEncoder().encodeToString(update).equals(encoded))
                throw new IllegalStateException("Invalid rich-text bootstrap update");
            this.initialUpdate = update;
        } catch (IOException | IllegalArgumentException | tools.jackson.core.JacksonException ex) {
            throw new IllegalStateException("Could not load rich-text contract", ex);
        }
    }

    public byte[] initialUpdate() { return initialUpdate.clone(); }
}
