package org.reactome.release.chebiupdate;

import java.io.IOException;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;

import uk.ac.ebi.chebi.webapps.chebiWS.client.ChebiWebServiceClient;
import uk.ac.ebi.chebi.webapps.chebiWS.model.ChebiWebServiceFault_Exception;
import uk.ac.ebi.chebi.webapps.chebiWS.model.Entity;

/**
 * An instance of this class can be used to retrieve data from ChEBI.
 * @author sshorser
 *
 */
class ChebiDataRetriever {
	// Optional TODO: Make this user-configurable. Not really a high priority, but might prove useful some day in the
	// 			distant future...
	private ChebiWebServiceClient chebiClient = new ChebiWebServiceClient();
	
	private static final Logger logger = LogManager.getLogger();

	private ChebiCache chebiCache;
	private ChEBIData chebiData;

	/**
	 * Creates a new ChEBI Data Retriever.
	 */
	public ChebiDataRetriever(boolean useCache) {
		this.chebiCache = new ChebiCache(useCache);
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
	public ChEBIData retrieveUpdatesFromChebi(Collection<GKInstance> referenceMolecules) throws Exception {
		for (GKInstance referenceMolecule : referenceMolecules) {
			String identifier = getIdentifier(referenceMolecule);
			if (identifier.isEmpty()) {
				chebiData.addFailedEntity(referenceMolecule, "ChEBI Identifier is empty.");
				continue;
			}

			Entity entity = getEntity(referenceMolecule);

			if (entity != null) {
				chebiData.addEntity(referenceMolecule, entity);
			} else {
				chebiData.addFailedEntity(referenceMolecule, "ChEBI WebService response was NULL.");
			}
		}

		return chebiData;
	}

	private String getIdentifier(GKInstance molecule) throws Exception {
		String identifier = (String) molecule.getAttributeValue(ReactomeJavaConstants.identifier);

		if (identifier == null || identifier.trim().isEmpty()) {
			return "";
		}

		return identifier;
	}

	private Entity getEntity(GKInstance referenceMolecule) throws Exception {
		Entity entity = getEntityFromCacheIfEnabled(referenceMolecule);

		if (entity == null) {
			entity = getChEBIDataFromWebService(referenceMolecule);
		}
		return entity;
	}

	private Entity getEntityFromCacheIfEnabled(GKInstance referenceMolecule) throws Exception {
		if (!getChEBICache().isEnabled()) {
			return null;
		}

		return getChEBICache().extractChEBIEntityFromCache(getIdentifier(referenceMolecule));
	}

	/**
	 * Handles exceptions from the ChEBI web service. Some of them are ok, as in "invalid ChEBI identifier",
	 * and "entity is deleted, obsolete, not released", but anything else
	 * will cause a runtime exception to be thrown. It's probably not safe to continue with unrecognized exceptions.
	 * IF you encounter *new* exceptions that are not listed here but are not too serious, feel free to update this
	 * code to write the appropriate message for those exceptions, rather than failing with a RuntimeException.
	 * @param molecule
	 * @param e
	 */
	private void handleWSException(GKInstance molecule, ChebiWebServiceFault_Exception e) throws Exception {
		String identifier = getIdentifier(molecule);
		// "invalid ChEBI identifier" probably shouldn't break execution but should be logged for further investigation.
		if (e.getMessage().contains("invalid ChEBI identifier")) {
			String errMsg = "ChEBI Identifier \"" + identifier + "\" is not formatted correctly.";
			logger.error(errMsg);
		}
		// Log this identifier but don't fail.
		else if (e.getMessage().contains("the entity in question is deleted, obsolete, or not yet released"))
		{
			String errMsg = "ChEBI Identifier \"" + identifier + "\" is deleted, obsolete, or not yet released.";
			logger.error(errMsg);
		}
		else
		{
			// Other Webservice errors should probably break execution - if one fails, they will all probably fail.
			// This is *not* a general principle, but is based on my experience with the ChEBI webservice specifically -
			// it's a pretty stable service, so it's unlikely that if one service call fails, the others will succeed.
			logger.error("WebService error occurred! Message is: {}", e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/**
	 * Get data from the ChEBI web service.
	 * @param referenceMolecule - the Molecule to query for.
	 * @return - The Entity, from ChEBI.
	 * @throws IOException
	 * @throws Exception
	 */
	private Entity getChEBIDataFromWebService(GKInstance referenceMolecule) throws IOException, Exception {

		Entity entity;
		try {
			entity = this.chebiClient.getCompleteEntity(getIdentifier(referenceMolecule));
		} catch (ChebiWebServiceFault_Exception e) {
			handleWSException(referenceMolecule, e);
			chebiData.addFailedEntity(
				referenceMolecule, "ChEBI Identifier " + getIdentifier(referenceMolecule) + ": " + e.getMessage()
			);
			entity = null;
		}
		// IF there is a valid entity AND we are supposed to use a cache, then write the entity to the cache file.
		if (entity != null && getChEBICache().isEnabled()) {
			getChEBICache().writeToCacheFile(getIdentifier(referenceMolecule), entity);
		}

		return entity;
	}

	private ChebiCache getChEBICache() {
		return this.chebiCache;
	}

	public class ChEBIData {
		private Map<GKInstance, Entity> entityMap;
		private Map<GKInstance, String> failedEntitiesMap;

		public ChEBIData() {
			this.entityMap = new HashMap<>();
			this.failedEntitiesMap = new HashMap<>();
		}

		public Map<GKInstance, Entity> getEntityMap() {
			return this.entityMap;
		}

		public Map<GKInstance, String> getFailedEntitiesMap() {
			return this.failedEntitiesMap;
		}

		public void addEntity(GKInstance referenceMolecule, Entity entity) {
			this.entityMap.put(referenceMolecule, entity);
		}

		public void addFailedEntity(GKInstance referenceMolecule, String errorMessage) {
			this.failedEntitiesMap.put(referenceMolecule, errorMessage);
		}


	}
}