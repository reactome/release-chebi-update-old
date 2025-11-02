package org.reactome.release.chebiupdate;

import org.json.JSONObject;

public class ChEBIEntityParser {

    public ChebiEntity parse(JSONObject chEBIJSON) {
        String chebiID = chEBIJSON.getString("chebi_accession").replace("CHEBI:", "");
        String name = chEBIJSON.getString("ascii_name");
        String formula = getFormula(chEBIJSON);

        return new ChebiEntity(chebiID, name, formula);
    }

    private String getFormula(JSONObject chEBIJSON) {
        if (chEBIJSON.isNull("chemical_data") ||
                chEBIJSON.getJSONObject("chemical_data").isNull("formula")) {
            return "";
        }

        return chEBIJSON.getJSONObject("chemical_data").getString("formula");
    }
}
