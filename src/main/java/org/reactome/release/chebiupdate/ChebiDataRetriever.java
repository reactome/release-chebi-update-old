package org.reactome.release.chebiupdate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.json.JSONObject;

/**
 * An instance of this class can be used to retrieve data from ChEBI.
 * @author sshorser
 *
 */
class ChebiDataRetriever {
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

			ChebiEntity entity = getEntity(referenceMolecule);

			if (entity != null) {
				chebiData.addEntity(referenceMolecule, entity);
			}
		}

		return chebiData;
	}

	ChebiEntity getCompleteEntity(String identifier) throws Exception {
		if (identifier.isEmpty()) {
			throw new IllegalStateException("identifier is empty");
		}

		String restURL = "https://www.ebi.ac.uk/chebi/backend/api/public/compound/" + identifier;

		HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.build();

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(restURL))
			.GET()
			.build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		JSONObject chEBIJSON = new JSONObject(response.body());

		System.out.println(chEBIJSON);
		if (noCompoundMatches(chEBIJSON)) {
			throw new NoCompoundMatchesException("No compound matches found for identifier: " + identifier);
		}

		String chebiID = chEBIJSON.getString("chebi_accession");
		String name = chEBIJSON.getString("ascii_name");
		String formula = getFormula(chEBIJSON);
		return new ChebiEntity(chebiID, name, formula);
	}

	private static boolean noCompoundMatches(JSONObject chEBIJSON) {
		if (chEBIJSON.has("detail")) {
			return chEBIJSON.getString("detail").contains("No Compound matches");
		}

		return false;
	}

	private String getFormula(JSONObject chEBIJSON) {
		if (chEBIJSON.isNull("chemical_data") ||
				chEBIJSON.getJSONObject("chemical_data").isNull("formula")) {
			return "";
		}

		return chEBIJSON.getJSONObject("chemical_data").getString("formula");
	}

	private String getIdentifier(GKInstance molecule) throws Exception {
		String identifier = (String) molecule.getAttributeValue(ReactomeJavaConstants.identifier);

		if (identifier == null || identifier.trim().isEmpty()) {
			return "";
		}

		return identifier;
	}

	private ChebiEntity getEntity(GKInstance referenceMolecule) throws Exception {
		ChebiEntity entity = getEntityFromCacheIfEnabled(referenceMolecule);

		if (entity == null) {
			entity = getChEBIDataFromWebService(referenceMolecule);
		}
		return entity;
	}

	private ChebiEntity getEntityFromCacheIfEnabled(GKInstance referenceMolecule) throws Exception {
		if (!getChEBICache().isEnabled()) {
			return null;
		}

		return getChEBICache().extractChEBIEntityFromCache(getIdentifier(referenceMolecule));
	}

	/**
	 * Get data from the ChEBI web service.
	 * @param referenceMolecule - the Molecule to query for.
	 * @return - The Entity, from ChEBI.
	 * @throws IOException
	 * @throws Exception
	 */
	private ChebiEntity getChEBIDataFromWebService(GKInstance referenceMolecule) throws IOException, Exception {

		ChebiEntity entity;
		try {
			entity = getCompleteEntity(getIdentifier(referenceMolecule));
		} catch (NoCompoundMatchesException e) {
			chebiData.addFailedEntity(referenceMolecule, e.getMessage());
			return null;
		}

		// IF there is a valid entity AND we are supposed to use a cache, then write the entity to the cache file.
		if (getChEBICache().isEnabled()) {
			getChEBICache().writeToCacheFile(getIdentifier(referenceMolecule), entity);
		}

		return entity;
	}

	private ChebiCache getChEBICache() {
		return this.chebiCache;
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

	public static class NoCompoundMatchesException extends Exception {
		public NoCompoundMatchesException(String message) {
			super(message);
		}
	}
}