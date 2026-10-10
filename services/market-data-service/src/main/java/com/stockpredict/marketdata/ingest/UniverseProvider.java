package com.stockpredict.marketdata.ingest;

import com.stockpredict.marketdata.config.MarketDataProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Supplies the ingestion universe: S&P 500 constituents scraped from
 * Wikipedia (with a configured fallback) plus market/sector ETFs.
 */
@Component
public class UniverseProvider {

    private static final Logger log = LoggerFactory.getLogger(UniverseProvider.class);
    private static final String WIKI_URL = "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies";
    private static final int TIMEOUT_MS = 10_000;

    private final MarketDataProperties properties;

    public UniverseProvider(MarketDataProperties properties) {
        this.properties = properties;
    }

    public List<String> symbols() {
        LinkedHashSet<String> symbols = new LinkedHashSet<>(sp500Symbols());
        symbols.addAll(properties.getMarketSymbols());
        symbols.addAll(properties.getSectorEtfs());
        return List.copyOf(symbols);
    }

    List<String> sp500Symbols() {
        try {
            Document doc = Jsoup.connect(WIKI_URL)
                    .userAgent("Mozilla/5.0 (compatible; stockpredict-market-data/1.0)")
                    .timeout(TIMEOUT_MS)
                    .get();
            Element table = doc.selectFirst("table#constituents");
            if (table == null) {
                log.warn("Wikipedia S&P 500 table not found; using fallback symbols");
                return properties.getFallbackSymbols();
            }
            int symbolColumn = symbolColumnIndex(table);
            List<String> symbols = new ArrayList<>();
            for (Element row : table.select("tbody tr")) {
                Elements cells = row.select("td");
                if (symbolColumn < cells.size()) {
                    String symbol = cells.get(symbolColumn).text().trim();
                    if (!symbol.isBlank()) {
                        symbols.add(symbol);
                    }
                }
            }
            if (symbols.isEmpty()) {
                log.warn("Wikipedia S&P 500 table parsed empty; using fallback symbols");
                return properties.getFallbackSymbols();
            }
            return symbols;
        } catch (Exception ex) {
            log.warn("Failed to fetch S&P 500 constituents ({}); using fallback symbols", ex.toString());
            return properties.getFallbackSymbols();
        }
    }

    private static int symbolColumnIndex(Element table) {
        Element header = table.selectFirst("tr");
        if (header != null) {
            Elements headers = header.select("th");
            for (int i = 0; i < headers.size(); i++) {
                if ("Symbol".equalsIgnoreCase(headers.get(i).text().trim())) {
                    return i;
                }
            }
        }
        return 0;
    }
}
