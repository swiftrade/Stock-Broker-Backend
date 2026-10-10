package com.stockpredict.marketdata.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.config.MarketDataProperties;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code bars.updated} events to the Redis stream of the same name.
 * The stream is capped with an approximate {@code MAXLEN} so it cannot grow
 * unboundedly. Failures (e.g. Redis down) are logged and swallowed so ingestion
 * never fails because of the event bus.
 */
@Component
public class BarsUpdatedPublisher {

    private static final Logger log = LoggerFactory.getLogger(BarsUpdatedPublisher.class);
    private static final String STREAM = "bars.updated";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final long maxStreamLength;

    public BarsUpdatedPublisher(StringRedisTemplate redis, ObjectMapper objectMapper, MarketDataProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.maxStreamLength = properties.getStreamMaxLength();
    }

    public void publish(String symbol, String interval, List<Bar> bars, String source) {
        if (bars == null || bars.isEmpty()) {
            return;
        }
        Bar lastBar = bars.get(bars.size() - 1);
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", UUID.randomUUID().toString());
            event.put("type", "bars.updated");
            event.put("occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
            event.put("symbol", symbol);
            event.put("interval", interval);
            event.put("barCount", bars.size());
            event.put("lastBarTs", lastBar.getTs().toString());
            event.put("source", source == null ? "cache" : source);
            event.put("stale", false);

            String payload = objectMapper.writeValueAsString(event);
            MapRecord<byte[], byte[], byte[]> record = StreamRecords.rawBytes(Map.of(
                            "payload".getBytes(StandardCharsets.UTF_8),
                            payload.getBytes(StandardCharsets.UTF_8)))
                    .withStreamKey(STREAM.getBytes(StandardCharsets.UTF_8));
            XAddOptions options = XAddOptions.maxlen(maxStreamLength).approximateTrimming(true);
            redis.execute((RedisCallback<RecordId>) connection ->
                    ((RedisStreamCommands) connection).xAdd(record, options));
        } catch (Exception ex) {
            log.warn("Failed to publish {} event for {} {}: {}", STREAM, symbol, interval, ex.toString());
        }
    }
}
