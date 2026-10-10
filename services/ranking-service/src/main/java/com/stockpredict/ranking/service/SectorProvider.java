package com.stockpredict.ranking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.ranking.config.RankingProperties;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a symbol's sector for the 25% sector cap. Sectors come from an
 * optional JSON map exported alongside the models; unknown symbols fall into a
 * single "Unknown" bucket so the cap still applies.
 */
@Component
public class SectorProvider {

    private static final Logger log = LoggerFactory.getLogger(SectorProvider.class);
    public static final String UNKNOWN = "Unknown";

    private final RankingProperties properties;
    private final ObjectMapper objectMapper;
    private volatile Map<String, String> sectorBySymbol = Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    public SectorProvider(RankingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Test/seeding constructor. */
    public SectorProvider(Map<String, String> sectorBySymbol) {
        this.properties = new RankingProperties();
        this.objectMapper = new ObjectMapper();
        this.sectorBySymbol = Map.copyOf(sectorBySymbol);
    }

    @PostConstruct
    public void load() {
        Path path = Path.of(properties.getSectorMapFile());
        if (!Files.exists(path)) {
            log.info("No sector map at {}; all symbols will be treated as '{}'", path, UNKNOWN);
            return;
        }
        try (InputStream in = Files.newInputStream(path)) {
            sectorBySymbol = objectMapper.readValue(in, new TypeReference<Map<String, String>>() { });
            log.info("Loaded {} sector mappings from {}", sectorBySymbol.size(), path);
        } catch (Exception ex) {
            log.warn("Unable to read sector map {}: {}", path, ex.getMessage());
        }
    }

    public String sector(String symbol) {
        return sectorBySymbol.getOrDefault(symbol, UNKNOWN);
    }
}
