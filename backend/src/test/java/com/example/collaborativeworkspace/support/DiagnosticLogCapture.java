package com.example.collaborativeworkspace.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/** Thread-safe capture for asserting structured fields across the async socket sender. */
public final class DiagnosticLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    private final Logger logger;
    public final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    public DiagnosticLogCapture(Class<?> type) { logger = (Logger) LoggerFactory.getLogger(type); start(); logger.addAppender(this); }
    @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); events.add(event); }
    public boolean contains(Object correlation, String stage) {
        return events.stream().anyMatch(event -> field(event, "correlationId").equals(correlation.toString()) && field(event, "stage").equals(stage));
    }
    public static String field(ILoggingEvent event, String key) {
        if (event.getKeyValuePairs() == null) return "";
        return event.getKeyValuePairs().stream().filter(value -> value.key.equals(key)).map(value -> String.valueOf(value.value)).findFirst().orElse("");
    }
    public static String rendered(ILoggingEvent event) {
        return event.getFormattedMessage() + String.valueOf(event.getKeyValuePairs()) + event.getMDCPropertyMap();
    }
    @Override public void close() { logger.detachAppender(this); stop(); }
}
