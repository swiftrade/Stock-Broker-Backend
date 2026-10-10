package com.stockpredict.paper.service;

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
import com.stockpredict.paper.data.OrderStatus;
import com.stockpredict.paper.data.PositionRepository;
import com.stockpredict.paper.data.WatchlistRepository;
import com.stockpredict.paper.engine.Quote;
import com.stockpredict.paper.engine.QuoteProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Order submission, listing and cancellation. */
@Service
public class OrderService {

    private static final double EPSILON = 1e-9;
    /** Basis points per unit fraction (1 bp = 0.0001). */
    private static final double BPS_PER_UNIT = 10_000.0;
    private static final Set<String> ORDER_STATUSES = java.util.Arrays.stream(OrderStatus.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final AccountRepository accountRepository;
    private final WatchlistRepository watchlistRepository;
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final QuoteProvider quoteProvider;
    private final MarketClock clock;
    private final TradingCalendar calendar;
    private final PaperProperties properties;

    public OrderService(AccountRepository accountRepository,
                        WatchlistRepository watchlistRepository,
                        OrderRepository orderRepository,
                        PositionRepository positionRepository,
                        QuoteProvider quoteProvider,
                        MarketClock clock,
                        TradingCalendar calendar,
                        PaperProperties properties) {
        this.accountRepository = accountRepository;
        this.watchlistRepository = watchlistRepository;
        this.orderRepository = orderRepository;
        this.positionRepository = positionRepository;
        this.quoteProvider = quoteProvider;
        this.clock = clock;
        this.calendar = calendar;
        this.properties = properties;
    }

    public Order submit(UUID userId, OrderRequest request, String idempotencyKey) {
        validate(request);
        AccountRow account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Account"));

        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
        if (key != null) {
            Optional<OrderRow> existing = orderRepository.findByIdempotencyKey(account.id(), key);
            if (existing.isPresent()) {
                return toModel(existing.get());
            }
        }

        String symbol = request.getSymbol().trim().toUpperCase(Locale.ROOT);
        boolean buy = request.getSide() == OrderRequest.SideEnum.BUY;
        boolean inWatchlist = watchlistRepository.findByUserId(userId).contains(symbol);
        boolean held = positionRepository.find(account.id(), symbol)
                .map(position -> position.quantity() > EPSILON)
                .orElse(false);
        if (!inWatchlist && !(!buy && held)) {
            throw new ApiException(ErrorCode.SYMBOL_NOT_IN_WATCHLIST,
                    symbol + " is not in the watchlist");
        }

        boolean market = request.getType() == OrderRequest.TypeEnum.MARKET;
        if (market && !properties.isAllowAfterHours() && !calendar.isMarketOpen(clock.now())) {
            throw new ApiException(ErrorCode.MARKET_CLOSED, "Market is closed for market orders");
        }

        if (buy) {
            double reference = quoteProvider.quote(symbol)
                    .map(Quote::last)
                    .orElseGet(() -> request.getLimitPrice() != null ? request.getLimitPrice()
                            : request.getStopPrice() != null ? request.getStopPrice() : 0.0);
            if (reference > 0) {
                double required = request.getQuantity() * reference
                        * (1 + properties.getSlippageBps() / BPS_PER_UNIT);
                if (required > account.cash() + EPSILON) {
                    throw new ApiException(ErrorCode.INSUFFICIENT_CASH,
                            "Order requires " + required + " but only " + account.cash() + " is available");
                }
            }
        }

        String timeInForce = request.getTimeInForce() == null
                ? "DAY"
                : request.getTimeInForce().name();
        Instant now = clock.now();
        OrderRow order = new OrderRow(
                UUID.randomUUID(),
                account.id(),
                symbol,
                request.getSide().name(),
                request.getType().name(),
                request.getQuantity(),
                request.getLimitPrice(),
                request.getStopPrice(),
                timeInForce,
                "NEW",
                0,
                null,
                null,
                key,
                now,
                now,
                "DAY".equals(timeInForce) ? dayOrderExpiry() : null);
        orderRepository.insert(order);
        return toModel(order);
    }

    public List<Order> list(UUID userId, String status, int limit) {
        if (status != null && !ORDER_STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown order status: " + status);
        }
        AccountRow account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Account"));
        return orderRepository.findByAccount(account.id(), status, limit).stream()
                .map(this::toModel)
                .toList();
    }

    public void cancel(UUID userId, UUID orderId) {
        AccountRow account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Account"));
        OrderRow order = orderRepository.findById(orderId)
                .filter(row -> row.accountId().equals(account.id()))
                .orElseThrow(() -> ApiException.notFound("Order"));
        OrderStatus status = OrderStatus.from(order.status());
        if (status == null || !status.isWorking()) {
            throw new ApiException(ErrorCode.CONFLICT, "Order is no longer cancelable");
        }
        if (!orderRepository.cancel(orderId)) {
            throw new ApiException(ErrorCode.CONFLICT, "Order is no longer cancelable");
        }
    }

    private void validate(OrderRequest request) {
        if (request == null || request.getSymbol() == null || request.getSymbol().isBlank()
                || request.getSide() == null || request.getType() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "symbol, side and type are required");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "quantity must be positive");
        }
        if (request.getType() == OrderRequest.TypeEnum.LIMIT && request.getLimitPrice() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "LIMIT orders require limitPrice");
        }
        if (request.getType() == OrderRequest.TypeEnum.STOP && request.getStopPrice() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "STOP orders require stopPrice");
        }
    }

    /**
     * DAY orders submitted outside regular hours are queued for the next
     * session and expire at that session's close (they are never left working
     * indefinitely). See README 10.2.
     */
    private Instant dayOrderExpiry() {
        LocalDate today = clock.todayEt();
        ZonedDateTime close = calendar.closeTime(today);
        if (!calendar.isTradingDay(today) || !clock.now().isBefore(close.toInstant())) {
            close = calendar.closeTime(calendar.nextTradingDay(today));
        }
        return close.toInstant();
    }

    private Order toModel(OrderRow row) {
        return new Order()
                .id(row.id())
                .accountId(row.accountId())
                .symbol(row.symbol())
                .side(Order.SideEnum.fromValue(row.side()))
                .type(Order.TypeEnum.fromValue(row.type()))
                .quantity(row.quantity())
                .limitPrice(row.limitPrice())
                .stopPrice(row.stopPrice())
                .timeInForce(Order.TimeInForceEnum.fromValue(row.timeInForce()))
                .status(Order.StatusEnum.fromValue(row.status()))
                .filledQuantity(row.filledQuantity())
                .avgFillPrice(row.avgFillPrice())
                .rejectReason(row.rejectReason())
                .submittedAt(row.submittedAt().atOffset(ZoneOffset.UTC))
                .updatedAt(row.updatedAt().atOffset(ZoneOffset.UTC));
    }
}
