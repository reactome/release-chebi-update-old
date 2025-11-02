package org.reactome.release.chebiupdate;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class ChEBIEntityRetriever {
    private final ChEBIAPIClient chEBIAPIClient;
    private final ChEBIEntityParser chEBIEntityParser;

    public ChEBIEntityRetriever() {
        this(new ChEBIAPIClient(), new ChEBIEntityParser());
    }

    ChEBIEntityRetriever(ChEBIAPIClient chEBIAPIClient, ChEBIEntityParser chEBIEntityParser) {
        this.chEBIAPIClient = chEBIAPIClient;
        this.chEBIEntityParser = chEBIEntityParser;
    }

    public Map<GKInstance, Optional<ChebiEntity>> getChEBIEntities(List<GKInstance> referenceMolecules)
        throws IOException, InterruptedException {

        if (referenceMolecules == null || referenceMolecules.isEmpty()) {
            throw new IllegalStateException("No reference molecules for identifiers to query ChEBI");
        }

        Map<String, GKInstance> chEBIIdentifierToReferenceMoleculeMap =
            getIdentifierToReferenceMoleculeMap(referenceMolecules);

        Set<String> chEBIIdentifiers = chEBIIdentifierToReferenceMoleculeMap.keySet();
        JSONObject chEBIResponseJSON = chEBIAPIClient.fetchCompounds(chEBIIdentifiers);

        Map<GKInstance, Optional<ChebiEntity>> chEBIEntities = new HashMap<>();
        for (String chEBIIdentifier : chEBIIdentifiers) {
            GKInstance referenceMolecule = chEBIIdentifierToReferenceMoleculeMap.get(chEBIIdentifier);

            JSONObject chEBIIdentifierJSON = chEBIResponseJSON.getJSONObject(chEBIIdentifier);
            if (chEBIIdentifierJSON.getBoolean("exists")) {
                chEBIEntities.put(referenceMolecule, Optional.of(
                    chEBIEntityParser.parse(chEBIIdentifierJSON.getJSONObject("data"))
                ));
            } else {
                chEBIEntities.put(referenceMolecule, Optional.empty());
            }
        }
        return chEBIEntities;
    }

    private Map<String, GKInstance> getIdentifierToReferenceMoleculeMap(List<GKInstance> referenceMolecules) {
        return referenceMolecules
            .stream()
            .collect(Collectors.toMap(
                this::getReferenceMoleculeIdentifier,
                referenceMolecule -> referenceMolecule)
            );
    }

    private String getReferenceMoleculeIdentifier(GKInstance referenceMolecule) {
        try {
            return (String) referenceMolecule.getAttributeValue(ReactomeJavaConstants.identifier);
        } catch (Exception e) {
            throw new RuntimeException("Unable to get reference molecule identifier for " + referenceMolecule, e);
        }
    }
}
