package com.stockpredict.paper.engine;

import com.stockpredict.paper.config.PaperProperties;
import com.stockpredict.paper.data.OrderRepository;
import com.stockpredict.paper.data.OrderRow;
import com.stockpredict.paper.service.FillService;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Evaluates working orders against the latest quote and applies fills.
 *
 * <ul>
 *   <li>MARKET: fills at last * (1 +/- slippage).</li>
 *   <li>LIMIT BUY: fills when ask &lt;= limit, at the limit price.
 *       LIMIT SELL: fills when bid &gt;= limit, at the limit price.</li>
 *   <li>STOP BUY: triggers when last &gt;= stop. STOP SELL: triggers when last &lt;= stop.
 *       Triggered stops fill at last * (1 +/- slippage).</li>
 * </ul>
 */
@Service
public class MatchingEngine {

    /** Basis points per unit fraction (1 bp = 0.0001). */
    private static final double BPS_PER_UNIT = 10_000.0;

    private final OrderRepository orderRepository;
    private final QuoteProvider quoteProvider;
    private final FillService fillService;
    private final PaperProperties properties;

    public MatchingEngine(OrderRepository orderRepository,
                          QuoteProvider quoteProvider,
                          FillService fillService,
                          PaperProperties properties) {
        this.orderRepository = orderRepository;
        this.quoteProvider = quoteProvider;
        this.fillService = fillService;
        this.properties = properties;
    }

    /** Matches all currently working orders; returns the number of fills applied. */
    public int matchWorkingOrders() {
        int matched = 0;
        for (OrderRow order : orderRepository.findWorkingOrders()) {
            Optional<Quote> maybeQuote = quoteProvider.quote(order.symbol());
            if (maybeQuote.isEmpty() || !maybeQuote.get().valid()) {
                continue;
            }
            Quote quote = maybeQuote.get();
            Optional<FillDecision> decision = evaluate(order, quote);
            if (decision.isPresent()
                    && fillService.applyFill(order, decision.get().price(), decision.get().slippageBps(), quote.ts())) {
                matched++;
            }
        }
        return matched;
    }

    Optional<FillDecision> evaluate(OrderRow order, Quote quote) {
        if (quote == null || !quote.valid()) {
            return Optional.empty();
        }
        boolean buy = "BUY".equals(order.side());
        double slippage = properties.getSlippageBps() / BPS_PER_UNIT;
        return switch (order.type()) {
            case "MARKET" -> Optional.of(new FillDecision(
                    quote.last() * (buy ? 1 + slippage : 1 - slippage), properties.getSlippageBps()));
            case "LIMIT" -> {
                if (order.limitPrice() == null) {
                    yield Optional.empty();
                }
                if (buy && quote.ask() <= order.limitPrice()) {
                    yield Optional.of(new FillDecision(order.limitPrice(), 0));
                }
                if (!buy && quote.bid() >= order.limitPrice()) {
                    yield Optional.of(new FillDecision(order.limitPrice(), 0));
                }
                yield Optional.empty();
            }
            case "STOP" -> {
                if (order.stopPrice() == null) {
                    yield Optional.empty();
                }
                if (buy && quote.last() >= order.stopPrice()) {
                    yield Optional.of(new FillDecision(
                            quote.last() * (1 + slippage), properties.getSlippageBps()));
                }
                if (!buy && quote.last() <= order.stopPrice()) {
                    yield Optional.of(new FillDecision(
                            quote.last() * (1 - slippage), properties.getSlippageBps()));
                }
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    record FillDecision(double price, double slippageBps) {
    }
}
