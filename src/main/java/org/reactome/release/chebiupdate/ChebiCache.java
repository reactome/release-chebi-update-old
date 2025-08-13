package org.reactome.release.chebiupdate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import uk.ac.ebi.chebi.webapps.chebiWS.model.DataItem;
import uk.ac.ebi.chebi.webapps.chebiWS.model.Entity;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChebiCache {
    private static final String CHEBI_CACHE_FILE_NAME = "chebi-cache";
    private static final Logger logger = LogManager.getLogger();

    private boolean isEnabled;
    private Map<String, ChEBICacheEntry> chebiCache;

    public ChebiCache(boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public Map<String, ChEBICacheEntry> getCache() throws IOException {
        if (this.chebiCache == null) {
            this.chebiCache = loadCacheFromFile();
        }
        return this.chebiCache;
    }

    public void writeToCacheFile(String oldIdentifier, Entity entity) throws IOException {
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
    public Entity extractChEBIEntityFromCache(String identifier) throws IOException {
        String identifierWithPrefix = "CHEBI:" + identifier;
        if (getEntry(identifierWithPrefix) == null) {
            return null;
        }

        ChEBICacheEntry chEBICacheEntry = getEntry(identifierWithPrefix);

        // Use the AccessibleEntity to set the formula.
        AccessibleEntity entity = new AccessibleEntity();
        entity.setChebiId(chEBICacheEntry.getChebiID());
        entity.setChebiAsciiName(chEBICacheEntry.getName());

        DataItem formula = new DataItem();
        formula.setData(chEBICacheEntry.getFormula());
        entity.setFormulae(List.of(formula));

        return entity;
    }

    public boolean isEnabled() {
        return this.isEnabled;
    }

    private String getEntityCacheLine(String oldIdentifier, Entity entity) {
        String identifierWithPrefix = "ChEBI:" + oldIdentifier;
        String chebiID = entity.getChebiId();
        String name = entity.getChebiAsciiName();
        String formula = !entity.getFormulae().isEmpty() ? entity.getFormulae().get(0).getData() : "";
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
    private Map<String, ChEBICacheEntry> loadCacheFromFile() throws IOException {
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

        Map<String, ChEBICacheEntry> chebiCache = new HashMap<>();
        Files.readAllLines(Paths.get(CHEBI_CACHE_FILE_NAME)).forEach(line -> {
            String[] parts = line.split("\t");
            String oldChebiID = parts[0];
            String newChebiID = parts[1];
            String name = parts[2];
            String formula = parts.length > 2 ? parts[3] : "";
            chebiCache.put(oldChebiID, new ChEBICacheEntry(newChebiID, name, formula));
        });


        return chebiCache;
    }

    private ChEBICacheEntry getEntry(String identifier) throws IOException {
        return getCache().get(identifier);
    }

    public static class ChEBICacheEntry {
        public String chebiID;
        public String name;
        public String formula;

        public ChEBICacheEntry(String chebiID, String name, String formula) {
            this.chebiID = chebiID;
            this.name = name;
            this.formula = formula;
        }

        public String getChebiID() {
            return this.chebiID;
        }

        public String getName() {
            return this.name;
        }

        public String getFormula() {
            return this.formula;
        }
    }

    /**
     * This looks weird, I know. I needed to be able to set the Formulae on an Entity
     * and the Entity class provided by ChEBI does not have a setter for that. It has setters for
     * other members, just not all of them. So I added a setter, hence the "accessible" name.
     * @author sshorser
     *
     */
    private static class AccessibleEntity extends Entity {
        public void setFormulae(List<DataItem> formulae) {
            this.formulae = formulae;
        }
    }
}
