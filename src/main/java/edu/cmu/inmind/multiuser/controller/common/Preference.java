package edu.cmu.inmind.multiuser.controller.common;

/**
 * Created by oscarr on 2/27/18.
 */
public class Preference {
    public static final String LIKE = "LIKE";
    public static final String DISLIKE = "DISLIKE";
    private String type;  // LIKE or DISLIKE
    private String lemaVerb; // nsubj - VERB
    private String lemmaObj; // dobj - NNS or NS


    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getLemaVerb() {
        return lemaVerb;
    }

    public void setLemaVerb(String lemaVerb) {
        this.lemaVerb = lemaVerb;
    }

    public String getLemmaObj() {
        return lemmaObj;
    }

    public void setLemmaObj(String lemmaObj) {
        this.lemmaObj = lemmaObj;
    }
}
