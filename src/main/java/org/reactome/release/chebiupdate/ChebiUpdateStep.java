package org.reactome.release.chebiupdate;

import java.util.Properties;

import org.gk.persistence.MySQLAdaptor;
import org.reactome.release.common.ReleaseStep;
import org.reactome.util.general.DBUtils;

public class ChebiUpdateStep extends ReleaseStep {
	@Override
	public void executeStep(Properties props) throws Exception {
		MySQLAdaptor adaptor = DBUtils.getCuratorDbAdaptor(props);
		this.loadTestModeFromProperties(props);
		long personID = new Long(props.getProperty("personId"));
		ChebiUpdater chebiUpdater = new ChebiUpdater(adaptor, this.testMode, personID);
		
		logger.info("Pre-update duplicate check:");
		chebiUpdater.findAndLogDuplicates();
		chebiUpdater.updateChebiReferenceMolecules();
		logger.info("Post-update duplicate check:");
		chebiUpdater.findAndLogDuplicates();
	}

}
