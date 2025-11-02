package org.reactome.release.chebiupdate;

public class ChebiEntity {

    private String chebiID;
    private String name;
    private String formula;

    public ChebiEntity(String chebiID, String name, String formula) {
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

    @Override
    public String toString() {
        return String.format("ChebiEntity[chebiID=%s, name=%s, formula=%s]", this.chebiID, this.name, this.formula);
    }
}
