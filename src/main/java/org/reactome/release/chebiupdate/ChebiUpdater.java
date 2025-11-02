package org.reactome.release.chebiupdate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.InvalidAttributeException;
import org.gk.schema.InvalidAttributeValueException;
import org.reactome.release.common.database.InstanceEditUtils;


/**
 * Updates the ReferenceMolecules with new information from ChEBI.
 * @author sshorser
 *
 */
public class ChebiUpdater {
	private static final Logger logger = LogManager.getLogger();
	private static final Logger refMolNameChangeLog = LogManager.getLogger("molNameChangeLog");
	private static final Logger refMolIdentChangeLog = LogManager.getLogger("molIdentChangeLog");
	private static final Logger simpleEntityInstanceChangeLog =
		LogManager.getLogger("simpleEntityInstanceChangeLog");
	private static final Logger duplicatesLog = LogManager.getLogger("duplicatesLog");
	private static final Logger failedChebiLookupsLog = LogManager.getLogger("failedChebiLookupsLog");

	private boolean testMode;
	private MySQLAdaptor adaptor;
	private long personID;
	private Comparator<GKInstance> personComparator;

	private StringBuilder formulaUpdateSB = new StringBuilder();
	private StringBuilder formulaFillSB = new StringBuilder();
	private Map<GKInstance, List<String>> simpleEntityInstanceChanges = new HashMap<>();

	/**
	 * Create a ChebiUpdater
	 * @param adaptor - The database adaptor
	 * @param testMode - Set testMode to TRUE if you want to perform a dry-run. Set to FALSE if you actually want to
	 *                   commit to the database.
	 * @param personID - The DB_ID of the Person whom the InstanceEdits will be associated with.
	 */
	public ChebiUpdater(MySQLAdaptor adaptor, boolean testMode, long personID) {
		this.adaptor = adaptor;
		this.testMode = testMode;
		this.personID = personID;

		// A Comparator object that will compare GKInstances, assuming that they are of the "Person" type,
		// with a surname and firstname.
		this.personComparator = getPersonComparator();
	}

	/**
	 * Update ChEBI ReferenceMolecules. This method will query ChEBI for up-to-date
	 * information, and using that information, it will: <br/>
	 * <ul>
	 * <li>Update the names of ReferenceEntitites that refer to the ReferenceMolecule</li>
	 * <li>Update the names of ReferenceMolecules</li>
	 * <li>Update the identifiers of ReferenceMolecules</li>
	 * <li>Update the formulae of ReferenceMolecules</li>
	 * </ul>
	 *
	 * @throws Exception
	 */
	public void updateChebiReferenceMolecules() throws Exception {
		setupLogHeaders();

		List<GKInstance> referenceMoleculeInstances = getReferenceMoleculeInstances(getChEBIReferenceDatabaseOrThrow());
		logger.info("{} ChEBI ReferenceMolecules to check...", referenceMoleculeInstances.size());

		ChebiDataRetriever dataRetriever = new ChebiDataRetriever();
		ChebiDataRetriever.ChEBIData chEBIData = dataRetriever.retrieveUpdatesFromChebi(referenceMoleculeInstances);

		logFailedEntities(chEBIData.getFailedEntitiesMap());

		executeUpdateTransaction(chEBIData);

		logFormulaChanges();
		logSimpleEntityInstanceChanges();
	}

	private void setupLogHeaders() {
		refMolIdentChangeLog.info("# DB_ID\tCreator\tReference Molecule\tDeprecated Identifier\t" +
			"Replacement Identifier\tAffected referenceEntity DB_IDs\t" +
			"DB_ID of Molecule with Replacement Identifier\t" +
			"DB_IDs of referenceEntities of Molecule with Replacement Identifier");
		refMolNameChangeLog.info("# DB_ID\tCreator\tReference Molecule\tOld Name\tNew Name");
		simpleEntityInstanceChangeLog.info("# DB_ID\tCreator\tAffected ReferenceEntity\tNew ChEBI Name\t" +
			"Updated list of all names");
		failedChebiLookupsLog.info("# DB_ID\tCreator\tReferenceMolecule\tReason");
		duplicatesLog.info("# DB_ID\tCreator\tDuplicated Identifier\tReferenceMolecule");
	}

