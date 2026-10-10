package com.stockpredict.paper.web;

import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.security.JwtClaims;
import com.stockpredict.paper.api.PaperTradingApi;
import com.stockpredict.paper.api.model.Account;
import com.stockpredict.paper.api.model.Order;
import com.stockpredict.paper.api.model.OrderList;
import com.stockpredict.paper.api.model.OrderRequest;
import com.stockpredict.paper.api.model.Portfolio;
import com.stockpredict.paper.api.model.PositionList;
import com.stockpredict.paper.api.model.Watchlist;
import com.stockpredict.paper.api.model.WatchlistUpdateRequest;
import com.stockpredict.paper.service.AccountService;
import com.stockpredict.paper.service.OrderService;
import com.stockpredict.paper.service.WatchlistService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Real paper-trading endpoints; user identity comes from the verified JWT. */
@RestController
public class PaperTradingController implements PaperTradingApi {

    private final AccountService accountService;
    private final WatchlistService watchlistService;
    private final OrderService orderService;

    public PaperTradingController(AccountService accountService,
                                  WatchlistService watchlistService,
                                  OrderService orderService) {
        this.accountService = accountService;
        this.watchlistService = watchlistService;
        this.orderService = orderService;
    }

    @Override
    public ResponseEntity<Account> openAccount() {
        JwtClaims claims = currentUser();
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.open(userId(claims), plan(claims)));
    }

    @Override
    public ResponseEntity<Account> resetAccount() {
        JwtClaims claims = currentUser();
        return ResponseEntity.ok(accountService.reset(userId(claims)));
    }

    @Override
    public ResponseEntity<Watchlist> getWatchlist() {
        JwtClaims claims = currentUser();
        return ResponseEntity.ok(watchlistService.get(userId(claims), plan(claims)));
    }

    @Override
    public ResponseEntity<Watchlist> replaceWatchlist(WatchlistUpdateRequest watchlistUpdateRequest) {
        JwtClaims claims = currentUser();
        return ResponseEntity.ok(watchlistService.replace(userId(claims), plan(claims),
                watchlistUpdateRequest.getSymbols()));
    }

    @Override
    public ResponseEntity<Order> submitOrder(OrderRequest orderRequest, String idempotencyKey) {
        JwtClaims claims = currentUser();
        Order order = orderService.submit(userId(claims), orderRequest, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @Override
    public ResponseEntity<OrderList> listOrders(String status, Integer limit) {
        JwtClaims claims = currentUser();
        int capped = limit == null ? 100 : Math.min(Math.max(limit, 1), 500);
        return ResponseEntity.ok(new OrderList()
                .orders(orderService.list(userId(claims), status, capped)));
    }

    @Override
    public ResponseEntity<Void> cancelOrder(UUID id) {
        JwtClaims claims = currentUser();
        orderService.cancel(userId(claims), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<PositionList> listPositions() {
        JwtClaims claims = currentUser();
        return ResponseEntity.ok(new PositionList().positions(accountService.positions(userId(claims))));
    }

    @Override
    public ResponseEntity<Portfolio> getPortfolio() {
        JwtClaims claims = currentUser();
        return ResponseEntity.ok(accountService.portfolio(userId(claims)));
    }

    private JwtClaims currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtClaims claims) {
            return claims;
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED, "Authentication required");
    }

    private UUID userId(JwtClaims claims) {
        return UUID.fromString(claims.subject());
    }

    private Plan plan(JwtClaims claims) {
        return claims.plan() == null ? Plan.FREE : claims.plan();
    }
}
