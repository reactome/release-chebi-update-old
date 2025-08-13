package org.reactome.release.chebiupdate;

import java.io.FileInputStream;
import java.sql.SQLException;
import java.util.Properties;

public class Main {
	public static void main(String[] args) throws SQLException, Exception {
		// Assume the path to the properties file is ./chebi-update.properties
		// but if args[] is not empty, then the first argument must be the path to
		// the resources file.
		String pathToResources = args.length > 0 ? args[0] : "./chebi-update.properties";

		Properties props = new Properties();
		props.load(new FileInputStream(pathToResources));

		ChebiUpdateStep chebiUpdateStep = new ChebiUpdateStep();
		chebiUpdateStep.executeStep(props);
	}
}
