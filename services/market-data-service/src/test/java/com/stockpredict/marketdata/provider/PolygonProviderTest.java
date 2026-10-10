package com.stockpredict.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PolygonProviderTest {

    private static final String BASE = "https://api.polygon.io";
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 5);

    private MockRestServiceServer server;
    private PolygonProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        MarketClock clock = new MarketClock(Clock.fixed(Instant.parse("2026-06-10T15:00:00Z"), ZoneOffset.UTC));
        provider = new PolygonProvider(builder.build(), "test-key", clock);
    }

    @Test
    void parsesDailyAggregates() {
        server.expect(requestTo(BASE + "/v2/aggs/ticker/AAPL/range/1/day/2026-06-01/2026-06-05"
                        + "?adjusted=true&sort=asc&limit=50000&apiKey=test-key"))
                .andRespond(withSuccess("""
                        {"results":[
                          {"t":1780358400000,"o":100.0,"h":102.0,"l":99.0,"c":101.0,"v":1000000,"vw":100.5,"n":5000}
                        ],"status":"OK"}
                        """, MediaType.APPLICATION_JSON));

        List<Bar> bars = provider.fetchDailyBars("AAPL", FROM, TO);

        assertThat(bars).hasSize(1);
        Bar bar = bars.get(0);
        assertThat(bar.getTs()).isEqualTo(Instant.ofEpochMilli(1780358400000L).atOffset(ZoneOffset.UTC));
        assertThat(bar.getOpen()).isEqualTo(100.0);
        assertThat(bar.getHigh()).isEqualTo(102.0);
        assertThat(bar.getLow()).isEqualTo(99.0);
        assertThat(bar.getClose()).isEqualTo(101.0);
        assertThat(bar.getVolume()).isEqualTo(1_000_000L);
        assertThat(bar.getVwap()).isEqualTo(100.5);
        assertThat(bar.getTradeCount()).isEqualTo(5000L);
        server.verify();
    }

    @Test
    void followsNextUrlPagination() {
        server.expect(requestTo(BASE + "/v2/aggs/ticker/AAPL/range/1/day/2026-06-01/2026-06-05"
                        + "?adjusted=true&sort=asc&limit=50000&apiKey=test-key"))
                .andRespond(withSuccess("""
                        {"results":[{"t":1780358400000,"o":1,"h":2,"l":0.5,"c":1.5,"v":10}],
                         "next_url":"https://api.polygon.io/v2/aggs/ticker/AAPL/range/1/day/2026-06-01/2026-06-05?cursor=abc",
                         "status":"OK"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.polygon.io/v2/aggs/ticker/AAPL/range/1/day/2026-06-01/2026-06-05"
                        + "?cursor=abc&apiKey=test-key"))
                .andRespond(withSuccess("""
                        {"results":[{"t":1780444800000,"o":2,"h":3,"l":1.5,"c":2.5,"v":20}],"status":"OK"}
                        """, MediaType.APPLICATION_JSON));

        List<Bar> bars = provider.fetchDailyBars("AAPL", FROM, TO);

        assertThat(bars).hasSize(2);
        server.verify();
    }

    @Test
    void parsesIntradayAggregates() {
        server.expect(requestTo(BASE + "/v2/aggs/ticker/AAPL/range/5/minute/2026-06-01/2026-06-05"
                        + "?adjusted=true&sort=asc&limit=50000&apiKey=test-key"))
                .andRespond(withSuccess("""
                        {"results":[{"t":1780358400000,"o":100,"h":101,"l":99,"c":100.5,"v":500}],"status":"OK"}
                        """, MediaType.APPLICATION_JSON));

        List<Bar> bars = provider.fetchIntradayBars("AAPL", FROM, TO);

        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).getVolume()).isEqualTo(500L);
        server.verify();
    }

    @Test
    void searchMapsTickerResults() {
        server.expect(requestTo(BASE + "/v3/reference/tickers?search=apple&active=true&limit=5&apiKey=test-key"))
                .andRespond(withSuccess("""
                        {"results":[{"ticker":"AAPL","name":"Apple Inc.","primary_exchange":"XNAS"}]}
                        """, MediaType.APPLICATION_JSON));

        List<SymbolMatch> matches = provider.search("apple", 5);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getSymbol()).isEqualTo("AAPL");
        assertThat(matches.get(0).getName()).isEqualTo("Apple Inc.");
        assertThat(matches.get(0).getExchange()).isEqualTo("XNAS");
        server.verify();
    }

    @Test
    void quoteCombinesNbboAndLastTrade() {
        server.expect(requestTo(BASE + "/v2/last/nbbo/AAPL?apiKey=test-key"))
                .andRespond(withSuccess("""
                        {"results":{"bid":100.0,"ask":100.2,"t":1780358400000},"status":"OK"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/last/trade/AAPL?apiKey=test-key"))
                .andRespond(withSuccess("""
                        {"results":{"p":100.1,"t":1780358401000},"status":"OK"}
                        """, MediaType.APPLICATION_JSON));

        Optional<QuoteSnapshot> quote = provider.fetchQuote("AAPL");

        assertThat(quote).isPresent();
        assertThat(quote.get().bid()).isEqualTo(100.0);
        assertThat(quote.get().ask()).isEqualTo(100.2);
        assertThat(quote.get().last()).isEqualTo(100.1);
        assertThat(quote.get().ts()).isEqualTo(Instant.ofEpochMilli(1780358400000L).atOffset(ZoneOffset.UTC));
        server.verify();
    }

    @Test
    void quoteReturnsEmptyWhenBothEndpointsFail() {
        server.expect(requestTo(BASE + "/v2/last/nbbo/AAPL?apiKey=test-key")).andRespond(withServerError());
        server.expect(requestTo(BASE + "/v2/last/trade/AAPL?apiKey=test-key")).andRespond(withServerError());

        assertThat(provider.fetchQuote("AAPL")).isEmpty();
    }

    @Test
    void aggregateErrorsPropagateForRetry() {
        server.expect(requestTo(BASE + "/v2/aggs/ticker/AAPL/range/1/day/2026-06-01/2026-06-05"
                        + "?adjusted=true&sort=asc&limit=50000&apiKey=test-key"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.fetchDailyBars("AAPL", FROM, TO))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void sourceIsPolygon() {
        assertThat(provider.source()).isEqualTo("polygon");
    }
}