	private void executeUpdateTransaction(ChebiDataRetriever.ChEBIData chEBIData) throws Exception {
		adaptor.startTransaction();
		try {
			GKInstance instanceEdit = InstanceEditUtils.createInstanceEdit(this.adaptor, this.personID,
					this.getClass().getCanonicalName());

			processChEBIEntities(chEBIData.getEntityMap(), instanceEdit);

			if (!testMode) {
				adaptor.commit();
			} else {
				adaptor.rollback();
			}
		} catch (Exception e) {
			adaptor.rollback();
			throw e;
		}
	}

	private void processChEBIEntities(Map<GKInstance, ChebiEntity> entityMap, GKInstance instanceEdit) throws Exception {
		logger.info("Number of entities we were able to retrieve information about: {}", entityMap.size());

		for (Map.Entry<GKInstance, ChebiEntity> entry : entityMap.entrySet()) {
			GKInstance referenceMolecule = entry.getKey();
			ChebiEntity entity = entry.getValue();
			processEntity(referenceMolecule, entity, instanceEdit);
		}
	}

	private void processEntity(GKInstance referenceMolecule, ChebiEntity entity, GKInstance instanceEdit) throws Exception {
		String chebiID = entity.getChebiID().replaceAll("CHEBI:", "");
		String chebiName = entity.getName();
		String chebiFormulae = entity.getFormula();

		logIfReferenceMoleculeIdentifierChanged(referenceMolecule, chebiID);

		boolean nameUpdated = updateMoleculeName(referenceMolecule, chebiName);
		boolean formulaUpdated = updateMoleculeFormula(referenceMolecule, chebiFormulae);

		if (nameUpdated) {
			updateSimpleEntityInstances(referenceMolecule, chebiName, instanceEdit);
		}

		if (nameUpdated || formulaUpdated) {
			addInstanceEditToExistingModifieds(instanceEdit, referenceMolecule);
			InstanceDisplayNameGenerator.setDisplayName(referenceMolecule);
			adaptor.updateInstanceAttribute(referenceMolecule, ReactomeJavaConstants._displayName);
		}
	}

	private void logFormulaChanges() {
		logger.info("*** Formula-fill changes ***");
		logger.info(this.formulaFillSB.toString());
		logger.info("*** Formula update changes ***");
		logger.info(this.formulaUpdateSB.toString());
	}

	private Comparator<GKInstance> getPersonComparator() {
		return (person1, person2) -> {
            if (person1 == person2) {
                return 0;
            } else if (person1 == null) {
                return -1;
            } else if (person2 == null) {
                return 1;
            }

            try
            {
                String surname1 = (String)person1.getAttributeValue(ReactomeJavaConstants.surname);
                String surname2 = (String)person2.getAttributeValue(ReactomeJavaConstants.surname);
                int surnameCompare = surname1.compareTo(surname2);
                // If surnames are the same, compare firstnames.
                if (surnameCompare == 0) {
                    String firstname1 = (String)person1.getAttributeValue(ReactomeJavaConstants.firstname);
                    String firstname2 = (String)person2.getAttributeValue(ReactomeJavaConstants.firstname);
                    // MUST return the result of firstname-comparison, we're not going any deeper than firstname for
                    // Person comparisons.
                    return firstname1.compareTo(firstname2);
                }
                return surnameCompare;
            } catch (Exception e) {
                logger.error("Error while trying to compare objects: o1: " + person1 + " ; o2: " + person2 +
                        " ; they will be treated as equivalent.", e);
            }
            return 0;
        };
	}

	@SuppressWarnings("unchecked")
	private List<GKInstance> getReferenceMoleculeInstances(GKInstance referenceDatabase) throws Exception {
		return new ArrayList<>(
			(Collection<GKInstance>) adaptor.fetchInstanceByAttribute(
				ReactomeJavaConstants.ReferenceMolecule,
				ReactomeJavaConstants.referenceDatabase,
				"=",
				referenceDatabase.getDBID()
			)
		);

	}

