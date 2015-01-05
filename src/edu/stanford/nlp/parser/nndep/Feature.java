package edu.stanford.nlp.parser.nndep;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.commons.lang3.builder.HashCodeBuilder;

import edu.stanford.nlp.ling.CoreLabel;

public abstract class Feature {

	protected int id;
	public boolean tweaked = false;
	private double[] embedding;

	public Feature(int id) {
		this.id = id;
	}

	public void loadEmbedding(double[][] embedding) {
		this.embedding = embedding[id];
	}

	public void overWriteEmbedding(double[] embedding) {
		this.embedding = embedding;
		this.tweaked = true;
	}

	public double[] getEmbedding() {
		return embedding;
	}

	public int getId() {
		return id;
	}

	public int getIndex(int j) {
		return id * Config.numTokens + j;
	}

	public boolean isTweaked() {
		return tweaked;
	}

	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof Feature))
			return false;
		return ((Feature) o).id == this.id
				&& Arrays.equals(((Feature) o).embedding, this.embedding);
	}

	public int hashCode() {
		return new HashCodeBuilder(6277, 5309).append(id).append(embedding)
				.build();
	}

	public static void loadEmbeddings(Collection<Feature> features,
			double[][] embedding) {
		for (Feature feature : features) {
			feature.loadEmbedding(embedding);
		}
	}

	public static class AdditionalFeature extends Feature {

		public AdditionalFeature(int id) {
			super(id);
		}

	}

	public static class ArcFeature extends Feature {

		public ArcFeature(int id) {
			super(id);
		}

	}

	public static class POSFeature extends Feature {

		public POSFeature(int id) {
			super(id);
		}

	}

	public static class WordFeature extends Feature {

		private boolean meanTweak;
		private boolean posTweak;

		public WordFeature(int id) {
			super(id);
		}

		public void applyMeanTweak(List<CoreLabel> sentence) {

		}

		public void applyPOSTweak(List<CoreLabel> sentence) {

		}

		public void loadEmbedding(double[][] embedding) {
			if (!tweaked)
				super.loadEmbedding(embedding);
		}

	}
}
