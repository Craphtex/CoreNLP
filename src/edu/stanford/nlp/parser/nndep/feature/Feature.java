package edu.stanford.nlp.parser.nndep.feature;

import java.util.Collection;

public abstract class Feature {

	public abstract void loadEmbedding(double[][] embedding);

	public abstract double[] getEmbedding();

	public abstract int getId();

	public static void loadEmbedding(Collection<Feature> features,
			double[][] embedding) {
		for (Feature feature : features) {
			feature.loadEmbedding(embedding);
		}
	}

}
