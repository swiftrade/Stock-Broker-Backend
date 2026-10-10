package com.stockpredict.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.config.MarketDataProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

class BarsUpdatedPublisherTest {

    private static final Instant NOW = Instant.parse("2026-06-10T15:00:00Z");

    @Test
    void publishesContractPayloadToTheStreamWithTrimming() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        stubExecute(redis, connection);
        BarsUpdatedPublisher publisher = new BarsUpdatedPublisher(redis, new ObjectMapper(), properties());

        publisher.publish("AAPL", "1d", List.of(bar(NOW.minusSeconds(600), 100.0)), "polygon");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<MapRecord<byte[], byte[], byte[]>> recordCaptor =
                ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> optionsCaptor = ArgumentCaptor.forClass(XAddOptions.class);
        verify((RedisStreamCommands) connection).xAdd(recordCaptor.capture(), optionsCaptor.capture());

        assertThat(new String(recordCaptor.getValue().getStream(), StandardCharsets.UTF_8))
                .isEqualTo("bars.updated");
        Map<String, String> fields = recordCaptor.getValue().getValue().entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> new String(entry.getKey(), StandardCharsets.UTF_8),
                        entry -> new String(entry.getValue(), StandardCharsets.UTF_8)));
        JsonNode payload = new ObjectMapper().readTree(fields.get("payload"));
        assertThat(payload.path("type").asText()).isEqualTo("bars.updated");
        assertThat(payload.path("symbol").asText()).isEqualTo("AAPL");
        assertThat(payload.path("interval").asText()).isEqualTo("1d");
        assertThat(payload.path("barCount").asInt()).isEqualTo(1);
        assertThat(payload.path("source").asText()).isEqualTo("polygon");
        assertThat(payload.path("eventId").asText()).isNotBlank();
        assertThat(payload.path("lastBarTs").asText()).isNotBlank();

        assertThat(optionsCaptor.getValue().getMaxlen()).isEqualTo(100_000L);
        assertThat(optionsCaptor.getValue().isApproximateTrimming()).isTrue();
    }

    @Test
    void redisFailureIsSwallowed() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnection connection = mock(RedisConnection.class);
        stubExecute(redis, connection);
        when(((RedisStreamCommands) connection).xAdd(any(MapRecord.class), any(XAddOptions.class)))
                .thenThrow(new IllegalStateException("redis down"));
        BarsUpdatedPublisher publisher = new BarsUpdatedPublisher(redis, new ObjectMapper(), properties());

        assertThatCode(() -> publisher.publish("AAPL", "1d", List.of(bar(NOW, 100.0)), "polygon"))
                .doesNotThrowAnyException();
    }

    private static void stubExecute(StringRedisTemplate redis, RedisConnection connection) {
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisCallback<RecordId>>any()))
                .thenAnswer(invocation -> {
                    RedisCallback<RecordId> callback = invocation.getArgument(0);
                    return callback.doInRedis(connection);
                });
    }

    private static MarketDataProperties properties() {
        MarketDataProperties properties = new MarketDataProperties();
        properties.setStreamMaxLength(100_000L);
        return properties;
    }

    private static Bar bar(Instant ts, double close) {
        return new Bar()
                .ts(OffsetDateTime.ofInstant(ts, ZoneOffset.UTC))
                .open(close - 0.5)
                .high(close + 0.5)
                .low(close - 1.0)
                .close(close)
                .volume(1_000L);
    }
}
