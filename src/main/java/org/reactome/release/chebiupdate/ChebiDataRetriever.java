package org.reactome.release.chebiupdate;

import java.io.IOException;
import java.util.*;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;

/**
 * An instance of this class can be used to retrieve data from ChEBI.
 * @author sshorser
 *
 */
class ChebiDataRetriever {
	private static final Logger logger = LogManager.getLogger();

	private ChEBIData chebiData;

	/**
	 * Creates a new ChEBI Data Retriever.
	 */
	public ChebiDataRetriever() {
		this.chebiData = new ChEBIData();
	}
	
	/**
	 * Makes calls to the ChEBI web service to get info for specified ChEBI
	 * identifiers.
	 *
	 * @param referenceMolecules - a list of ReferenceMolecules. The Identifier of each of these molecules will be sent
	 *                             to ChEBI to get up-to-date information for that Identifier.
	 * @return A ReferenceMolecule DB_ID-to-ChEBI Entity map.
	 * @throws IOException 
	 */
	public ChEBIData retrieveUpdatesFromChebi(List<GKInstance> referenceMolecules) throws Exception {
		ChEBIEntityRetriever chEBIEntityRetriever = new ChEBIEntityRetriever();

		final int batchSize = 500;
		int processedCount = 0;
		for (List<GKInstance> referenceMoleculeBatch : getReferenceMoleculeBatches(referenceMolecules, batchSize)) {

			Map<GKInstance, Optional<ChebiEntity>> referenceMolecule2ChebiEntity
				= chEBIEntityRetriever.getChEBIEntities(new ArrayList<>(referenceMoleculeBatch));

			for (GKInstance referenceMolecule : referenceMoleculeBatch) {
				String identifier = getIdentifier(referenceMolecule);
				if (identifier.isEmpty()) {
					chebiData.addFailedEntity(referenceMolecule, "ChEBI Identifier is empty.");
					continue;
				}

				Optional<ChebiEntity> entity = referenceMolecule2ChebiEntity.get(referenceMolecule);

				if (entity != null && entity.isPresent()) {
					chebiData.addEntity(referenceMolecule, entity.get());
				} else {
					chebiData.addFailedEntity(referenceMolecule, "No ChEBI Entity found for " + identifier);
				}

			}

			processedCount += referenceMoleculeBatch.size();
			logger.info("Finished processing " + processedCount + " reference molecules");
		}

		return chebiData;
	}

	private List<List<GKInstance>> getReferenceMoleculeBatches(
			List<GKInstance> referenceMolecules, int batchSize) {

		return Lists.partition(referenceMolecules, batchSize);
	}


	private String getIdentifier(GKInstance molecule) throws Exception {
		String identifier = (String) molecule.getAttributeValue(ReactomeJavaConstants.identifier);

		if (identifier == null || identifier.trim().isEmpty()) {
			return "";
		}

		return identifier;
	}


	public static class ChEBIData {
		private Map<GKInstance, ChebiEntity> entityMap;
		private Map<GKInstance, String> failedEntitiesMap;

		public ChEBIData() {
			this.entityMap = new HashMap<>();
			this.failedEntitiesMap = new HashMap<>();
		}

		public Map<GKInstance, ChebiEntity> getEntityMap() {
			return this.entityMap;
		}

		public Map<GKInstance, String> getFailedEntitiesMap() {
			return this.failedEntitiesMap;
		}

		public void addEntity(GKInstance referenceMolecule, ChebiEntity entity) {
			this.entityMap.put(referenceMolecule, entity);
		}

		public void addFailedEntity(GKInstance referenceMolecule, String errorMessage) {
			this.failedEntitiesMap.put(referenceMolecule, errorMessage);
		}
	}
}