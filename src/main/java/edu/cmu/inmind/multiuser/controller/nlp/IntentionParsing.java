package edu.cmu.inmind.multiuser.controller.nlp;

import edu.cmu.inmind.multiuser.controller.common.Preference;
import edu.cmu.inmind.multiuser.controller.common.Utils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.util.CoreMap;

import java.util.List;
import java.util.Properties;

public class IntentionParsing {
	private StanfordCoreNLP pipeline;
	private static IntentionParsing instance;
	private boolean verbose;

	private IntentionParsing() {
		// creates a StanfordCoreNLP object, with POS tagging, lemmatization,
		// NER, parsing, and coreference resolution
		verbose = Boolean.valueOf(Utils.getProperty("verbose"));
		Properties props = new Properties();
		props.setProperty("annotators", "tokenize, ssplit, pos, lemma,depparse,natlog,openie");
		pipeline = new StanfordCoreNLP(props);
        System.out.println("After creating the StanfordCoreNLP instance");
	}

	public static IntentionParsing getInstance(){
	    if(instance == null){
	        instance = new IntentionParsing();
        }
        return instance;
    }

	public String extractPreference(String utterance){
		return Utils.toJson( extractSemGraph(utterance) );
	}


	private Preference extractSemGraph(String utterance){
		// create an empty Annotation just with the given text
		Annotation document = new Annotation(utterance);

		// run all Annotators on this text
		pipeline.annotate(document);

		// these are all the sentences in this document
		// a CoreMap is essentially a Map that uses class objects as
		// keys and
		// has values with custom types
		List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
//		String doc = "";
//		String nes = "";
		String parse = "";
		for (CoreMap sentence : sentences) {
			System.out.println(sentence);
			// traversing the words in the current sentence
			// a CoreLabel is a CoreMap with additional
			// token-specific methods
//			for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
//
//				// this is the text of the token
//				String word = token.get(CoreAnnotations.TextAnnotation.class);
//				// this is the POS tag of the token
//				String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
//
//				// this is the NER label of the token
//				String ne = token.get(CoreAnnotations.NamedEntityTagAnnotation.class);
//
//				String stem = token.getString(CoreAnnotations.StemAnnotation.class);
//				String lemma = token.getString(CoreAnnotations.LemmaAnnotation.class);
//
//				doc += word + " ";
//			}

			SemanticGraph dependencies = sentence.get(SemanticGraphCoreAnnotations.EnhancedDependenciesAnnotation.class);
			if(verbose){
				parse = dependencies.toString(SemanticGraph.OutputFormat.LIST);
				System.out.println("dependency graph:\n" + parse);
			}
			Preference preference = new Preference();
			getLikes(dependencies, preference);
			return preference;
		}
		return null;
	}


//	private void getLikes(SemanticGraph graph, List<SemanticGraphEdge> nodes, Pair pair){
//	    for( SemanticGraphEdge node : nodes) {
//            if (node.getRelation().toString().equals("nsubj")
//                    && node.getDependent().getString(CoreAnnotations.LemmaAnnotation.class).equalsIgnoreCase("I")) {
//                System.out.println("nsubj: " + node.getSource().tag());
//                pair.fst = node.getSource().lemma();
//                getLikes(graph, graph.getIncomingEdgesSorted(node.getSource()), pair);
//            }else if (node.getRelation().toString().equals("dobj")){
//                pair.snd = node.getSource().lemma();
//                return;
//            }else{
//                getLikes(graph, graph.getOutEdgesSorted(node.getSource()), pair);
//            }
//        }
//	}


    private void getLikes(SemanticGraph graph, Preference preference){
        for( SemanticGraphEdge node : graph.edgeListSorted() ) {
            if (node.getRelation().toString().equals("nsubj")
                    && node.getDependent().lemma().equalsIgnoreCase("I")) {
                String mainVerb = node.getSource().lemma();
                if( mainVerb.equals("like") || mainVerb.equals("love") ){
                    preference.setType( Preference.LIKE);
                    preference.setLemaVerb( node.getSource().lemma() );
                }else if( mainVerb.equals("dislike") || mainVerb.equals("hate") ){
                    preference.setType( Preference.DISLIKE);
                    preference.setLemaVerb( node.getSource().lemma() );
                }else{
                    // we are only interested in verbs that describe preferences (e.g., like, love, hate...)
                    return;
                }
            }else if( node.getRelation().toString().equals("neg") && node.getSource().tag().equals("VB")
                    && preference.getType() != null){
                // we are validating whether the verb uses an aux negation (e.g., I don't like books..)
                preference.setType( Preference.DISLIKE );
            }else if (node.getRelation().toString().equals("dobj") && ( node.getTarget().tag().equals("NNS") ||
                    node.getTarget().tag().equals("NS"))){
                preference.setLemmaObj( node.getTarget().lemma() );
                return;
            }
        }
    }



	public static void main(String args[]){
	    IntentionParsing ip = getInstance();
	    ip.extractPreference("I want to have two books and you can take the rest");
        ip.extractPreference("I'd like to have two books and you can take the rest");
        ip.extractPreference("I don't like hats so you can have the two books");
    }

}