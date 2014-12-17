package edu.stanford.nlp.parser.nndep.feature;

import java.util.List;

import edu.stanford.nlp.ling.CoreLabel;

public class WordFeature extends Feature {

	private int id;
	private boolean meanTweak;
	private boolean posTweak;

	public WordFeature(int id) {
		this.id = id;
	}

	public void applyMeanTweak(List<CoreLabel> sentence) {
		
	}

	public void applyPOSTweak(List<CoreLabel> sentence) {
		
	}

	public boolean isTweaked() {
		return tweaked;
	}

	public void loadEmbedding(double[][] embedding) {
		this.embedding = embedding[id];
	}

	public double[] getEmbedding() {
		return embedding;
	}

	public int getId() {
		return id;
	}

}
