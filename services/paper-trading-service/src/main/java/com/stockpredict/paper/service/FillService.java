package com.stockpredict.paper.service;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.paper.data.AccountRepository;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.FillRepository;
import com.stockpredict.paper.data.FillRow;
import com.stockpredict.paper.data.OrderRepository;
import com.stockpredict.paper.data.OrderRow;
import com.stockpredict.paper.data.PositionRepository;
import com.stockpredict.paper.data.PositionRow;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a fill to the ledger in one transaction: order state, fill row,
 * position and account cash/realized PnL. Optimistic account versioning plus
 * the working-status guard make fills idempotent and cash-safe.
 */
@Service
public class FillService {

    private static final double EPSILON = 1e-9;

    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final FillRepository fillRepository;
    private final PositionRepository positionRepository;
    private final MarketClock clock;

    public FillService(AccountRepository accountRepository,
                       OrderRepository orderRepository,
                       FillRepository fillRepository,
                       PositionRepository positionRepository,
                       MarketClock clock) {
        this.accountRepository = accountRepository;
        this.orderRepository = orderRepository;
        this.fillRepository = fillRepository;
        this.positionRepository = positionRepository;
        this.clock = clock;
    }

    public boolean applyFill(OrderRow order, double price, double slippageBps) {
        return applyFill(order, price, slippageBps, null);
    }

    @Transactional
    public boolean applyFill(OrderRow order, double price, double slippageBps, Instant quoteTs) {
        double quantity = order.remainingQuantity();
        if (quantity <= EPSILON) {
            return false;
        }
        boolean buy = "BUY".equals(order.side());
        // Pessimistic per-account serialization: the account row lock is held
        // for the whole transaction, so concurrent fills (multi-replica poller)
        // cannot interleave read-modify-write on cash or positions.
        AccountRow account = accountRepository.findByIdForUpdate(order.accountId())
                .orElseThrow(() -> ApiException.notFound("Account"));
        PositionRow position = positionRepository.find(order.accountId(), order.symbol()).orElse(null);

        if (buy) {
            if (account.cash() + EPSILON < quantity * price) {
                orderRepository.updateStatus(order.id(), "REJECTED", "insufficient cash");
                return false;
            }
        } else {
            double held = position == null ? 0 : position.quantity();
            if (held + EPSILON < quantity) {
                orderRepository.updateStatus(order.id(), "REJECTED", "insufficient position");
                return false;
            }
        }

        // Status guard: a non-working order can never be filled twice.
        if (!orderRepository.fillIfWorking(order.id(), quantity, price)) {
            return false;
        }

        fillRepository.insert(new FillRow(order.id(), order.accountId(), order.symbol(), quantity, price,
                slippageBps, clock.now(), quoteTs));

        double realized = 0;
        if (buy) {
            double oldQuantity = position == null ? 0 : position.quantity();
            double oldAverage = position == null ? 0 : position.avgEntryPrice();
            double newQuantity = oldQuantity + quantity;
            double newAverage = (oldQuantity * oldAverage + quantity * price) / newQuantity;
            positionRepository.upsert(order.accountId(), new PositionRow(order.symbol(), newQuantity, newAverage,
                    position == null ? 0 : position.realizedPnl()));
        } else {
            realized = (price - position.avgEntryPrice()) * quantity;
            double newQuantity = position.quantity() - quantity;
            if (newQuantity <= EPSILON) {
                positionRepository.delete(order.accountId(), order.symbol());
            } else {
                positionRepository.upsert(order.accountId(), new PositionRow(order.symbol(), newQuantity,
                        position.avgEntryPrice(), position.realizedPnl() + realized));
            }
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            AccountRow current = attempt == 0
                    ? account
                    : accountRepository.findById(order.accountId())
                            .orElseThrow(() -> ApiException.notFound("Account"));
            double newCash = current.cash() + (buy ? -quantity * price : quantity * price);
            double newRealized = current.realizedPnl() + (buy ? 0 : realized);
            if (accountRepository.updateCashAndRealizedWithVersion(
                    order.accountId(), newCash, newRealized, current.version())) {
                return true;
            }
        }
        throw new ApiException(ErrorCode.CONFLICT, "Account was modified concurrently");
    }
}