	private GKInstance getChEBIReferenceDatabaseOrThrow() throws Exception {
		Collection<GKInstance> chEBIReferenceDatabaseInstances = adaptor.fetchInstanceByAttribute(
			ReactomeJavaConstants.ReferenceDatabase, ReactomeJavaConstants.name, "=", "ChEBI"
		);

		if (chEBIReferenceDatabaseInstances == null || chEBIReferenceDatabaseInstances.size() != 1) {
			throw new RuntimeException("No unique ChEBI ReferenceDatabase instance could be found");
		}

		return chEBIReferenceDatabaseInstances.iterator().next();
	}

	private void logFailedEntities(Map<GKInstance, String> failedEntitiesMap) throws Exception {
		logger.info("Number of entities we were NOT able to retrieve information about: {}", failedEntitiesMap.size());

		for (GKInstance molecule : failedEntitiesMap.keySet()) {
			GKInstance creator = getCreator(molecule);
			failedChebiLookupsLog.info("{}\t{}\t{}\t{}",
				molecule.getDBID(), getCreatorName(creator), molecule.toString(), failedEntitiesMap.get(molecule));
		}
	}

	/**
	 * Generates the report for ReferenceEntity changes.
	 */
	private void logSimpleEntityInstanceChanges() {
		// Print the referenceEntities that have changes, sorted by who created them.
		for (GKInstance creator : getCreatorsOfSimpleEntityChanges()) {
			for (String message : this.simpleEntityInstanceChanges.get(creator)) {
				simpleEntityInstanceChangeLog.info("{}", message);
			}
		}
	}

	private List<GKInstance> getCreatorsOfSimpleEntityChanges() {
		return this.simpleEntityInstanceChanges.keySet().stream()
			.sorted(this.personComparator).collect(Collectors.toList());
	}

	private boolean updateMoleculeFormula(GKInstance molecule, String chebiFormula) throws Exception {

		if (chebiFormula.isEmpty()) {
			return false;
		}

		String moleculeFormula = (String) molecule.getAttributeValue(ReactomeJavaConstants.formula);
		String moleculeIdentifier = (String) molecule.getAttributeValue(ReactomeJavaConstants.identifier);
		String firstFormula = chebiFormula;

		String reportLinePrefix = String.format(
				"ReferenceMolecule (DB ID: %d / ChEBI ID: %s) has changes: ",
				molecule.getDBID(), moleculeIdentifier
		);

		boolean updated;

		if (isNonEmpty(firstFormula)) {
			updated = reportFormulaChange(moleculeFormula, firstFormula, reportLinePrefix);
		} else {
			updated = handleEmptyFormulaCase(molecule, moleculeFormula);
		}

		if (updated) {
			applyFormulaUpdate(molecule, firstFormula);
		}

		return updated;
	}

	private boolean reportFormulaChange(String oldFormula, String newFormula, String reportLinePrefix) {
		if (oldFormula == null) {
			formulaFillSB.append(reportLinePrefix)
					.append("New Formula: ").append(newFormula).append("\n");
			return true;
		}
		if (!newFormula.equals(oldFormula)) {
			formulaUpdateSB.append(reportLinePrefix)
					.append("Old Formula: ").append(oldFormula).append(" ; ")
					.append("New Formula: ").append(newFormula).append("\n");
			return true;
		}
		return false;
	}

	private boolean handleEmptyFormulaCase(GKInstance molecule, String oldFormula) {
		if (isNonEmpty(oldFormula)) {
			logger.warn("Got empty/NULL formula for {}, old formula was: {}",
					molecule.toString(), oldFormula);
		}
		return true; // still marked as updated so we overwrite
	}

	private void applyFormulaUpdate(GKInstance molecule, String newFormula)
			throws InvalidAttributeException, InvalidAttributeValueException, Exception {
		molecule.setAttributeValue(ReactomeJavaConstants.formula, newFormula);
		adaptor.updateInstanceAttribute(molecule, ReactomeJavaConstants.formula);
	}

