package edu.stanford.nlp.parser.nndep.feature;

public class ArcFeature extends Feature {

	private int id;
	private double[] embedding;

	public ArcFeature(int id) {
		this.id = id;
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
