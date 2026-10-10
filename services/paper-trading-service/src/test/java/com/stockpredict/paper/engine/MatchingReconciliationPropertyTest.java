package com.stockpredict.paper.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.paper.api.model.OrderRequest;
import com.stockpredict.paper.config.PaperProperties;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.FillRow;
import com.stockpredict.paper.data.InMemoryPaperStore;
import com.stockpredict.paper.data.PositionRow;
import com.stockpredict.paper.service.FillService;
import com.stockpredict.paper.service.OrderService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property test: random sequences of MARKET buys/sells over a fixed price
 * series must never violate the ledger invariants. No Docker, no Spring.
 */
class MatchingReconciliationPropertyTest {

    private static final double[] PRICES = {100, 102, 98, 105, 95, 110, 90, 101};
    private static final double STARTING_CASH = 100000;
    private static final UUID USER = UUID.randomUUID();

    record Action(boolean buy, double quantity) {
    }

    @Property(tries = 50)
    void ledgerInvariantsHoldForRandomMarketOrderSequences(@ForAll("actions") List<Action> actions) {
        InMemoryPaperStore store = new InMemoryPaperStore();
        AccountRow account = store.createAccount(USER, STARTING_CASH);
        store.watchlistRepository().replaceAll(USER, List.of("SYM"));

        PaperProperties properties = new PaperProperties();
        MarketClock clock = new MarketClock(Clock.fixed(Instant.parse("2026-06-10T14:00:00Z"), ZoneOffset.UTC));
        double[] price = {PRICES[0]};
        QuoteProvider quoteProvider = symbol -> Optional.of(
                new Quote(symbol, price[0], price[0], price[0], clock.now(), false));
        MatchingEngine engine = new MatchingEngine(store.orderRepository(), quoteProvider,
                new FillService(store.accountRepository(), store.orderRepository(), store.fillRepository(),
                        store.positionRepository(), clock),
                properties);
        OrderService orderService = new OrderService(store.accountRepository(), store.watchlistRepository(),
                store.orderRepository(), store.positionRepository(), quoteProvider, clock,
                new TradingCalendar(), properties);

        int step = 0;
        for (Action action : actions) {
            price[0] = PRICES[step % PRICES.length];
            step++;

            double held = store.position(account.id(), "SYM").map(PositionRow::quantity).orElse(0.0);
            double quantity = action.buy() ? action.quantity() : Math.min(action.quantity(), held);
            if (quantity > 1e-9) {
                OrderRequest request = new OrderRequest()
                        .symbol("SYM")
                        .side(action.buy() ? OrderRequest.SideEnum.BUY : OrderRequest.SideEnum.SELL)
                        .type(OrderRequest.TypeEnum.MARKET)
                        .quantity(quantity);
                try {
                    orderService.submit(USER, request, null);
                } catch (ApiException expectedRejection) {
                    // Submission-level checks (e.g. insufficient cash) may reject; that is fine.
                }
            }
            engine.matchWorkingOrders();

            assertInvariants(store, account.id(), price[0]);
        }
    }

    private void assertInvariants(InMemoryPaperStore store, UUID accountId, double currentPrice) {
        AccountRow account = store.account(accountId);
        assertThat(account.cash()).isGreaterThanOrEqualTo(0.0);

        double costBasis = 0;
        double marketValue = 0;
        double unrealized = 0;
        for (PositionRow position : store.positionRepository().findByAccount(accountId)) {
            assertThat(position.quantity()).isGreaterThanOrEqualTo(0.0);
            costBasis += position.quantity() * position.avgEntryPrice();
            marketValue += position.quantity() * currentPrice;
            unrealized += (currentPrice - position.avgEntryPrice()) * position.quantity();
        }

        // Conservation: cash + cost basis == starting cash + realized PnL.
        assertThat(account.cash() + costBasis)
                .isCloseTo(STARTING_CASH + account.realizedPnl(), within(1e-6));
        // Equity identity: cash + market value == starting cash + realized + unrealized.
        assertThat(account.cash() + marketValue)
                .isCloseTo(STARTING_CASH + account.realizedPnl() + unrealized, within(1e-6));

        // No order is ever filled twice and no non-positive fill exists.
        assertThat(store.fills().stream().map(FillRow::orderId).toList()).doesNotHaveDuplicates();
        assertThat(store.fills()).allSatisfy(fill -> assertThat(fill.quantity()).isGreaterThan(0.0));
    }

    @Provide
    Arbitrary<List<Action>> actions() {
        Arbitrary<Action> action = Combinators
                .combine(Arbitraries.of(true, false),
                        Arbitraries.integers().between(1, 50).map(Integer::doubleValue))
                .as(Action::new);
        return action.list().ofMinSize(1).ofMaxSize(40);
    }
}
