package edu.stanford.nlp.parser.nndep.feature;

import java.util.Collection;

public abstract class Feature {
	
	public boolean tweaked = false;
	public double[] embedding;

	public abstract void loadEmbedding(double[][] embedding);

	public abstract double[] getEmbedding();

	public abstract int getId();
	
	public abstract boolean isTweaked();

	public static void loadEmbeddings(Collection<Feature> features,
			double[][] embedding) {
		for (Feature feature : features) {
			feature.loadEmbedding(embedding);
		}
	}

}
