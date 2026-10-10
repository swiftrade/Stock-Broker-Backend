package com.stockpredict.paper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.paper.api.model.Watchlist;
import com.stockpredict.paper.config.PaperProperties;
import com.stockpredict.paper.data.AccountRepository;
import com.stockpredict.paper.data.AccountRow;
import com.stockpredict.paper.data.WatchlistRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WatchlistServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-10T14:00:00Z");

    private WatchlistRepository watchlistRepository;
    private AccountRepository accountRepository;
    private WatchlistService service;

    @BeforeEach
    void setUp() {
        watchlistRepository = mock(WatchlistRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new WatchlistService(watchlistRepository, accountRepository, new PaperProperties(),
                new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private AccountRow accountLockedUntil(Instant lockedUntil) {
        return new AccountRow(UUID.randomUUID(), USER, 100000, 100000, 0, "ACTIVE", lockedUntil, 0,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void freePlanRejectsMoreThanTenDistinctSymbols() {
        List<String> symbols = IntStream.range(0, 11).mapToObj(index -> "SYM" + index).toList();

        assertThatThrownBy(() -> service.replace(USER, Plan.FREE, symbols))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.PLAN_LIMIT));
        verify(watchlistRepository, never()).replaceAll(any(), any());
    }

    @Test
    void freePlanAllowsExactlyTenSymbols() {
        List<String> symbols = IntStream.range(0, 10).mapToObj(index -> "SYM" + index).toList();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of());
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.empty());

        Watchlist watchlist = service.replace(USER, Plan.FREE, symbols);

        assertThat(watchlist.getLimit()).isEqualTo(10);
        assertThat(watchlist.getSymbols()).hasSize(10);
        verify(watchlistRepository).replaceAll(eq(USER), any());
    }

    @Test
    void premiumPlanIsUnlimited() {
        List<String> symbols = IntStream.range(0, 25).mapToObj(index -> "SYM" + index).toList();
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of());
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.empty());

        Watchlist watchlist = service.replace(USER, Plan.PREMIUM, symbols);

        assertThat(watchlist.getLimit()).isZero();
        assertThat(watchlist.getPlan().getValue()).isEqualTo("PREMIUM");
        verify(watchlistRepository).replaceAll(eq(USER), any());
    }

    @Test
    void lockedWatchlistRejectsChangesWithLockedUntilDetail() {
        Instant lock = NOW.plus(Duration.ofDays(10));
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL"));
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.of(accountLockedUntil(lock)));

        assertThatThrownBy(() -> service.replace(USER, Plan.FREE, List.of("MSFT")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.extensions()).containsEntry("lockedUntil", lock.toString());
                });
        verify(watchlistRepository, never()).replaceAll(any(), any());
    }

    @Test
    void lockedWatchlistAllowsUnchangedSetWithoutRenewingLock() {
        Instant lock = NOW.plus(Duration.ofDays(10));
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL", "MSFT"));
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.of(accountLockedUntil(lock)));

        Watchlist watchlist = service.replace(USER, Plan.FREE, List.of("msft", "aapl"));

        assertThat(watchlist.getSymbols()).containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(watchlist.getLockedUntil()).isEqualTo(lock.atOffset(ZoneOffset.UTC));
        verify(watchlistRepository, never()).replaceAll(any(), any());
        verify(accountRepository, never()).setWatchlistLock(any(), any());
    }

    @Test
    void expiredLockAllowsChangesAndSetsNewLock() {
        Instant expired = NOW.minus(Duration.ofDays(1));
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL"));
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.of(accountLockedUntil(expired)));

        Watchlist watchlist = service.replace(USER, Plan.FREE, List.of("MSFT"));

        assertThat(watchlist.getSymbols()).containsExactly("MSFT");
        assertThat(watchlist.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC));
        verify(watchlistRepository).replaceAll(USER, List.of("MSFT"));
        verify(accountRepository).setWatchlistLock(USER, NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void deduplicatesUppercasesAndSortsSymbols() {
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of());
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.empty());

        Watchlist watchlist = service.replace(USER, Plan.FREE, List.of("aapl", "AAPL", "msft"));

        verify(watchlistRepository).replaceAll(USER, List.of("AAPL", "MSFT"));
        assertThat(watchlist.getSymbols()).containsExactly("AAPL", "MSFT");
    }

    @Test
    void invalidSymbolIsRejected() {
        assertThatThrownBy(() -> service.replace(USER, Plan.FREE, List.of("BAD SYMBOL")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void getReturnsPlanLimitAndSymbols() {
        when(watchlistRepository.findByUserId(USER)).thenReturn(List.of("AAPL", "MSFT"));
        when(accountRepository.findByUserId(USER)).thenReturn(Optional.empty());

        Watchlist watchlist = service.get(USER, Plan.FREE);

        assertThat(watchlist.getSymbols()).containsExactly("AAPL", "MSFT");
        assertThat(watchlist.getLimit()).isEqualTo(10);
        assertThat(watchlist.getLockedUntil()).isNull();
    }
}
