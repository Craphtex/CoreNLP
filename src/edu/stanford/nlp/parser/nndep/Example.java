package edu.stanford.nlp.parser.nndep;

import java.util.List;

import edu.stanford.nlp.parser.nndep.feature.Feature;

/**
 * @author Christopher Manning
 */
class Example {

  private final List<Feature> feature;
  private final List<Integer> label;

  public Example(List<Feature> feature, List<Integer> label) {
    this.feature = feature;
    this.label = label;
  }

  public List<Feature> getFeature() {
    return feature;
  }

  public List<Integer> getLabel() {
    return label;
  }

}
