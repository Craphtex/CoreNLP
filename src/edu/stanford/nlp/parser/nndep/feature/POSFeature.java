package edu.stanford.nlp.parser.nndep.feature;

public class POSFeature extends Feature {

	private int id;
	private double[] embedding;
	
	public POSFeature(int id) {
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
