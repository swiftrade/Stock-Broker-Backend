package com.stockpredict.paper.data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateful in-memory backing store for the Docker-free engine and property
 * tests. Semantics mirror the Jdbc implementations (optimistic versioning,
 * working-status guards, upserts). Repository views are exposed as delegates
 * because several interfaces share method signatures.
 */
public class InMemoryPaperStore {

    private final Map<UUID, AccountRow> accounts = new LinkedHashMap<>();
    private final Map<UUID, UUID> accountByUser = new HashMap<>();
    private final Map<UUID, List<String>> watchlists = new HashMap<>();
    private final Map<UUID, OrderRow> orders = new LinkedHashMap<>();
    private final List<FillRow> fills = new ArrayList<>();
    private final Map<String, PositionRow> positions = new HashMap<>();
    private final Map<String, EquitySnapshotRow> snapshots = new LinkedHashMap<>();

    private final AccountRepository accountRepository = new Accounts();
    private final WatchlistRepository watchlistRepository = new Watchlists();
    private final OrderRepository orderRepository = new Orders();
    private final FillRepository fillRepository = new Fills();
    private final PositionRepository positionRepository = new Positions();
    private final EquitySnapshotRepository equitySnapshotRepository = new Snapshots();

    public AccountRepository accountRepository() {
        return accountRepository;
    }

    public WatchlistRepository watchlistRepository() {
        return watchlistRepository;
    }

    public OrderRepository orderRepository() {
        return orderRepository;
    }

    public FillRepository fillRepository() {
        return fillRepository;
    }

    public PositionRepository positionRepository() {
        return positionRepository;
    }

    public EquitySnapshotRepository equitySnapshotRepository() {
        return equitySnapshotRepository;
    }

    public AccountRow createAccount(UUID userId, double startingCash) {
        UUID existing = accountByUser.get(userId);
        if (existing != null) {
            return accounts.get(existing);
        }
        AccountRow row = new AccountRow(UUID.randomUUID(), userId, startingCash, startingCash, 0,
                "ACTIVE", null, 0, Instant.parse("2026-01-01T00:00:00Z"));
        accounts.put(row.id(), row);
        accountByUser.put(userId, row.id());
        return row;
    }

    public AccountRow account(UUID accountId) {
        return accounts.get(accountId);
    }

    public OrderRow order(UUID orderId) {
        return orders.get(orderId);
    }

    public Optional<PositionRow> position(UUID accountId, String symbol) {
        return Optional.ofNullable(positions.get(positionKey(accountId, symbol)));
    }

    public List<FillRow> fills() {
        return List.copyOf(fills);
    }

    private class Accounts implements AccountRepository {

        @Override
        public Optional<AccountRow> findById(UUID accountId) {
            return Optional.ofNullable(accounts.get(accountId));
        }

        @Override
        public Optional<AccountRow> findByIdForUpdate(UUID accountId) {
            // Single-threaded test store: same semantics as findById.
            return findById(accountId);
        }

        @Override
        public Optional<AccountRow> findByUserId(UUID userId) {
            UUID accountId = accountByUser.get(userId);
            return accountId == null ? Optional.empty() : Optional.ofNullable(accounts.get(accountId));
        }

        @Override
        public List<AccountRow> findAll() {
            return List.copyOf(accounts.values());
        }

        @Override
        public AccountRow insert(UUID userId, double startingCash) {
            return createAccount(userId, startingCash);
        }

        @Override
        public boolean reset(UUID accountId) {
            AccountRow account = accounts.get(accountId);
            if (account == null) {
                return false;
            }
            accounts.put(accountId, new AccountRow(account.id(), account.userId(), account.startingCash(),
                    account.startingCash(), 0, account.status(), null, account.version() + 1,
                    account.createdAt()));
            return true;
        }

        @Override
        public boolean updateCashWithVersion(UUID accountId, double newCash, long expectedVersion) {
            AccountRow account = accounts.get(accountId);
            if (account == null || account.version() != expectedVersion) {
                return false;
            }
            accounts.put(accountId, new AccountRow(account.id(), account.userId(), newCash,
                    account.startingCash(), account.realizedPnl(), account.status(),
                    account.watchlistLockedUntil(), account.version() + 1, account.createdAt()));
            return true;
        }

        @Override
        public boolean updateCashAndRealizedWithVersion(UUID accountId, double newCash, double newRealizedPnl,
                                                        long expectedVersion) {
            AccountRow account = accounts.get(accountId);
            if (account == null || account.version() != expectedVersion) {
                return false;
            }
            accounts.put(accountId, new AccountRow(account.id(), account.userId(), newCash,
                    account.startingCash(), newRealizedPnl, account.status(),
                    account.watchlistLockedUntil(), account.version() + 1, account.createdAt()));
            return true;
        }

        @Override
        public void setWatchlistLock(UUID userId, Instant lockedUntil) {
            UUID accountId = accountByUser.get(userId);
            if (accountId == null) {
                return;
            }
            AccountRow account = accounts.get(accountId);
            accounts.put(accountId, new AccountRow(account.id(), account.userId(), account.cash(),
                    account.startingCash(), account.realizedPnl(), account.status(), lockedUntil,
                    account.version(), account.createdAt()));
        }
    }

    private class Watchlists implements WatchlistRepository {

        @Override
        public List<String> findByUserId(UUID userId) {
            return watchlists.getOrDefault(userId, List.of()).stream().sorted().toList();
        }

        @Override
        public void replaceAll(UUID userId, List<String> symbols) {
            watchlists.put(userId, new ArrayList<>(symbols));
        }

        @Override
        public List<String> symbolsForAccount(UUID accountId) {
            AccountRow account = accounts.get(accountId);
            return account == null ? List.of() : findByUserId(account.userId());
        }
    }

