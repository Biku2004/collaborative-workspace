package com.example.collaborativeworkspace.common.api;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;

/** Reject ambiguous wire shapes before validation or any service mutation. */
@Configuration
public class JsonInputConfiguration {
    @Bean
    JsonMapperBuilderCustomizer strictRequestTypes() {
        return builder -> builder
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
