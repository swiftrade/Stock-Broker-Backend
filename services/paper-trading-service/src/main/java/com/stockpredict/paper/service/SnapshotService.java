package com.stockpredict.paper.service;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.paper.data.AccountRepository;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.EquitySnapshotRepository;
import com.stockpredict.paper.data.PositionRepository;
import com.stockpredict.paper.data.PositionRow;
import com.stockpredict.paper.engine.Quote;
import com.stockpredict.paper.engine.QuoteProvider;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Writes end-of-day equity snapshots for every account. */
@Service
public class SnapshotService {

    private final AccountRepository accountRepository;
    private final PositionRepository positionRepository;
    private final EquitySnapshotRepository equitySnapshotRepository;
    private final QuoteProvider quoteProvider;
    private final MarketClock clock;
    private final TradingCalendar calendar;

    public SnapshotService(AccountRepository accountRepository,
                           PositionRepository positionRepository,
                           EquitySnapshotRepository equitySnapshotRepository,
                           QuoteProvider quoteProvider,
                           MarketClock clock,
                           TradingCalendar calendar) {
        this.accountRepository = accountRepository;
        this.positionRepository = positionRepository;
        this.equitySnapshotRepository = equitySnapshotRepository;
        this.quoteProvider = quoteProvider;
        this.clock = clock;
        this.calendar = calendar;
    }

    /** Snapshots all accounts; no-op on non-trading days. Returns the number written. */
    public int snapshotAll() {
        LocalDate sessionDate = clock.todayEt();
        if (!calendar.isTradingDay(sessionDate)) {
            return 0;
        }
        // Collect every symbol once and mark with a single batch quote lookup
        // instead of accounts x positions sequential HTTP calls.
        List<AccountRow> accounts = accountRepository.findAll();
        Map<UUID, List<PositionRow>> positionsByAccount = new LinkedHashMap<>();
        Set<String> symbols = new LinkedHashSet<>();
        for (AccountRow account : accounts) {
            List<PositionRow> positions = positionRepository.findByAccount(account.id());
            positionsByAccount.put(account.id(), positions);
            positions.forEach(position -> symbols.add(position.symbol()));
        }
        Map<String, Quote> quotes = quoteProvider.quotes(symbols);

        int written = 0;
        for (AccountRow account : accounts) {
            double positionsValue = 0;
            double unrealizedPnl = 0;
            for (PositionRow position : positionsByAccount.get(account.id())) {
                Quote quote = quotes.get(position.symbol());
                double price = quote == null || !quote.valid()
                        ? position.avgEntryPrice()
                        : quote.last();
                positionsValue += position.quantity() * price;
                unrealizedPnl += (price - position.avgEntryPrice()) * position.quantity();
            }
            equitySnapshotRepository.upsert(account.id(), sessionDate,
                    account.cash() + positionsValue, account.cash(), positionsValue,
                    unrealizedPnl, account.realizedPnl());
            written++;
        }
        return written;
    }
}
