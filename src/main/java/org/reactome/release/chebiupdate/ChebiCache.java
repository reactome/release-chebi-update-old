package org.reactome.release.chebiupdate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ChebiCache {
    private static final String CHEBI_CACHE_FILE_NAME = "chebi-cache";
    private static final Logger logger = LogManager.getLogger();

    private boolean isEnabled;
    private Map<String, ChebiEntity> chebiCache;

    public ChebiCache(boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public Map<String, ChebiEntity> getCache() throws IOException {
        if (this.chebiCache == null) {
            this.chebiCache = loadCacheFromFile();
        }
        return this.chebiCache;
    }

    public void writeToCacheFile(String oldIdentifier, ChebiEntity entity) throws IOException {
        try (FileWriter fileWriter = new FileWriter(CHEBI_CACHE_FILE_NAME, true);
             BufferedWriter bw = new BufferedWriter(fileWriter)) {

            bw.write(getEntityCacheLine(oldIdentifier, entity));
            bw.flush();
        }
    }


    /**
     * Extract a ChEBI entity from the cache, based on the identifier.
     * @param identifier - The chebi Identifier.
     * @return A ChEBI Entity that will be built from the data in the cache. It will only have: an Identifier,
     * ChEBI Name, Formula.  NULL will be returned if <code>identifier</code> is not in the cache.
     */
    public ChebiEntity extractChEBIEntityFromCache(String identifier) throws IOException {
        String identifierWithPrefix = "CHEBI:" + identifier;

        return getEntry(identifierWithPrefix);
    }

    public boolean isEnabled() {
        return this.isEnabled;
    }

    private String getEntityCacheLine(String oldIdentifier, ChebiEntity entity) {
        String identifierWithPrefix = "ChEBI:" + oldIdentifier;
        String chebiID = entity.getChebiID();
        String name = entity.getName();
        String formula = entity.getFormula();
        String currentDateTime = LocalDateTime.now().toString();

        return String.join("\t", identifierWithPrefix, chebiID, name, formula, currentDateTime) +
                System.lineSeparator();
    }

    /**
     * Returns the data from the cache file (if it exists). If it doesn't exist, then an empty map will be returned.
     * @return A mapping of ChEBI ID to a list with items in this order: ChEBI (might be different if ChEBI has changed
     * identifiers), ChEBI Name, Formula.
     * @throws IOException Thrown if unable to read cache file lines
     */
    private Map<String, ChebiEntity> loadCacheFromFile() throws IOException {
        if (!isEnabled()) {
            logger.info(
                "useCache is FALSE - chebi-cache will NOT be read. ChEBI will be queried for ALL identifiers."
            );
            return Collections.emptyMap();
        }

        if (!Files.exists(Paths.get(CHEBI_CACHE_FILE_NAME))) {
            logger.info("chebi-cache file does not exist at " + CHEBI_CACHE_FILE_NAME + ". Returning empty cache");
            return Collections.emptyMap();
        }

        logger.info("useCache is TRUE - chebi-cache file will be read, and populated. " +
                "Identifiers not in the cache will be queried from ChEBI."
        );

        Map<String, ChebiEntity> chebiCache = new HashMap<>();
        Files.readAllLines(Paths.get(CHEBI_CACHE_FILE_NAME)).forEach(line -> {
            String[] parts = line.split("\t");
            String oldChebiID = parts[0];
            String newChebiID = parts[1];
            String name = parts[2];
            String formula = parts.length > 2 ? parts[3] : "";
            chebiCache.put(oldChebiID, new ChebiEntity(newChebiID, name, formula));
        });


        return chebiCache;
    }

    private ChebiEntity getEntry(String identifier) throws IOException {
        return getCache().get(identifier);
    }
}