    private class Orders implements OrderRepository {

        @Override
        public OrderRow insert(OrderRow order) {
            orders.put(order.id(), order);
            return order;
        }

        @Override
        public Optional<OrderRow> findById(UUID orderId) {
            return Optional.ofNullable(orders.get(orderId));
        }

        @Override
        public Optional<OrderRow> findByIdempotencyKey(UUID accountId, String idempotencyKey) {
            return orders.values().stream()
                    .filter(order -> order.accountId().equals(accountId))
                    .filter(order -> Objects.equals(order.idempotencyKey(), idempotencyKey))
                    .findFirst();
        }

        @Override
        public List<OrderRow> findByAccount(UUID accountId, String status, int limit) {
            return orders.values().stream()
                    .filter(order -> order.accountId().equals(accountId))
                    .filter(order -> status == null || status.equals(order.status()))
                    .sorted(Comparator.comparing(OrderRow::submittedAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<OrderRow> findWorkingOrders() {
            return orders.values().stream()
                    .filter(InMemoryPaperStore::isWorking)
                    .sorted(Comparator.comparing(OrderRow::submittedAt))
                    .toList();
        }

        @Override
        public boolean updateStatus(UUID orderId, String status, String rejectReason) {
            OrderRow order = orders.get(orderId);
            if (order == null) {
                return false;
            }
            orders.put(orderId, withStatus(order, status, rejectReason));
            return true;
        }

        @Override
        public boolean cancel(UUID orderId) {
            OrderRow order = orders.get(orderId);
            if (order == null || !isWorking(order)) {
                return false;
            }
            orders.put(orderId, withStatus(order, "CANCELLED", order.rejectReason()));
            return true;
        }

        @Override
        public int cancelWorkingForAccount(UUID accountId) {
            int cancelled = 0;
            for (OrderRow order : new ArrayList<>(orders.values())) {
                if (order.accountId().equals(accountId) && isWorking(order)) {
                    orders.put(order.id(), withStatus(order, "CANCELLED", order.rejectReason()));
                    cancelled++;
                }
            }
            return cancelled;
        }

        @Override
        public boolean fillIfWorking(UUID orderId, double filledQuantity, double avgFillPrice) {
            OrderRow order = orders.get(orderId);
            if (order == null || !isWorking(order)) {
                return false;
            }
            orders.put(orderId, new OrderRow(order.id(), order.accountId(), order.symbol(), order.side(),
                    order.type(), order.quantity(), order.limitPrice(), order.stopPrice(), order.timeInForce(),
                    "FILLED", filledQuantity, avgFillPrice, order.rejectReason(), order.idempotencyKey(),
                    order.submittedAt(), Instant.now(), order.expiresAt()));
            return true;
        }

        @Override
        public int expireDue(Instant now) {
            int expired = 0;
            for (OrderRow order : new ArrayList<>(orders.values())) {
                if (isWorking(order) && "DAY".equals(order.timeInForce()) && order.expiresAt() != null
                        && !order.expiresAt().isAfter(now)) {
                    orders.put(order.id(), withStatus(order, "EXPIRED", order.rejectReason()));
                    expired++;
                }
            }
            return expired;
        }
    }

    private class Fills implements FillRepository {

        @Override
        public void insert(FillRow fill) {
            fills.add(fill);
        }
    }

    private class Positions implements PositionRepository {

        @Override
        public List<PositionRow> findByAccount(UUID accountId) {
            String prefix = accountId + "|";
            return positions.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue)
                    .sorted(Comparator.comparing(PositionRow::symbol))
                    .toList();
        }

        @Override
        public Optional<PositionRow> find(UUID accountId, String symbol) {
            return position(accountId, symbol);
        }

        @Override
        public void upsert(UUID accountId, PositionRow position) {
            positions.put(positionKey(accountId, position.symbol()), position);
        }

        @Override
        public void delete(UUID accountId, String symbol) {
            positions.remove(positionKey(accountId, symbol));
        }

        @Override
        public void deleteByAccount(UUID accountId) {
            positions.keySet().removeIf(key -> key.startsWith(accountId + "|"));
        }
    }

    private class Snapshots implements EquitySnapshotRepository {

        @Override
        public void upsert(UUID accountId, LocalDate date, double equity, double cash, double positionsValue,
                           double unrealizedPnl, double realizedPnl) {
            snapshots.put(snapshotKey(accountId, date),
                    new EquitySnapshotRow(date, equity, cash, positionsValue, unrealizedPnl, realizedPnl));
        }

        @Override
        public List<EquitySnapshotRow> findByAccount(UUID accountId) {
            String prefix = accountId + "|";
            return snapshots.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .map(Map.Entry::getValue)
                    .sorted(Comparator.comparing(EquitySnapshotRow::snapshotDate))
                    .toList();
        }
    }

    private static boolean isWorking(OrderRow order) {
        OrderStatus status = OrderStatus.from(order.status());
        return status != null && status.isWorking();
    }

    private static OrderRow withStatus(OrderRow order, String status, String rejectReason) {
        return new OrderRow(order.id(), order.accountId(), order.symbol(), order.side(), order.type(),
                order.quantity(), order.limitPrice(), order.stopPrice(), order.timeInForce(), status,
                order.filledQuantity(), order.avgFillPrice(), rejectReason, order.idempotencyKey(),
                order.submittedAt(), Instant.now(), order.expiresAt());
    }

    private static String positionKey(UUID accountId, String symbol) {
        return accountId + "|" + symbol;
    }

    private static String snapshotKey(UUID accountId, LocalDate date) {
        return accountId + "|" + date;
    }
}
