package edu.stanford.nlp.parser.nndep.feature;

public class ArcFeature implements Feature {

	private int id;

	public ArcFeature(int id) {
		this.id = id;
	}

	public double[] getEmbedding(double[][] embedding) {
		return embedding[id];
	}

}