	/**
	 * Updates a molecule's name.
	 *
	 * @param molecule
	 * @param chebiName
	 * @return True if the name was updated. False if not.
	 * @throws InvalidAttributeException
	 * @throws InvalidAttributeValueException
	 * @throws Exception
	 */
	private boolean updateMoleculeName(GKInstance molecule, String chebiName)
		throws InvalidAttributeException, InvalidAttributeValueException, Exception {

		List<String> moleculeNames = molecule.getAttributeValuesList(ReactomeJavaConstants.name);
		if (moleculeNames == null || moleculeNames.isEmpty()) {
			return false;
		}

		String moleculeName = (String) molecule.getAttributeValuesList(ReactomeJavaConstants.name).get(0);
		if (!chebiName.equals(moleculeName)) {
			molecule.setAttributeValue(ReactomeJavaConstants.name, chebiName);
			GKInstance creator = getCreator(molecule);
			refMolNameChangeLog.info("{}\t{}\t{}\t{}\t{}",
				molecule.getDBID(), getCreatorName(creator), molecule.toString() , moleculeName, chebiName);
			adaptor.updateInstanceAttribute(molecule, ReactomeJavaConstants.name);
			return true;
		}
		return false;
	}

	/**
	 * Writes report lines if a molecule's Identifier has changed, according to ChEBI.
	 *
	 * @param referenceMolecule
	 * @param newChebiID
	 * @return True if the identifier was updated, false otherwise.
	 * @throws InvalidAttributeException
	 * @throws InvalidAttributeValueException
	 * @throws Exception
	 */
	private void logIfReferenceMoleculeIdentifierChanged(GKInstance referenceMolecule, String newChebiID)
		throws InvalidAttributeException, Exception {

		String oldMoleculeIdentifier = (String) referenceMolecule.getAttributeValue(ReactomeJavaConstants.identifier);
		if (newChebiID.equals(oldMoleculeIdentifier)) {
			return;
		}

		List <GKInstance> refMolsWithNewIdentifier = getReferenceMoleculesWithChEBIIdentifier(newChebiID);
		if (refMolsWithNewIdentifier.isEmpty()) {
			logReferenceMoleculeIdentifierChange(referenceMolecule, newChebiID, null);
		}

		for (GKInstance referenceMoleculeWithNewIdentifier : refMolsWithNewIdentifier) {
			logReferenceMoleculeIdentifierChange(referenceMolecule, newChebiID, referenceMoleculeWithNewIdentifier);
		}
	}

	private void logReferenceMoleculeIdentifierChange(
		GKInstance referenceMolecule, String newChebiId, GKInstance newReferenceMolecule) throws Exception {

		String oldMoleculeIdentifier = (String) referenceMolecule.getAttributeValue(ReactomeJavaConstants.identifier);
		//Need to get list of DB_IDs of referrers for *old* Identifier and also for *new* Identifier.
		String oldIdentifierReferrersString = referrerIDJoiner(referenceMolecule);

		refMolIdentChangeLog.info("{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
			referenceMolecule.getDBID(),
			getCreatorName(getCreator(referenceMolecule)),
			referenceMolecule.toString(),
			oldMoleculeIdentifier,
			newChebiId,
			newReferenceMolecule != null ? newReferenceMolecule.getDBID() : "No new Reference Molecule DB_ID",
			oldIdentifierReferrersString,
			newReferenceMolecule != null ? referrerIDJoiner(newReferenceMolecule) :
				"No simple entities DB_IDs for non-existent new Reference Molecule"
		);
	}

