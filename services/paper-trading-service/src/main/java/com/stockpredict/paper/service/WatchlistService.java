package com.stockpredict.paper.service;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.paper.api.model.Watchlist;
import com.stockpredict.paper.config.PaperProperties;
import com.stockpredict.paper.data.AccountRepository;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.WatchlistRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watchlist reads/writes with plan limits, symbol validation and the 30-day
 * swap lock. A set that is unchanged is always allowed and does not renew the
 * lock.
 */
@Service
public class WatchlistService {

    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z][A-Z0-9.-]{0,14}$");

    private final WatchlistRepository watchlistRepository;
    private final AccountRepository accountRepository;
    private final PaperProperties properties;
    private final MarketClock clock;

    public WatchlistService(WatchlistRepository watchlistRepository,
                            AccountRepository accountRepository,
                            PaperProperties properties,
                            MarketClock clock) {
        this.watchlistRepository = watchlistRepository;
        this.accountRepository = accountRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public Watchlist get(UUID userId, Plan plan) {
        List<String> symbols = watchlistRepository.findByUserId(userId);
        AccountRow account = accountRepository.findByUserId(userId).orElse(null);
        return toModel(symbols, plan, account == null ? null : account.watchlistLockedUntil());
    }

    /**
     * Replaces the watchlist under the plan limit and the 30-day swap lock.
     *
     * <p>Plan-downgrade policy: a user who already holds more symbols than the
     * new plan allows may only shrink the set (never grow it) until it fits the
     * limit; an unchanged set is always allowed.
     */
    @Transactional
    public Watchlist replace(UUID userId, Plan plan, List<String> symbols) {
        List<String> normalized = normalize(symbols);
        int limit = limitFor(plan);
        List<String> current = watchlistRepository.findByUserId(userId);
        if (limit > 0 && normalized.size() > limit) {
            boolean shrinkingAfterDowngrade = current.size() > limit && normalized.size() < current.size();
            if (!shrinkingAfterDowngrade) {
                throw new ApiException(ErrorCode.PLAN_LIMIT,
                        plan + " plan allows at most " + limit + " watchlist symbols");
            }
        }

        AccountRow account = accountRepository.findByUserId(userId).orElse(null);
        Instant lockedUntil = account == null ? null : account.watchlistLockedUntil();

        if (new HashSet<>(current).equals(new HashSet<>(normalized))) {
            return toModel(current, plan, lockedUntil);
        }

        if (lockedUntil != null && clock.now().isBefore(lockedUntil)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Watchlist is locked until " + lockedUntil,
                    Map.of("lockedUntil", lockedUntil.toString()));
        }

        List<String> sorted = normalized.stream().sorted().toList();
        watchlistRepository.replaceAll(userId, sorted);
        Instant newLock = clock.now().plus(properties.getWatchlistSwapLockDays(), ChronoUnit.DAYS);
        if (account != null) {
            accountRepository.setWatchlistLock(userId, newLock);
            return toModel(sorted, plan, newLock);
        }
        return toModel(sorted, plan, null);
    }

    private List<String> normalize(List<String> symbols) {
        if (symbols == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "symbols is required");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        for (String raw : symbols) {
            String symbol = raw == null ? null : raw.trim().toUpperCase(java.util.Locale.ROOT);
            if (symbol == null || symbol.isEmpty() || !SYMBOL_PATTERN.matcher(symbol).matches()) {
                invalid.add(String.valueOf(raw));
            } else {
                unique.add(symbol);
            }
        }
        if (!invalid.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Invalid symbol(s): " + String.join(", ", invalid));
        }
        return new ArrayList<>(unique);
    }

    private int limitFor(Plan plan) {
        return plan == Plan.PREMIUM ? 0 : properties.getFreePlanWatchlistLimit();
    }

    private Watchlist toModel(List<String> symbols, Plan plan, Instant lockedUntil) {
        return new Watchlist()
                .symbols(symbols)
                .plan(com.stockpredict.paper.api.model.Plan.fromValue(plan == null ? "FREE" : plan.name()))
                .limit(limitFor(plan))
                .lockedUntil(lockedUntil == null ? null : lockedUntil.atOffset(ZoneOffset.UTC));
    }
}
