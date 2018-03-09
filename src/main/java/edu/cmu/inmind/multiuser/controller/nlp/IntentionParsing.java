package edu.cmu.inmind.multiuser.controller.nlp;

import edu.cmu.inmind.multiuser.controller.Sentence;
import edu.cmu.inmind.multiuser.controller.common.Preference;
import edu.cmu.inmind.multiuser.controller.common.Utils;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.naturalli.OpenIE;
import edu.stanford.nlp.naturalli.SentenceFragment;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.util.CoreMap;

import java.util.*;
import java.util.stream.Collectors;

public class IntentionParsing {
	private StanfordCoreNLP pipeline;
    private OpenIE clause_stance;
	private static IntentionParsing instance;
	private boolean verbose;

	private IntentionParsing() {
		// creates a StanfordCoreNLP object, with POS tagging, lemmatization,
		// NER, parsing, and coreference resolution
		verbose = Boolean.valueOf(Utils.getProperty("verbose"));
		Properties props = new Properties();
		props.setProperty("annotators", "tokenize, ssplit, pos, lemma,depparse,natlog,openie");
		pipeline = new StanfordCoreNLP(props);
		clause_stance = new OpenIE(props);
        System.out.println("After creating the StanfordCoreNLP and OpenIE instances");
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


    private void getLikes(SemanticGraph graph, Preference preference){
	    boolean foundNSUBJ = false, foundVB = false;
        for( SemanticGraphEdge node : graph.edgeListSorted() ) {
            if(node.getTarget().lemma().equalsIgnoreCase("I") && node.getRelation().toString().equals("nsubj")){
                foundNSUBJ = true;
            }else if(foundNSUBJ){
                if(!foundVB) {
                    String mainVerb = getVerb(node);
                    if (mainVerb == null)
                        continue;
                    if (mainVerb.equals("like") || mainVerb.equals("love")) {
                        foundVB = true;
                        preference.setType(Preference.LIKE);
                        preference.setLemaVerb(node.getSource().lemma());
                    } else if (mainVerb.equals("dislike") || mainVerb.equals("hate")) {
                        foundVB = true;
                        preference.setType(Preference.DISLIKE);
                        preference.setLemaVerb(node.getSource().lemma());
                    }
                }
                if(foundVB) {
                    if( node.getRelation().toString().equals("neg") && node.getSource().tag().equals("VB")
                            && preference.getType() != null){
                        // we are validating whether the verb uses an aux negation (e.g., I don't like books..)
                        preference.setType( Preference.DISLIKE );
                    }
                    if (node.getRelation().toString().equals("dobj") && node.getTarget().tag().contains("NN")){
                        preference.setLemmaObj( node.getTarget().lemma() );
                        return;
                    }
                }
            }else if (foundVB){
                preference.setLemmaObj( node.getTarget().lemma() );
                return;
            }
        }
    }

    private String getVerb(SemanticGraphEdge node){
        String source = node.getSource().tag();
        String target = node.getTarget().tag();
        return source.contains("VB")? node.getSource().lemma() : target.contains("VB")? node.getTarget().lemma() : null;
    }

    public List<String> clauseBreakSent(String original_Sent) {
        if( original_Sent != null && !original_Sent.isEmpty() ) {
            Annotation document = new Annotation(original_Sent);
            // run all Annotators on this text
            pipeline.annotate(document);
            List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);

            original_Sent = "";
            original_Sent=original_Sent.replace(" u "," you ");
            for (CoreMap sentence : sentences) {
                original_Sent += sentence;
            }

            List<String> clause_in_sentence = new ArrayList<>();
            for (CoreMap sentence : sentences) {
                List<String> final_clause_string = new ArrayList<>();
                List<CoreLabel> predicates = new ArrayList<>();
                List<CoreLabel> removeVerb = new ArrayList<>();

                HashMap<SentenceFragment, List<CoreLabel>> clause_info = new HashMap<>();
                HashMap<Integer, String> word_dic = new HashMap<>();

                // this is the Stanford dependency graph of the current sentence
                SemanticGraph dependencies = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
                for (TypedDependency depedency : dependencies.typedDependencies()) {
                    // System.out.println(depedency);

                    // System.out.println(depedency.reln());

                    //Remove all complementary verb
                    if(depedency.reln().toString().equals("xcomp")) {
                        CoreLabel dep_word=depedency.dep().backingLabel();
                        if(dep_word.get(CoreAnnotations.PartOfSpeechAnnotation.class).startsWith("V")){
                            removeVerb.add(depedency.gov().backingLabel());
                        }
                    }
                }

                for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                    // this is the POS tag of the token
                    String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                    word_dic.put(token.index(), token.get(CoreAnnotations.TextAnnotation.class));
                    //Extract all the verb in the sentence
                    if (pos.startsWith("V") && !removeVerb.contains(token)) {
                        predicates.add(token);
                    }
                }
                List<SentenceFragment> clauses = clause_stance.clausesInSentence(sentence);
                for (SentenceFragment clause : clauses) {
                    List<CoreLabel> words = clause.words;
                    List<CoreLabel> clause_predicates = new ArrayList<>();
                    for (CoreLabel predic : predicates) {
                        if (words.contains(predic)) {
                            System.out.print(predic + " ");
                            clause_predicates.add(predic);
                        }
                    }
                    clause_info.put(clause, clause_predicates);
                    System.out.println();
                }
                final_clause_string = removeLargeClause(clause_info, predicates, word_dic);
                clause_in_sentence.addAll(final_clause_string);
            }
            return clause_in_sentence;
        }
        return null;
    }


    private List<String> removeLargeClause(HashMap<SentenceFragment, List<CoreLabel>> clause_predicate,
                                           List<CoreLabel> predicates, HashMap<Integer, String> word_dic) {

        List<String> final_clause = new ArrayList<>();
        List<SentenceFragment> waitList_clause = new ArrayList<>();
        HashMap<CoreLabel, SentenceFragment> shortest_clause = new HashMap<>();
        for (int i = 0; i < predicates.size(); i++) {
            SentenceFragment candidate = null;
            int candid_contain_num_words = Integer.MAX_VALUE;
            CoreLabel current_predicate = predicates.get(i);
            for (SentenceFragment clause : clause_predicate.keySet()) {
                List<CoreLabel> pred_in_clause = clause_predicate.get(clause);
                if (pred_in_clause.contains(current_predicate) && clause.words.size() < candid_contain_num_words) {

                    candid_contain_num_words = clause.words.size();
                    candidate = clause;
                }
            }
            shortest_clause.put(current_predicate, candidate);
            //Update candidate predicate list by minus its only-predict
            if (clause_predicate.get(candidate).size() > 1) {
                // The segment contain at least on predict that only be in this segment
                waitList_clause.add(candidate);
            } else {
                final_clause.add(candidate.toString().trim());
            }
            clause_predicate.get(candidate).remove(current_predicate);
        }

        for (SentenceFragment wait_clause : waitList_clause) {
            Set<Integer> curr_wait_clause = new HashSet<Integer>(Arrays.stream(parseListLabel(wait_clause.words)).boxed().collect(Collectors.toList()));
            List<CoreLabel> wait_predicats = clause_predicate.get(wait_clause);
            Set<Integer> tokensets = new HashSet<>();
            for (CoreLabel rest_predicate : wait_predicats) {
                SentenceFragment corr_shortest = shortest_clause.get(rest_predicate);
                tokensets.addAll(longestForward(parseListLabel(corr_shortest.words)));
            }
            tokensets.retainAll(curr_wait_clause);
            curr_wait_clause.removeAll(tokensets);
            List<Integer> list = new ArrayList<Integer>(curr_wait_clause);
            String convert_string = "";
            for (int j = 0; j < list.size(); j++) {
                convert_string += word_dic.get(list.get(j)) + " ";
            }
            convert_string=convert_string.trim();
            System.out.println("CONVERTSTRING!!!!!: " + convert_string);
            if (!final_clause.contains(convert_string)) {
                final_clause.add(convert_string);
            }
            //Check clause word number threhould
        }
        return final_clause;
    }


    private int[] parseListLabel(List<CoreLabel> words) {
        int fragment_index[] = new int[words.size()];
        for (int i = 0; i < words.size(); i++) {
            fragment_index[i] = words.get(i).index();
        }
        return fragment_index;
    }


    private Set<Integer> longestForward(int[] arr) {
        int subSeqLength = 1;
        int longest = 1;

        for (int i = 0; i < arr.length - 1; i++) {
            if (arr[i] == arr[i + 1] - 1)//We need to check if the current is equal to the next
            {
                subSeqLength++;//if it is we increment
                if (subSeqLength > longest)//we assign the longest and new bounds
                {
                    longest = subSeqLength;
                }

            } else
                subSeqLength = 1;//else re-initiate the straight length
        }
        Set<Integer> tokenset = new HashSet<Integer>(Arrays.stream(arr).boxed().collect(Collectors.toList()));
        return tokenset;
    }




	public static void main(String args[]){
        IntentionParsing ip = getInstance();
        List<String> sentences = new ArrayList<>();
        sentences.add("I love books");
        sentences.add("you can take the hats because I love books");
        sentences.add("you can take the hats because I love reading books");
        sentences.add("I want to have two books and you can take the rest");
        sentences.add("I'd like to have two books and you can take the rest");
        sentences.add("I don't like hats so you can have the two books");


        for(String sentence :  sentences) {
            //System.out.println(ip.extractPreference(sentence));
            System.out.println(Arrays.toString(ip.clauseBreakSent(sentence).toArray()) );
        }
    }

}