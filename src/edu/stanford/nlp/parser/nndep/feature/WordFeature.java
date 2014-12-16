package edu.stanford.nlp.parser.nndep.feature;

public class WordFeature implements Feature {

	private int id;

	public WordFeature(int id) {
		this.id = id;
	}

	public double[] getEmbedding(double[][] embedding) {
		return embedding[id];
	}

}
