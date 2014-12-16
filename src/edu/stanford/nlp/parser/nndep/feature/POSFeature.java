package edu.stanford.nlp.parser.nndep.feature;

public class POSFeature implements Feature {

	private int id;
	
	public POSFeature(int id) {
		this.id = id;
	}

	public double[] getEmbedding(double[][] embedding) {
		return embedding[id];
	}

}