	private List<GKInstance> getReferenceMoleculesWithChEBIIdentifier(String chebiID) throws Exception {
		@SuppressWarnings("unchecked")
		Collection<GKInstance> refMolsWithChEBIIdentifier = (Collection<GKInstance>) adaptor.fetchInstanceByAttribute(
			ReactomeJavaConstants.ReferenceMolecule, ReactomeJavaConstants.identifier, "=", chebiID
		);

		if (refMolsWithChEBIIdentifier == null || refMolsWithChEBIIdentifier.isEmpty()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(refMolsWithChEBIIdentifier);
	}

	/**
	 * Returns the DB_IDs of the referrers of a ReferenceMolecule, all joined by "|".
	 * @param molecule The ReferenceMolecule for which to get referrers
	 * @return Returns the DB_IDs of the referrers of a ReferenceMolecule, all joined by "|" or an empty string if
	 * there are no referrers.
	 * @throws Exception Thrown if referrers retrieval from the database throws an exception
	 */
	@SuppressWarnings("unchecked")
	private String referrerIDJoiner(GKInstance molecule) throws Exception {
		Collection<GKInstance> referrers =
			((Collection<GKInstance>) molecule.getReferers(ReactomeJavaConstants.referenceEntity));

		if (referrers == null) {
			return "";
		}

		return referrers.stream()
			.map(referrer -> referrer.getDBID().toString())
			.collect(Collectors.joining("|"));
	}

	/**
	 * Updates Objects that refer to a ReferenceMolecule via the "referenceEntity" attribute. The ChEBI name will be
	 * appended to the Entities' list of names, at the end. Unless the ChEBI name is *already* in the list, in which
	 * case nothing will happen.
	 * @param molecule The ReferenceMolecule whose referrers need to be updated.
	 * @param chebiName The ChEBI name.
	 * @throws Exception
	 * @throws InvalidAttributeException
	 * @throws InvalidAttributeValueException
	 */
	private void updateSimpleEntityInstances(GKInstance molecule, String chebiName, GKInstance instanceEdit)
		throws Exception, InvalidAttributeException, InvalidAttributeValueException {

		for (GKInstance simpleEntityInstance : getReferenceMoleculeReferrers(molecule)) {
			@SuppressWarnings("unchecked")
			List<String> names = getSimpleEntityInstanceNames(simpleEntityInstance);

			if (!nameShouldBeAdded(chebiName, simpleEntityInstance)) {
				continue;
			}

			names.add(chebiName);
			simpleEntityInstance.setAttributeValue(ReactomeJavaConstants.name, names);
			adaptor.updateInstanceAttribute(simpleEntityInstance, ReactomeJavaConstants.name);
			addInstanceEditToExistingModifieds(instanceEdit, simpleEntityInstance);
			GKInstance creator = getCreator(simpleEntityInstance);

			@SuppressWarnings("unchecked")
			String message = String.join("\t",
				simpleEntityInstance.getDBID().toString(),
				getCreatorName(creator),
				simpleEntityInstance.toString(),
				chebiName,
				((List<String>)simpleEntityInstance.getAttributeValuesList(ReactomeJavaConstants.name)).toString()
			);
			this.simpleEntityInstanceChanges.computeIfAbsent(creator, k -> new ArrayList<>()).add(message);
		}
	}

	private List<GKInstance> getReferenceMoleculeReferrers(GKInstance referenceMolecule) throws Exception {
		@SuppressWarnings("unchecked")
		Collection<GKInstance> referrers = referenceMolecule.getReferers(ReactomeJavaConstants.referenceEntity);
		if (referrers == null || referrers.isEmpty()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(referrers);
	}

	private List<String> getSimpleEntityInstanceNames(GKInstance simpleEntityInstance) throws Exception {
		@SuppressWarnings("unchecked")
		List<String> names = (List<String>) simpleEntityInstance.getAttributeValuesList(ReactomeJavaConstants.name);
		if (names == null || names.isEmpty()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(names);
	}

	private boolean nameShouldBeAdded(String chEBIName, GKInstance simpleEntityInstance) throws Exception {
		List<String> names = getSimpleEntityInstanceNames(simpleEntityInstance);
		if (names == null || names.isEmpty()) {
			logger.error("\"{}\" has a NULL/Empty list of names. This doesn't seem right.",
				simpleEntityInstance.toString());
			return false;
		}

		// If the first name IS the ChEBI name, then nothing to do. But if not, then need to append.
		if (names.get(0).equals(chEBIName)) {
			logger.info("\"{}\" has \"{}\" as its first name: {}",
				simpleEntityInstance.toString(), chEBIName, names.toString());
			return false;
		}

		if (names.contains(chEBIName)) {
			logger.info("\"{}\" *already* has \"{}\" as in its list of names; it will not be added again. Names: {}",
				simpleEntityInstance.toString(), chEBIName, names.toString());
			return false;
		}
		return true;
	}

	/**
	 * Gets the Creator of some instance.
	 * @param inst - the Instance to get the creator of.
	 * @return A GKInstance. It is the value in the "author" attribute (most likely, it will be a Person object) of
	 * the InstanceEdit that is associated with "created" attribute of <code>inst</code>.
	 * If the instance does not have a "created" attribute, then NULL will be returned.
	 * @throws InvalidAttributeException
	 * @throws Exception
	 */
	private GKInstance getCreator(GKInstance inst) throws InvalidAttributeException, Exception {
		GKInstance createdInstanceEdit = (GKInstance) inst.getAttributeValue(ReactomeJavaConstants.created);
		if (createdInstanceEdit == null) {
			// User should probably be warned that the object has no "creator" attribute value
			// so they can explain to the curators why there is no author name in the report.
			logger.warn("Instance {} does not have a value for \"created\" attribute!", inst.toString());
			return null;
		}
		GKInstance creator = (GKInstance) createdInstanceEdit.getAttributeValue(ReactomeJavaConstants.author);
		return creator;
	}

	/**
	 * Adds an instanceEdit to an existing list of "modified" objects.
	 * @param instanceEdit The InstanceEdit to add.
	 * @param instance The instance to add the InstanceEdit to.
	 * @throws InvalidAttributeException
	 * @throws Exception
	 * @throws InvalidAttributeValueException
	 */
	private void addInstanceEditToExistingModifieds(GKInstance instanceEdit, GKInstance instance)
		throws InvalidAttributeException, Exception, InvalidAttributeValueException {

		// make sure the "modified" list is loaded.
		instance.getAttributeValuesList(ReactomeJavaConstants.modified);
		// add this instanceEdit to the "modified" list, and update.
		instance.addAttributeValue(ReactomeJavaConstants.modified, instanceEdit);
		adaptor.updateInstanceAttribute(instance, ReactomeJavaConstants.modified);
	}

	/**
	 * Queries the database for duplicate ChEBI ReferenceMolecules, and prints the
	 * results. A duplicate ChEBI ReferenceMolecule is defined as a
	 * ReferenceMolecule with the same ChEBI Identifier as a different
	 * ReferenceMolecule. No two ReferenceMolecules should share a ChEBI Identifier.
	 *
	 * @throws Exception
	 */
	public void findAndLogDuplicates() throws Exception {
		List<GKInstance> referenceMolecules = getReferenceMoleculeInstances(getChEBIReferenceDatabaseOrThrow());
		Map<String, List<GKInstance>> duplicates = getDuplicateIdentifierToReferenceMolecules(referenceMolecules);

		for (Map.Entry<String, List<GKInstance>> entry : duplicates.entrySet()) {
			String identifier = entry.getKey();
			List<GKInstance> duplicateReferenceMolecules = entry.getValue();

			// Log each duplicate instance
			for (GKInstance referenceMolecule : duplicateReferenceMolecules) {
				GKInstance creator = getCreator(referenceMolecule);
				duplicatesLog.info("{}\t{}\t{}\t{}",
					referenceMolecule.getDBID(),
					getCreatorName(creator),
					identifier,
					referenceMolecule.toString()
				);
			}
		}
	}

	private Map<String, List<GKInstance>> getDuplicateIdentifierToReferenceMolecules(
		List<GKInstance> referenceMolecules) {

		return referenceMolecules.stream()
			.collect(Collectors.groupingBy(molecule -> {
				try {
					return (String) molecule.getAttributeValue(ReactomeJavaConstants.identifier);
				} catch (Exception e) {
					logger.error("Error getting identifier for molecule: " + molecule, e);
					return "";
				}
			}))
			.entrySet()
			.stream()
			.filter(entry -> entry.getValue().size() > 1)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private static String getCreatorName(GKInstance creator) {
		return creator != null ? creator.toString() : "AUTHOR UNKNOWN";
	}

	private boolean isNonEmpty(String value) {
		return value != null && !value.trim().isEmpty();
	}
}
