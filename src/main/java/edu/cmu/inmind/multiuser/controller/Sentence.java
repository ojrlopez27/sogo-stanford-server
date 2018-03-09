package edu.cmu.inmind.multiuser.controller;

/**
 * Created by oscarr on 3/8/18.
 */
public class Sentence {
    private String sentence;

    public Sentence(String sentence) {
        this.sentence = sentence;
    }

    public String getSentence() {
        return sentence;
    }

    public void setSentence(String sentence) {
        this.sentence = sentence;
    }
}
