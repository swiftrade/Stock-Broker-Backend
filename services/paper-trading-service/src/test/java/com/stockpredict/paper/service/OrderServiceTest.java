package com.stockpredict.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.paper.api.model.Order;
import com.stockpredict.paper.api.model.OrderRequest;
import com.stockpredict.paper.config.PaperProperties;
import com.stockpredict.paper.data.AccountRepository;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.OrderRepository;
import com.stockpredict.paper.data.OrderRow;
import com.stockpredict.paper.data.PositionRepository;
import com.stockpredict.paper.data.PositionRow;
import com.stockpredict.paper.data.WatchlistRepository;
import com.stockpredict.paper.engine.Quote;
import com.stockpredict.paper.engine.QuoteProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Instant MARKET_OPEN = Instant.parse("2026-06-10T14:00:00Z");

    private AccountRepository accountRepository;
    private WatchlistRepository watchlistRepository;
    private OrderRepository orderRepository;
    private PositionRepository positionRepository;
    private QuoteProvider quoteProvider;
    private PaperProperties properties;
    private OrderService service;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        watchlistRepository = mock(WatchlistRepository.class);
        orderRepository = mock(OrderRepository.class);
        positionRepository = mock(PositionRepository.class);
        quoteProvider = mock(QuoteProvider.class);
        properties = new PaperProperties();
        service = serviceAt(MARKET_OPEN);
    }

    private OrderService serviceAt(Instant instant) {
        return new OrderService(accountRepository, watchlistRepository, orderRepository,
                positionRepository, quoteProvider,
                new MarketClock(Clock.fixed(instant, ZoneOffset.UTC)), new TradingCalendar(), properties);
    }

    private AccountRow account() {
        return new AccountRow(ACCOUNT_ID, USER, 100000, 100000, 0, "ACTIVE", null, 0,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void stubAccount() {
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.of(account()));
    }

    private OrderRequest request(OrderRequest.SideEnum side, OrderRequest.TypeEnum type, double quantity) {
        return new OrderRequest().symbol("AAPL").side(side).type(type).quantity(quantity);
    }

    @Test
    void idempotencyKeyReturnsOriginalOrderWithoutInserting() {
        stubAccount();
        OrderRow existing = new OrderRow(UUID.randomUUID(), ACCOUNT_ID, "AAPL", "BUY", "LIMIT", 5.0,
                100.0, null, "DAY", "NEW", 0, null, null, "key-1", MARKET_OPEN, MARKET_OPEN, null);
        when(orderRepository.findByIdempotencyKey(ACCOUNT_ID, "key-1")).thenReturn(Optional.of(existing));

        Order order = service.submit(USER, request(OrderRequest.SideEnum.BUY, OrderRequest.TypeEnum.LIMIT, 5)
                .limitPrice(100.0), "key-1");

        assertThat(order.getId()).isEqualTo(existing.id());
        assertThat(order.getStatus()).isEqualTo(Order.StatusEnum.NEW);
        verify(orderRepository, never()).insert(any());
    }

    @Test
    void marketOrderRejectedWhenMarketClosed() {
        stubAccount();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL"));
        service = serviceAt(Instant.parse("2026-06-10T02:00:00Z"));

        assertThatThrownBy(() -> service.submit(USER, request(OrderRequest.SideEnum.BUY,
                OrderRequest.TypeEnum.MARKET, 1), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.MARKET_CLOSED));
        verify(orderRepository, never()).insert(any());
    }

    @Test
    void limitOrderRequiresLimitPrice() {
        assertThatThrownBy(() -> service.submit(USER, request(OrderRequest.SideEnum.BUY,
                OrderRequest.TypeEnum.LIMIT, 1), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void stopOrderRequiresStopPrice() {
        assertThatThrownBy(() -> service.submit(USER, request(OrderRequest.SideEnum.SELL,
                OrderRequest.TypeEnum.STOP, 1), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void buyRejectedWhenCashInsufficient() {
        stubAccount();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL"));
        when(quoteProvider.quote("AAPL")).thenReturn(Optional.of(
                new Quote("AAPL", 100, 100, 100, MARKET_OPEN, false)));

        assertThatThrownBy(() -> service.submit(USER, request(OrderRequest.SideEnum.BUY,
                OrderRequest.TypeEnum.MARKET, 2000), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH));
        verify(orderRepository, never()).insert(any());
    }

    @Test
    void buyRejectedWhenSymbolNotInWatchlist() {
        stubAccount();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of());
        when(positionRepository.find(ACCOUNT_ID, "AAPL")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(USER, request(OrderRequest.SideEnum.BUY,
                OrderRequest.TypeEnum.LIMIT, 1).limitPrice(100.0), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SYMBOL_NOT_IN_WATCHLIST));
    }

    @Test
    void sellOfHeldSymbolAllowedAfterWatchlistRemoval() {
        stubAccount();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of());
        when(positionRepository.find(ACCOUNT_ID, "AAPL")).thenReturn(Optional.of(
                new PositionRow("AAPL", 5, 90, 0)));

        Order order = service.submit(USER, request(OrderRequest.SideEnum.SELL,
                OrderRequest.TypeEnum.LIMIT, 5).limitPrice(110.0), null);

        assertThat(order.getSide()).isEqualTo(Order.SideEnum.SELL);
        assertThat(order.getStatus()).isEqualTo(Order.StatusEnum.NEW);
        verify(orderRepository).insert(any(OrderRow.class));
    }

    @Test
    void marketBuyAcceptedDuringMarketHoursWithDayExpiryAtClose() {
        stubAccount();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL"));
        when(quoteProvider.quote("AAPL")).thenReturn(Optional.of(
                new Quote("AAPL", 100, 100, 100, MARKET_OPEN, false)));

        Order order = service.submit(USER, request(OrderRequest.SideEnum.BUY,
                OrderRequest.TypeEnum.MARKET, 10), null);

        assertThat(order.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(order.getQuantity()).isCloseTo(10.0, within(1e-9));
        assertThat(order.getStatus()).isEqualTo(Order.StatusEnum.NEW);

        ArgumentCaptor<OrderRow> captor = ArgumentCaptor.forClass(OrderRow.class);
        verify(orderRepository).insert(captor.capture());
        // 16:00 America/New_York on 2026-06-10 == 20:00 UTC.
        assertThat(captor.getValue().expiresAt()).isEqualTo(Instant.parse("2026-06-10T20:00:00Z"));
    }

    @Test
    void gtcOrderHasNoExpiry() {
        stubAccount();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL"));
        when(quoteProvider.quote("AAPL")).thenReturn(Optional.of(
                new Quote("AAPL", 100, 100, 100, MARKET_OPEN, false)));

        service.submit(USER, request(OrderRequest.SideEnum.BUY, OrderRequest.TypeEnum.LIMIT, 1)
                .limitPrice(100.0).timeInForce(OrderRequest.TimeInForceEnum.GTC), null);

        ArgumentCaptor<OrderRow> captor = ArgumentCaptor.forClass(OrderRow.class);
        verify(orderRepository).insert(captor.capture());
        assertThat(captor.getValue().expiresAt()).isNull();
        assertThat(captor.getValue().timeInForce()).isEqualTo("GTC");
    }
}
