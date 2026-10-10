package com.stockpredict.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stockpredict.marketdata.api.model.Bar;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BarRepositoryTest {

    private static final Instant TS = Instant.parse("2026-10-09T00:00:00Z");

    private static ResultSet resultSet(double vwap, long tradeCount, boolean wasNull) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp("ts")).thenReturn(Timestamp.from(TS));
        when(rs.getDouble("open")).thenReturn(100.0);
        when(rs.getDouble("high")).thenReturn(102.0);
        when(rs.getDouble("low")).thenReturn(99.0);
        when(rs.getDouble("close")).thenReturn(101.0);
        when(rs.getLong("volume")).thenReturn(1_000_000L);
        when(rs.getDouble("vwap")).thenReturn(vwap);
        when(rs.getLong("trade_count")).thenReturn(tradeCount);
        when(rs.wasNull()).thenReturn(wasNull);
        return rs;
    }

    @Test
    void mapsNumericColumnsWithoutGetObjectConversion() throws Exception {
        // pgjdbc cannot convert NUMERIC to Double via getObject; the mapper must
        // use getDouble + wasNull instead.
        Bar bar = BarRepository.mapRow(resultSet(100.5, 42L, false));

        assertThat(bar.getClose()).isEqualTo(101.0);
        assertThat(bar.getVwap()).isEqualTo(100.5);
        assertThat(bar.getTradeCount()).isEqualTo(42L);
        assertThat(bar.getTs().toInstant()).isEqualTo(TS);
    }

    @Test
    void mapsNullVwapAndTradeCountToNull() throws Exception {
        Bar bar = BarRepository.mapRow(resultSet(0.0, 0L, true));

        assertThat(bar.getVwap()).isNull();
        assertThat(bar.getTradeCount()).isNull();
        assertThat(bar.getClose()).isEqualTo(101.0);
    }
}
