package com.stockpredict.paper.service;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.paper.api.model.Account;
import com.stockpredict.paper.api.model.EquityPoint;
import com.stockpredict.paper.api.model.Portfolio;
import com.stockpredict.paper.api.model.Position;
import com.stockpredict.paper.config.PaperProperties;
import com.stockpredict.paper.data.AccountRepository;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.EquitySnapshotRepository;
import com.stockpredict.paper.data.OrderRepository;
import com.stockpredict.paper.data.PositionRepository;
import com.stockpredict.paper.data.PositionRow;
import com.stockpredict.paper.engine.Quote;
import com.stockpredict.paper.engine.QuoteProvider;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account lifecycle, position valuation and portfolio aggregation. */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final PositionRepository positionRepository;
    private final EquitySnapshotRepository equitySnapshotRepository;
    private final QuoteProvider quoteProvider;
    private final PaperProperties properties;

    public AccountService(AccountRepository accountRepository,
                          OrderRepository orderRepository,
                          PositionRepository positionRepository,
                          EquitySnapshotRepository equitySnapshotRepository,
                          QuoteProvider quoteProvider,
                          PaperProperties properties) {
        this.accountRepository = accountRepository;
        this.orderRepository = orderRepository;
        this.positionRepository = positionRepository;
        this.equitySnapshotRepository = equitySnapshotRepository;
        this.quoteProvider = quoteProvider;
        this.properties = properties;
    }

    /** Opens the virtual account; idempotent per user. */
    public Account open(UUID userId, Plan plan) {
        AccountRow account = accountRepository.findByUserId(userId)
                .orElseGet(() -> accountRepository.insert(userId, properties.getStartingCash()));
        return toAccount(account);
    }

    /** Resets cash/PnL, cancels working orders and clears positions (fills are kept for audit). */
    @Transactional
    public Account reset(UUID userId) {
        AccountRow account = accountRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Account"));
        // Same lock ordering as FillService: serialize reset against in-flight fills.
        accountRepository.findByIdForUpdate(account.id())
                .orElseThrow(() -> ApiException.notFound("Account"));
        orderRepository.cancelWorkingForAccount(account.id());
        positionRepository.deleteByAccount(account.id());
        accountRepository.reset(account.id());
        AccountRow reset = accountRepository.findById(account.id())
                .orElseThrow(() -> ApiException.notFound("Account"));
        return toAccount(reset);
    }

    public List<Position> positions(UUID userId) {
        AccountRow account = requireAccount(userId);
        List<PositionRow> positions = positionRepository.findByAccount(account.id());
        Map<String, Quote> quotes = quoteProvider.quotes(
                positions.stream().map(PositionRow::symbol).toList());
        return positions.stream()
                .map(position -> {
                    double price = markPrice(position, quotes);
                    return new Position()
                            .symbol(position.symbol())
                            .quantity(position.quantity())
                            .avgEntryPrice(position.avgEntryPrice())
                            .marketPrice(price)
                            .marketValue(position.quantity() * price)
                            .unrealizedPnl((price - position.avgEntryPrice()) * position.quantity())
                            .realizedPnl(position.realizedPnl());
                })
                .toList();
    }

    public Portfolio portfolio(UUID userId) {
        AccountRow account = requireAccount(userId);
        List<PositionRow> positions = positionRepository.findByAccount(account.id());
        Map<String, Quote> quotes = quoteProvider.quotes(
                positions.stream().map(PositionRow::symbol).toList());
        double positionsValue = 0;
        double unrealizedPnl = 0;
        for (PositionRow position : positions) {
            double price = markPrice(position, quotes);
            positionsValue += position.quantity() * price;
            unrealizedPnl += (price - position.avgEntryPrice()) * position.quantity();
        }
        List<EquityPoint> equityCurve = equitySnapshotRepository.findByAccount(account.id()).stream()
                .map(snapshot -> new EquityPoint()
                        .date(snapshot.snapshotDate())
                        .equity(snapshot.equity()))
                .toList();
        return new Portfolio()
                .accountId(account.id())
                .cash(account.cash())
                .positionsValue(positionsValue)
                .equity(account.cash() + positionsValue)
                .realizedPnl(account.realizedPnl())
                .unrealizedPnl(unrealizedPnl)
                .equityCurve(equityCurve);
    }

    AccountRow requireAccount(UUID userId) {
        return accountRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Account"));
    }

    private double markPrice(PositionRow position, Map<String, Quote> quotes) {
        Quote quote = quotes.get(position.symbol());
        return quote == null || !quote.valid() ? position.avgEntryPrice() : quote.last();
    }

    private Account toAccount(AccountRow row) {
        List<PositionRow> positions = positionRepository.findByAccount(row.id());
        Map<String, Quote> quotes = quoteProvider.quotes(
                positions.stream().map(PositionRow::symbol).toList());
        double positionsValue = positions.stream()
                .mapToDouble(position -> position.quantity() * markPrice(position, quotes))
                .sum();
        return new Account()
                .id(row.id())
                .cash(row.cash())
                .startingCash(row.startingCash())
                .equity(row.cash() + positionsValue)
                .createdAt(row.createdAt().atOffset(ZoneOffset.UTC));
    }
}
