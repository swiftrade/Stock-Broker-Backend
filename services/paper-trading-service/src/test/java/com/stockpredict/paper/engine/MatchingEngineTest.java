package com.stockpredict.paper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.paper.config.PaperProperties;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.InMemoryPaperStore;
import com.stockpredict.paper.data.OrderRow;
import com.stockpredict.paper.data.PositionRow;
import com.stockpredict.paper.service.FillService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private static final UUID USER = UUID.randomUUID();

    private InMemoryPaperStore store;
    private AccountRow account;
    private Map<String, Quote> quotes;
    private MatchingEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryPaperStore();
        account = store.createAccount(USER, 100000);
        store.watchlistRepository().replaceAll(USER, List.of("AAPL"));
        quotes = new HashMap<>();
        PaperProperties properties = new PaperProperties();
        MarketClock clock = new MarketClock(Clock.fixed(Instant.parse("2026-06-10T14:00:00Z"), ZoneOffset.UTC));
        FillService fillService = new FillService(store.accountRepository(), store.orderRepository(),
                store.fillRepository(), store.positionRepository(), clock);
        engine = new MatchingEngine(store.orderRepository(),
                symbol -> Optional.ofNullable(quotes.get(symbol)), fillService, properties);
    }

    private OrderRow workingOrder(String side, String type, double quantity, Double limitPrice, Double stopPrice) {
        Instant now = Instant.parse("2026-06-10T14:00:00Z");
        OrderRow order = new OrderRow(UUID.randomUUID(), account.id(), "AAPL", side, type, quantity,
                limitPrice, stopPrice, "DAY", "NEW", 0, null, null, null, now, now, null);
        return store.orderRepository().insert(order);
    }

    private void quote(double bid, double ask, double last) {
        quotes.put("AAPL", new Quote("AAPL", bid, ask, last, Instant.parse("2026-06-10T14:00:00Z"), false));
    }

    private double cash() {
        return store.account(account.id()).cash();
    }

    @Test
    void marketBuyFillsAtSlippedPrice() {
        OrderRow order = workingOrder("BUY", "MARKET", 10, null, null);
        quote(99.9, 100.1, 100.0);

        assertThat(engine.matchWorkingOrders()).isEqualTo(1);

        assertThat(store.fills()).hasSize(1);
        assertThat(store.fills().get(0).price()).isCloseTo(100.05, within(1e-9));
        assertThat(store.order(order.id()).status()).isEqualTo("FILLED");
        assertThat(store.order(order.id()).avgFillPrice()).isCloseTo(100.05, within(1e-9));
        PositionRow position = store.position(account.id(), "AAPL").orElseThrow();
        assertThat(position.quantity()).isCloseTo(10.0, within(1e-9));
        assertThat(position.avgEntryPrice()).isCloseTo(100.05, within(1e-9));
        assertThat(cash()).isCloseTo(100000 - 10 * 100.05, within(1e-6));
    }

    @Test
    void marketSellFillsAtSlippedPriceAndRealizesPnl() {
        store.positionRepository().upsert(account.id(), new PositionRow("AAPL", 10, 100, 0));
        OrderRow order = workingOrder("SELL", "MARKET", 10, null, null);
        quote(99.9, 100.1, 100.0);

        assertThat(engine.matchWorkingOrders()).isEqualTo(1);

        assertThat(store.fills().get(0).price()).isCloseTo(99.95, within(1e-9));
        assertThat(store.position(account.id(), "AAPL")).isEmpty();
        assertThat(store.account(account.id()).realizedPnl()).isCloseTo(-0.5, within(1e-9));
        assertThat(cash()).isCloseTo(100000 + 10 * 99.95, within(1e-6));
        assertThat(store.order(order.id()).status()).isEqualTo("FILLED");
    }

    @Test
    void limitBuyFillsOnlyWhenAskAtOrBelowLimit() {
        OrderRow order = workingOrder("BUY", "LIMIT", 5, 100.0, null);
        quote(100.5, 100.6, 100.4);

        assertThat(engine.matchWorkingOrders()).isZero();
        assertThat(store.fills()).isEmpty();
        assertThat(store.order(order.id()).status()).isEqualTo("NEW");

        quote(99.5, 99.9, 99.7);
        assertThat(engine.matchWorkingOrders()).isEqualTo(1);
        assertThat(store.fills().get(0).price()).isEqualTo(100.0);
    }

    @Test
    void limitSellFillsOnlyWhenBidAtOrAboveLimit() {
        store.positionRepository().upsert(account.id(), new PositionRow("AAPL", 5, 90, 0));
        OrderRow order = workingOrder("SELL", "LIMIT", 5, 100.0, null);
        quote(99.8, 100.0, 99.9);

        assertThat(engine.matchWorkingOrders()).isZero();
        assertThat(store.order(order.id()).status()).isEqualTo("NEW");

        quote(100.0, 100.2, 100.1);
        assertThat(engine.matchWorkingOrders()).isEqualTo(1);
        assertThat(store.fills().get(0).price()).isEqualTo(100.0);
    }

    @Test
    void stopBuyTriggersWhenLastReachesStop() {
        OrderRow order = workingOrder("BUY", "STOP", 5, null, 104.0);
        quote(103.0, 103.2, 103.5);

        assertThat(engine.matchWorkingOrders()).isZero();
        assertThat(store.order(order.id()).status()).isEqualTo("NEW");

        quote(104.0, 104.2, 104.1);
        assertThat(engine.matchWorkingOrders()).isEqualTo(1);
        assertThat(store.fills().get(0).price()).isCloseTo(104.1 * 1.0005, within(1e-9));
    }

    @Test
    void stopSellTriggersWhenLastFallsToStop() {
        store.positionRepository().upsert(account.id(), new PositionRow("AAPL", 5, 100, 0));
        OrderRow order = workingOrder("SELL", "STOP", 5, null, 96.0);
        quote(96.5, 96.7, 96.6);

        assertThat(engine.matchWorkingOrders()).isZero();
        assertThat(store.order(order.id()).status()).isEqualTo("NEW");

        quote(95.3, 95.5, 95.4);
        assertThat(engine.matchWorkingOrders()).isEqualTo(1);
        assertThat(store.fills().get(0).price()).isCloseTo(95.4 * 0.9995, within(1e-9));
    }

    @Test
    void zeroOrOneSidedQuoteNeverFills() {
        OrderRow market = workingOrder("BUY", "MARKET", 10, null, null);
        OrderRow limit = workingOrder("BUY", "LIMIT", 10, 100.0, null);
        OrderRow stop = workingOrder("SELL", "STOP", 10, null, 50.0);
        store.positionRepository().upsert(account.id(), new PositionRow("AAPL", 10, 100, 0));
        quote(0, 0, 0);

        assertThat(engine.matchWorkingOrders()).isZero();

        assertThat(store.fills()).isEmpty();
        assertThat(store.order(market.id()).status()).isEqualTo("NEW");
        assertThat(store.order(limit.id()).status()).isEqualTo("NEW");
        assertThat(store.order(stop.id()).status()).isEqualTo("NEW");
        assertThat(cash()).isCloseTo(100000.0, within(1e-9));
        assertThat(store.position(account.id(), "AAPL").orElseThrow().quantity()).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void crossedQuoteNeverFills() {
        OrderRow order = workingOrder("BUY", "MARKET", 10, null, null);
        quote(101.0, 100.0, 100.5);

        assertThat(engine.matchWorkingOrders()).isZero();
        assertThat(store.fills()).isEmpty();
        assertThat(store.order(order.id()).status()).isEqualTo("NEW");
    }

    @Test
    void orderIsNeverFilledTwice() {
        OrderRow order = workingOrder("BUY", "MARKET", 10, null, null);
        quote(99.9, 100.1, 100.0);

        assertThat(engine.matchWorkingOrders()).isEqualTo(1);
        assertThat(engine.matchWorkingOrders()).isZero();

        assertThat(store.fills()).hasSize(1);
        assertThat(store.order(order.id()).status()).isEqualTo("FILLED");
    }

    @Test
    void buyRejectedAtFillWhenCashWouldGoNegative() {
        assertThat(store.accountRepository().updateCashWithVersion(account.id(), 50.0, 0)).isTrue();
        OrderRow order = workingOrder("BUY", "MARKET", 10, null, null);
        quote(99.9, 100.1, 100.0);

        assertThat(engine.matchWorkingOrders()).isZero();

        assertThat(store.fills()).isEmpty();
        assertThat(store.order(order.id()).status()).isEqualTo("REJECTED");
        assertThat(store.order(order.id()).rejectReason()).isEqualTo("insufficient cash");
        assertThat(cash()).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void sellRejectedWhenPositionIsInsufficient() {
        store.positionRepository().upsert(account.id(), new PositionRow("AAPL", 5, 100, 0));
        OrderRow order = workingOrder("SELL", "MARKET", 10, null, null);
        quote(99.9, 100.1, 100.0);

        assertThat(engine.matchWorkingOrders()).isZero();

        assertThat(store.fills()).isEmpty();
        assertThat(store.order(order.id()).status()).isEqualTo("REJECTED");
        assertThat(store.order(order.id()).rejectReason()).isEqualTo("insufficient position");
        assertThat(store.position(account.id(), "AAPL").orElseThrow().quantity()).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void averageEntryPriceIsWeightedOnAdds() {
        workingOrder("BUY", "LIMIT", 10, 100.0, null);
        quote(99.0, 99.9, 99.5);
        assertThat(engine.matchWorkingOrders()).isEqualTo(1);

        workingOrder("BUY", "LIMIT", 10, 120.0, null);
        quote(115.0, 119.0, 117.0);
        assertThat(engine.matchWorkingOrders()).isEqualTo(1);

        PositionRow position = store.position(account.id(), "AAPL").orElseThrow();
        assertThat(position.quantity()).isCloseTo(20.0, within(1e-9));
        assertThat(position.avgEntryPrice()).isCloseTo(110.0, within(1e-9));
        assertThat(cash()).isCloseTo(100000 - 10 * 100 - 10 * 120, within(1e-6));
    }

    @Test
    void partialSellRealizesPnlAndReducesQuantity() {
        store.positionRepository().upsert(account.id(), new PositionRow("AAPL", 10, 100, 0));
        workingOrder("SELL", "LIMIT", 4, 110.0, null);
        quote(110.0, 110.2, 110.1);

        assertThat(engine.matchWorkingOrders()).isEqualTo(1);

        PositionRow position = store.position(account.id(), "AAPL").orElseThrow();
        assertThat(position.quantity()).isCloseTo(6.0, within(1e-9));
        assertThat(position.avgEntryPrice()).isCloseTo(100.0, within(1e-9));
        assertThat(position.realizedPnl()).isCloseTo(40.0, within(1e-9));
        assertThat(store.account(account.id()).realizedPnl()).isCloseTo(40.0, within(1e-9));
        assertThat(cash()).isCloseTo(100000 + 4 * 110.0, within(1e-6));
    }

    @Test
    void fullSellDeletesPositionAndAccumulatesRealizedPnl() {
        store.positionRepository().upsert(account.id(), new PositionRow("AAPL", 10, 100, 0));
        workingOrder("SELL", "LIMIT", 10, 110.0, null);
        quote(110.0, 110.2, 110.1);

        assertThat(engine.matchWorkingOrders()).isEqualTo(1);

        assertThat(store.position(account.id(), "AAPL")).isEmpty();
        assertThat(store.account(account.id()).realizedPnl()).isCloseTo(100.0, within(1e-9));
        assertThat(cash()).isCloseTo(100000 + 10 * 110.0, within(1e-6));
    }
}
