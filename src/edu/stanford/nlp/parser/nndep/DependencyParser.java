package edu.stanford.nlp.parser.nndep;

import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasTag;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.stats.IntCounter;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.trees.EnglishGrammaticalRelations;
import edu.stanford.nlp.trees.EnglishGrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.trees.TreeGraphNode;
import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.trees.international.pennchinese.ChineseGrammaticalRelations;
import edu.stanford.nlp.trees.international.pennchinese.ChineseGrammaticalStructure;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.StringUtils;
import edu.stanford.nlp.util.Timing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import static java.util.stream.Collectors.toList;

/**
 * This class defines a transition-based dependency parser which makes
 * use of a classifier powered by a neural network. The neural network
 * accepts distributed representation inputs: dense, continuous
 * representations of words, their part of speech tags, and the labels
 * which connect words in a partial dependency parse.
 *
 * <p>
 * This is an implementation of the method described in
 *
 * <blockquote>
 *   Danqi Chen and Christopher Manning. A Fast and Accurate Dependency
 *   Parser Using Neural Networks. In EMNLP 2014.
 * </blockquote>
 *
 * <p>
 * New models can be trained from the command line; see {@link #main}
 * for details on training options. This parser will also output
 * CoNLL-X format predictions; again see {@link #main} for available
 * options.
 *
 * <p>
 * This parser can also be used programmatically. The easiest way to
 * prepare the parser with a pre-trained model is to call
 * {@link #loadFromModelFile(String)}. Then call
 * {@link #predict(edu.stanford.nlp.util.CoreMap)} on the returned
 * parser instance in order to get new parses.
 *
 * @author Danqi Chen (danqi@cs.stanford.edu)
 * @author Jon Gauthier
 */
public class DependencyParser {
  public static final String DEFAULT_MODEL = "edu/stanford/nlp/models/parser/nndep/PTB_Stanford_params.txt.gz";

  /**
   * Words, parts of speech, and dependency relation labels which were
   * observed in our corpus / stored in the model
   *
   * @see #genDictionaries(java.util.List, java.util.List)
   */
  private List<String> knownWords, knownPos, knownLabels;

  /**
   * Mapping from word / POS / dependency relation label to integer ID
   */
  private Map<String, Integer> wordIDs, posIDs, labelIDs;

  private List<Integer> preComputed;

  /**
   * Given a particular parser configuration, this classifier will
   * predict the best transition to make next.
   *
   * The {@link edu.stanford.nlp.parser.nndep.Classifier} class
   * handles both training and inference.
   */
  private Classifier classifier;

  private ParsingSystem system;

  private Map<String, Integer> embedID;
  private double[][] embeddings;

  private final Config config;

  /**
   * Language used to generate
   * {@link edu.stanford.nlp.trees.GrammaticalRelation} instances.
   */
  private final GrammaticalRelation.Language language;

  DependencyParser() {
    this(new Properties());
  }

  public DependencyParser(Properties properties) {
    config = new Config(properties);

    // Convert Languages.Language instance to
    // GrammaticalLanguage.Language
    switch (config.language) {
      case English:
        language = GrammaticalRelation.Language.English;
        break;
      case Chinese:
        language = GrammaticalRelation.Language.Chinese;
        break;
      case Unknown:
        language = GrammaticalRelation.Language.Any;
        break;
      default:
        language = GrammaticalRelation.Language.English;  // note[gabor]: This is to conform to the default in the Parser annotator
        break;
    }
  }

  /**
   * Get an integer ID for the given word. This ID can be used to index
   * into the embeddings {@link #embeddings}.
   *
   * @return An ID for the given word, or an ID referring to a generic
   *         "unknown" word if the word is unknown
   */
  public int getWordID(String s) {
      return wordIDs.containsKey(s) ? wordIDs.get(s) : wordIDs.get(Config.UNKNOWN);
  }

  public int getPosID(String s) {
      return posIDs.containsKey(s) ? posIDs.get(s) : posIDs.get(Config.UNKNOWN);
  }

  public int getLabelID(String s) {
    return labelIDs.get(s);
  }

  public List<Feature> getFeatures(Configuration c, Map<Integer, double[]> featureEmbedding) {
    // Presize the arrays for very slight speed gain. Hardcoded, but so is the current feature list.
    List<Feature> fWord = new ArrayList<Feature>(18);
    List<Feature> fPos = new ArrayList<Feature>(18);
    List<Feature> fLabel = new ArrayList<Feature>(12);
    List<Feature> fAdditional = new ArrayList<Feature>(18);
    for (int j = 2; j >= 0; --j) {
      int index = c.getStack(j);
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
    }
    for (int j = 0; j <= 2; ++j) {
      int index = c.getBuffer(j);
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
    }
    for (int j = 0; j <= 1; ++j) {
      int k = c.getStack(j);
      int index = c.getLeftChild(k);
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
      fLabel.add(new Feature.ArcFeature(getLabelID(c.getLabel(index))));

      index = c.getRightChild(k);
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
      fLabel.add(new Feature.ArcFeature(getLabelID(c.getLabel(index))));

      index = c.getLeftChild(k, 2);
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
      fLabel.add(new Feature.ArcFeature(getLabelID(c.getLabel(index))));

      index = c.getRightChild(k, 2);
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
      fLabel.add(new Feature.ArcFeature(getLabelID(c.getLabel(index))));

      index = c.getLeftChild(c.getLeftChild(k));
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
      fLabel.add(new Feature.ArcFeature(getLabelID(c.getLabel(index))));

      index = c.getRightChild(c.getRightChild(k));
      fWord.add(new Feature.WordFeature(getWordID(c.getWord(index))));
      fPos.add(new Feature.POSFeature(getPosID(c.getPOS(index))));
      fLabel.add(new Feature.ArcFeature(getLabelID(c.getLabel(index))));
    }

    List<Feature> features = new ArrayList<>(48);
    features.addAll(fWord);
    features.addAll(fPos);
    features.addAll(fLabel);

    if (featureEmbedding != null && !featureEmbedding.isEmpty()) {
      Feature.loadEmbeddings(features, classifier.getE());
      if (config.featureModeReplace) {
        for (Feature feature : features) {
          if (featureEmbedding.containsKey(feature.getId())){
            feature.overWriteEmbedding(featureEmbedding.get(feature.getId()));
          }
        }
      }
      else {
        for (Feature feature : fWord) {
          if (featureEmbedding.containsKey(feature.getId())){
            fAdditional.add(new Feature.AdditionalFeature(featureEmbedding.get(feature.getId())));
          }
        }
        features.addAll(fAdditional);
      }
    }
    return features;
  }

  private static final int POS_OFFSET = 18;
  private static final int DEP_OFFSET = 36;
  private static final int STACK_OFFSET = 6;
  private static final int STACK_NUMBER = 6;

  /*
  private int[] getFeatureArray(Configuration c) {
    int[] feature = new int[config.numTokens];  // positions 0-17 hold fWord, 18-35 hold fPos, 36-47 hold fLabel

    for (int j = 2; j >= 0; --j) {
      int index = c.getStack(j);
      feature[2-j] = getWordID(c.getWord(index));
      feature[POS_OFFSET + (2-j)] = getPosID(c.getPOS(index));
    }

    for (int j = 0; j <= 2; ++j) {
      int index = c.getBuffer(j);
      feature[3 + j] = getWordID(c.getWord(index));
      feature[POS_OFFSET + 3 + j] = getPosID(c.getPOS(index));
    }

    for (int j = 0; j <= 1; ++j) {
      int k = c.getStack(j);

      int index = c.getLeftChild(k);
      feature[STACK_OFFSET + j * STACK_NUMBER] = getWordID(c.getWord(index));
      feature[POS_OFFSET + STACK_OFFSET + j * STACK_NUMBER] = getPosID(c.getPOS(index));
      feature[DEP_OFFSET + j * STACK_NUMBER] = getLabelID(c.getLabel(index));

      index = c.getRightChild(k);
      feature[STACK_OFFSET + j * STACK_NUMBER + 1] = getWordID(c.getWord(index));
      feature[POS_OFFSET + STACK_OFFSET + j * STACK_NUMBER + 1] = getPosID(c.getPOS(index));
      feature[DEP_OFFSET + j * STACK_NUMBER + 1] = getLabelID(c.getLabel(index));

      index = c.getLeftChild(k, 2);
      feature[STACK_OFFSET + j * STACK_NUMBER + 2] = getWordID(c.getWord(index));
      feature[POS_OFFSET + STACK_OFFSET + j * STACK_NUMBER + 2] = getPosID(c.getPOS(index));
      feature[DEP_OFFSET + j * STACK_NUMBER + 2] = getLabelID(c.getLabel(index));

      index = c.getRightChild(k, 2);
      feature[STACK_OFFSET + j * STACK_NUMBER + 3] = getWordID(c.getWord(index));
      feature[POS_OFFSET + STACK_OFFSET + j * STACK_NUMBER + 3] = getPosID(c.getPOS(index));
      feature[DEP_OFFSET + j * STACK_NUMBER + 3] = getLabelID(c.getLabel(index));

      index = c.getLeftChild(c.getLeftChild(k));
      feature[STACK_OFFSET + j * STACK_NUMBER + 4] = getWordID(c.getWord(index));
      feature[POS_OFFSET + STACK_OFFSET + j * STACK_NUMBER + 4] = getPosID(c.getPOS(index));
      feature[DEP_OFFSET + j * STACK_NUMBER + 4] = getLabelID(c.getLabel(index));

      index = c.getRightChild(c.getRightChild(k));
      feature[STACK_OFFSET + j * STACK_NUMBER + 5] = getWordID(c.getWord(index));
      feature[POS_OFFSET + STACK_OFFSET + j * STACK_NUMBER + 5] = getPosID(c.getPOS(index));
      feature[DEP_OFFSET + j * STACK_NUMBER + 5] = getLabelID(c.getLabel(index));
    }

    return feature;
  }
  */

  public Dataset genTrainExamples(List<CoreMap> sents, List<DependencyTree> trees) {
    Dataset ret = new Dataset(config.numTokens, system.transitions.size());

    Counter<Integer> tokPosCount = new IntCounter<>();
    System.err.println(Config.SEPARATOR);
    System.err.println("Generate training examples...");

    for (int i = 0; i < sents.size(); ++i) {

      if (i > 0) {
        if (i % 1000 == 0)
          System.err.print(i + " ");
        if (i % 10000 == 0 || i == sents.size() - 1)
          System.err.println();
      }

      if (trees.get(i).isProjective()) {
        Configuration c = system.initialConfiguration(sents.get(i));

        while (!system.isTerminal(c)) {
          String oracle = system.getOracle(c, trees.get(i));
          List<Feature> feature = getFeatures(c, null);
          List<Integer> label = new ArrayList<>();
          for (int j = 0; j < system.transitions.size(); ++j) {
            String str = system.transitions.get(j);
            if (str.equals(oracle)) label.add(1);
            else if (system.canApply(c, str)) label.add(0);
            else label.add(-1);
          }

          ret.addExample(feature, label);
          for (int j = 0; j < feature.size(); ++j)
            tokPosCount.incrementCount(feature.get(j).getId() * feature.size() + j);
          system.apply(c, oracle);
        }
      }
    }
    System.err.println("#Train Examples: " + ret.n);

    preComputed = new ArrayList<>(config.numPreComputed);
    List<Integer> sortedTokens = Counters.toSortedList(tokPosCount, false);

    preComputed = new ArrayList<>(sortedTokens.subList(0, Math.min(config.numPreComputed, sortedTokens.size())));

    return ret;
  }

  /**
   * Generate unique integer IDs for all known words / part-of-speech
   * tags / dependency relation labels.
   *
   * All three of the aforementioned types are assigned IDs from a
   * continuous range of integers; all IDs 0 <= ID < n_w are word IDs,
   * all IDs n_w <= ID < n_w + n_pos are POS tag IDs, and so on.
   */
  private void generateIDs() {
    wordIDs = new HashMap<>();
    posIDs = new HashMap<>();
    labelIDs = new HashMap<>();

    int index = 0;
    for (String word : knownWords)
      wordIDs.put(word, (index++));
    for (String pos : knownPos)
      posIDs.put(pos, (index++));
    for (String label : knownLabels)
      labelIDs.put(label, (index++));
  }

  /**
   * Scan a corpus and store all words, part-of-speech tags, and
   * dependency relation labels observed. Prepare other structures
   * which support word / POS / label lookup at train- / run-time.
   */
  private void genDictionaries(List<CoreMap> sents, List<DependencyTree> trees) {
    // Collect all words (!), etc. in lists, tacking on one sentence
    // after the other
    List<String> word = new ArrayList<>();
    List<String> pos = new ArrayList<>();
    List<String> label = new ArrayList<>();

    for (CoreMap sentence : sents) {
      List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);

      for (CoreLabel token : tokens) {
        word.add(token.word());
        pos.add(token.tag());
      }
    }

    String rootLabel = null;
    for (DependencyTree tree : trees)
      for (int k = 1; k <= tree.n; ++k)
        if (tree.getHead(k) == 0)
          rootLabel = tree.getLabel(k);
        else
          label.add(tree.getLabel(k));

    // Generate "dictionaries," possibly with frequency cutoff
    knownWords = Util.generateDict(word, config.wordCutOff);
    knownPos = Util.generateDict(pos);
    knownLabels = Util.generateDict(label);
    knownLabels.add(0, rootLabel);

    knownWords.add(0, Config.UNKNOWN);
    knownWords.add(1, Config.NULL);
    knownWords.add(2, Config.ROOT);

    knownPos.add(0, Config.UNKNOWN);
    knownPos.add(1, Config.NULL);
    knownPos.add(2, Config.ROOT);

    knownLabels.add(0, Config.NULL);
    generateIDs();

    System.err.println(Config.SEPARATOR);
    System.err.println("#Word: " + knownWords.size());
    System.err.println("#POS:" + knownPos.size());
    System.err.println("#Label: " + knownLabels.size());
  }

  public void writeModelFile(String modelFile) {
    try {
      double[][] W1 = classifier.getW1();
      double[] b1 = classifier.getb1();
      double[][] W2 = classifier.getW2();
      double[][] E = classifier.getE();

      Writer output = IOUtils.getPrintWriter(modelFile);

      output.write("dict=" + knownWords.size() + "\n");
      output.write("pos=" + knownPos.size() + "\n");
      output.write("label=" + knownLabels.size() + "\n");
      output.write("embeddingSize=" + E[0].length + "\n");
      output.write("hiddenSize=" + b1.length + "\n");
      output.write("numTokens=" + (W1[0].length / E[0].length) + "\n");
      output.write("preComputed=" + preComputed.size() + "\n");

      int index = 0;

      // First write word / POS / label embeddings
      for (String word : knownWords) {
        output.write(word);
        for (int k = 0; k < E[index].length; ++k)
          output.write(" " + E[index][k]);
        output.write("\n");
        index = index + 1;
      }
      for (String pos : knownPos) {
        output.write(pos);
        for (int k = 0; k < E[index].length; ++k)
          output.write(" " + E[index][k]);
        output.write("\n");
        index = index + 1;
      }
      for (String label : knownLabels) {
        output.write(label);
        for (int k = 0; k < E[index].length; ++k)
          output.write(" " + E[index][k]);
        output.write("\n");
        index = index + 1;
      }

      // Now write classifier weights
      for (int j = 0; j < W1[0].length; ++j)
        for (int i = 0; i < W1.length; ++i) {
          output.write("" + W1[i][j]);
          if (i == W1.length - 1)
            output.write("\n");
          else
            output.write(" ");
        }
      for (int i = 0; i < b1.length; ++i) {
        output.write("" + b1[i]);
        if (i == b1.length - 1)
          output.write("\n");
        else
          output.write(" ");
      }
      for (int j = 0; j < W2[0].length; ++j)
        for (int i = 0; i < W2.length; ++i) {
          output.write("" + W2[i][j]);
          if (i == W2.length - 1)
            output.write("\n");
          else
            output.write(" ");
        }

      // Finish with pre-computation info
      for (int i = 0; i < preComputed.size(); ++i) {
        output.write("" + preComputed.get(i));
        if ((i + 1) % 100 == 0 || i == preComputed.size() - 1)
          output.write("\n");
        else
          output.write(" ");
      }

      output.close();
    } catch (IOException e) {
      System.out.println(e);
    }
  }

  /**
   * Convenience method; see {@link #loadFromModelFile(String, java.util.Properties)}.
   *
   * @see #loadFromModelFile(String, java.util.Properties)
   */
  public static DependencyParser loadFromModelFile(String modelFile) {
    return loadFromModelFile(modelFile, null);
  }

  /**
   * Load a saved parser model.
   *
   * @param modelFile       Path to serialized model (may be GZipped)
   * @param extraProperties Extra test-time properties not already associated with model (may be null)
   *
   * @return Loaded and initialized (see {@link #initialize(boolean)} model
   */
  public static DependencyParser loadFromModelFile(String modelFile, Properties extraProperties) {
    DependencyParser parser = extraProperties == null ? new DependencyParser() : new DependencyParser(extraProperties);
    parser.loadModelFile(modelFile, false);
    return parser;
  }

  /** Load a parser model file, printing out some messages about the grammar in the file.
   *
   *  @param modelFile The file (classpath resource, etc.) to load the model from.
   */
  public void loadModelFile(String modelFile) {
    loadModelFile(modelFile, true);
  }

  private void loadModelFile(String modelFile, boolean verbose) {
    Timing t = new Timing();
    try {

      System.err.println("Loading depparse model file: " + modelFile + " ... ");
      String s;
      BufferedReader input = IOUtils.readerFromString(modelFile);

      int nDict, nPOS, nLabel;
      int eSize, hSize, nTokens, nPreComputed;
      nDict = nPOS = nLabel = eSize = hSize = nTokens = nPreComputed = 0;

      for (int k = 0; k < 7; ++k) {
        s = input.readLine();
        if (verbose) {
          System.err.println(s);
        }
        int number = Integer.parseInt(s.substring(s.indexOf('=') + 1));
        switch (k) {
          case 0:
            nDict = number;
            break;
          case 1:
            nPOS = number;
            break;
          case 2:
            nLabel = number;
            break;
          case 3:
            eSize = number;
            break;
          case 4:
            hSize = number;
            break;
          case 5:
            nTokens = number;
            break;
          case 6:
            nPreComputed = number;
            break;
          default:
            break;
        }
      }


      knownWords = new ArrayList<String>();
      knownPos = new ArrayList<String>();
      knownLabels = new ArrayList<String>();
      double[][] E = new double[nDict + nPOS + nLabel][eSize];
      String[] splits;
      int index = 0;

      for (int k = 0; k < nDict; ++k) {
        s = input.readLine();
        splits = s.split(" ");
        knownWords.add(splits[0]);
        for (int i = 0; i < eSize; ++i)
          E[index][i] = Double.parseDouble(splits[i + 1]);
        index = index + 1;
      }
      for (int k = 0; k < nPOS; ++k) {
        s = input.readLine();
        splits = s.split(" ");
        knownPos.add(splits[0]);
        for (int i = 0; i < eSize; ++i)
          E[index][i] = Double.parseDouble(splits[i + 1]);
        index = index + 1;
      }
      for (int k = 0; k < nLabel; ++k) {
        s = input.readLine();
        splits = s.split(" ");
        knownLabels.add(splits[0]);
        for (int i = 0; i < eSize; ++i)
          E[index][i] = Double.parseDouble(splits[i + 1]);
        index = index + 1;
      }
      generateIDs();

      double[][] W1 = new double[hSize][eSize * nTokens];
      for (int j = 0; j < W1[0].length; ++j) {
        s = input.readLine();
        splits = s.split(" ");
        for (int i = 0; i < W1.length; ++i)
          W1[i][j] = Double.parseDouble(splits[i]);
      }

      double[] b1 = new double[hSize];
      s = input.readLine();
      splits = s.split(" ");
      for (int i = 0; i < b1.length; ++i)
        b1[i] = Double.parseDouble(splits[i]);

      double[][] W2 = new double[nLabel * 2 - 1][hSize];
      for (int j = 0; j < W2[0].length; ++j) {
        s = input.readLine();
        splits = s.split(" ");
        for (int i = 0; i < W2.length; ++i)
          W2[i][j] = Double.parseDouble(splits[i]);
      }

      preComputed = new ArrayList<Integer>();
      while (preComputed.size() < nPreComputed) {
        s = input.readLine();
        splits = s.split(" ");
        for (String split : splits) {
          preComputed.add(Integer.parseInt(split));
        }
      }
      input.close();
      classifier = new Classifier(config, E, W1, b1, W2, preComputed);
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }

    // initialize the loaded parser
    initialize(verbose);
    t.done("Initializing dependency parser");
  }

  // TODO this should be a function which returns the embeddings array + embedID
  // otherwise the class needlessly carries around the extra baggage of `embeddings`
  // (never again used) for the entire training process
  private void readEmbedFile(String embedFile) {
    embedID = new HashMap<String, Integer>();
    if (embedFile == null)
      return;
    BufferedReader input = null;
    try {
      input = IOUtils.readerFromString(embedFile);
      List<String> lines = new ArrayList<String>();
      for (String s; (s = input.readLine()) != null; ) {
        lines.add(s);
      }

      int nWords = lines.size();
      String[] splits = lines.get(0).split("\\s+");

      int dim = splits.length - 1;
      embeddings = new double[nWords][dim];
      System.err.println("Embedding File " + embedFile + ": #Words = " + nWords + ", dim = " + dim);

      if (dim != config.embeddingSize)
          throw new IllegalArgumentException("The dimension of embedding file does not match config.embeddingSize");

      for (int i = 0; i < lines.size(); ++i) {
        splits = lines.get(i).split("\\s+");
        embedID.put(splits[0], i);
        for (int j = 0; j < dim; ++j)
          embeddings[i][j] = Double.parseDouble(splits[j + 1]);
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    } finally {
      IOUtils.closeIgnoringExceptions(input);
    }
  }

  /**
   * Train a new dependency parser model.
   *
   * @param trainFile Training data
   * @param devFile Development data (used for regular UAS evaluation
   *                of model)
   * @param modelFile String to which model should be saved
   * @param embedFile File containing word embeddings for words used in
   *                  training corpus
   */
  public void train(String trainFile, String devFile, String modelFile, String embedFile) {
    System.err.println("Train File: " + trainFile);
    System.err.println("Dev File: " + devFile);
    System.err.println("Model File: " + modelFile);
    System.err.println("Embedding File: " + embedFile);

    List<CoreMap> trainSents = new ArrayList<>();
    List<DependencyTree> trainTrees = new ArrayList<DependencyTree>();
    Util.loadConllFile(trainFile, trainSents, trainTrees);
    Util.printTreeStats("Train", trainTrees);

    List<CoreMap> devSents = new ArrayList<CoreMap>();
    List<DependencyTree> devTrees = new ArrayList<DependencyTree>();
    if (devFile != null) {
      Util.loadConllFile(devFile, devSents, devTrees);
      Util.printTreeStats("Dev", devTrees);
    }
    genDictionaries(trainSents, trainTrees);

    //NOTE: remove -NULL-, and the pass it to ParsingSystem
    List<String> lDict = new ArrayList<String>(knownLabels);
    lDict.remove(0);
    system = new ArcStandard(config.tlp, lDict, true);

    // Initialize a classifier; prepare for training
    setupClassifierForTraining(trainSents, trainTrees, embedFile);

    System.err.println(Config.SEPARATOR);
    config.printParameters();

    long startTime = System.currentTimeMillis();
    /**
     * Track the best UAS performance we've seen.
     */
    double bestUAS = 0;

    for (int iter = 0; iter < config.maxIter; ++iter) {
      System.err.println("##### Iteration " + iter);

      Classifier.Cost cost = classifier.computeCostFunction(config.batchSize, config.regParameter, config.dropProb);
      System.err.println("Cost = " + cost.getCost() + ", Correct(%) = " + cost.getPercentCorrect());
      classifier.takeAdaGradientStep(cost, config.adaAlpha, config.adaEps);

      System.err.println("Elapsed Time: " + (System.currentTimeMillis() - startTime) / 1000.0 + " (s)");

      // UAS evaluation
      if (devFile != null && iter % config.evalPerIter == 0) {
        // Redo precomputation with updated weights. This is only
        // necessary because we're updating weights -- for normal
        // prediction, we just do this once in #initialize
        classifier.preCompute();

        List<DependencyTree> predicted = devSents.stream().map(this::predictInner).collect(toList());

        double uas = system.getUASScore(devSents, predicted, devTrees);
        System.err.println("UAS: " + uas);

        if (config.saveIntermediate && uas > bestUAS) {
          System.err.printf("Exceeds best previous UAS of %f. Saving model file..%n", bestUAS);

          bestUAS = uas;
          writeModelFile(modelFile);
        }
      }

      // Clear gradients
      if (config.clearGradientsPerIter > 0 && iter % config.clearGradientsPerIter == 0) {
        System.err.println("Clearing gradient histories..");
        classifier.clearGradientHistories();
      }
    }

    classifier.finalizeTraining();

    if (devFile != null) {
      // Do final UAS evaluation and save if final model beats the
      // best intermediate one
      List<DependencyTree> predicted = devSents.stream().map(this::predictInner).collect(toList());
      double uas = system.getUASScore(devSents, predicted, devTrees);

      if (uas > bestUAS) {
        System.err.printf("Final model UAS: %f%n", uas);
        System.err.printf("Exceeds best previous UAS of %f. Saving model file..%n", bestUAS);

        writeModelFile(modelFile);
      }
    } else {
      writeModelFile(modelFile);
    }
  }

  /**
   * @see #train(String, String, String, String)
   */
  public void train(String trainFile, String devFile, String modelFile) {
    train(trainFile, devFile, modelFile, null);
  }

  /**
   * @see #train(String, String, String, String)
   */
  public void train(String trainFile, String modelFile) {
    train(trainFile, null, modelFile);
  }

  /**
   * Prepare a classifier for training with the given dataset.
   */
  private void setupClassifierForTraining(List<CoreMap> trainSents, List<DependencyTree> trainTrees, String embedFile) {
    double[][] E = new double[knownWords.size() + knownPos.size() + knownLabels.size()][config.embeddingSize];
    double[][] W1 = new double[config.hiddenSize][config.embeddingSize * config.numTokens];
    double[] b1 = new double[config.hiddenSize];
    double[][] W2 = new double[knownLabels.size() * 2 - 1][config.hiddenSize];

    // Randomly initialize weight matrices / vectors
    Random random = Util.getRandom();
    for (int i = 0; i < W1.length; ++i)
      for (int j = 0; j < W1[i].length; ++j)
        W1[i][j] = random.nextDouble() * 2 * config.initRange - config.initRange;

    for (int i = 0; i < b1.length; ++i)
      b1[i] = random.nextDouble() * 2 * config.initRange - config.initRange;

    for (int i = 0; i < W2.length; ++i)
      for (int j = 0; j < W2[i].length; ++j)
        W2[i][j] = random.nextDouble() * 2 * config.initRange - config.initRange;

    // Read embeddings into `embedID`, `embeddings`
    readEmbedFile(embedFile);

    // Try to match loaded embeddings with words in dictionary
    int foundEmbed = 0;
    for (int i = 0; i < E.length; ++i) {
      int index = -1;
      if (i < knownWords.size()) {
        String str = knownWords.get(i);
        //NOTE: exact match first, and then try lower case..
        if (embedID.containsKey(str)) index = embedID.get(str);
        else if (embedID.containsKey(str.toLowerCase())) index = embedID.get(str.toLowerCase());
      }

      if (index >= 0) {
        ++foundEmbed;
        for (int j = 0; j < E[i].length; ++j)
          E[i][j] = embeddings[index][j];
      } else {
        for (int j = 0; j < E[i].length; ++j)
          E[i][j] = random.nextDouble() * config.initRange * 2 - config.initRange;
      }
    }
    System.err.println("Found embeddings: " + foundEmbed + " / " + knownWords.size());

    Dataset trainSet = genTrainExamples(trainSents, trainTrees);
    classifier = new Classifier(config, trainSet, E, W1, b1, W2, preComputed);
  }

  /**
   * Determine the dependency parse of the given sentence.
   * <p>
   * This "inner" method returns a structure unique to this package; use {@link #predict(edu.stanford.nlp.util.CoreMap)}
   * for general parsing purposes.
   */
  private DependencyTree predictInner(CoreMap sentence) {
    int numTrans = system.transitions.size();
    Map<Integer, double[]> featureEmbeddings = Util.getFeaturesEmbeddings(sentence, classifier, config, this);

    Configuration c = system.initialConfiguration(sentence);
    
    while (!system.isTerminal(c)) {
      double[] scores = classifier.computeScores(getFeatures(c, featureEmbeddings));

      double optScore = Double.NEGATIVE_INFINITY;
      String optTrans = null;

      for (int j = 0; j < numTrans; ++j) {
        if (scores[j] > optScore && system.canApply(c, system.transitions.get(j))) {
          optScore = scores[j];
          optTrans = system.transitions.get(j);
        }
      }
      system.apply(c, optTrans);
    }
    return c.tree;
  }

  /**
   * Determine the dependency parse of the given sentence using the loaded model.
   * You must first load a parser before calling this method.
   *
   * @throws java.lang.IllegalStateException If parser has not yet been loaded and initialized
   *         (see {@link #initialize(boolean)}
   */
  public GrammaticalStructure predict(CoreMap sentence) {
    if (system == null)
      throw new IllegalStateException("Parser has not been  " +
          "loaded and initialized; first load a model.");

    DependencyTree result = predictInner(sentence);

    // The rest of this method is just busy-work to convert the
    // package-local representation into a CoreNLP-standard
    // GrammaticalStructure.

    List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
    List<TypedDependency> dependencies = new ArrayList<>();

    IndexedWord root = new IndexedWord(new Word("ROOT"));
    root.set(CoreAnnotations.IndexAnnotation.class, 0);

    for (int i = 1; i <= result.n; i++) {
      int head = result.getHead(i);
      String label = result.getLabel(i);

      IndexedWord thisWord = new IndexedWord(tokens.get(i - 1));
      IndexedWord headWord = head == 0 ? root
                                       : new IndexedWord(tokens.get(head - 1));

      GrammaticalRelation relation = head == 0
                                     ? GrammaticalRelation.ROOT
                                     : makeGrammaticalRelation(label);

      dependencies.add(new TypedDependency(relation, headWord, thisWord));
    }

    // Build GrammaticalStructure
    // TODO ideally submodule should just return GrammaticalStructure
    TreeGraphNode rootNode = new TreeGraphNode(root);
    return makeGrammaticalStructure(dependencies, rootNode);
  }

  private GrammaticalRelation makeGrammaticalRelation(String label) {
    GrammaticalRelation stored;

    switch (language) {
      case English:
        stored = EnglishGrammaticalRelations.shortNameToGRel.get(label);
        if (stored != null)
          return stored;
        break;
      case Chinese:
        stored = ChineseGrammaticalRelations.shortNameToGRel.get(label);
        if (stored != null)
          return stored;
        break;
    }

    return new GrammaticalRelation(language, label, null, GrammaticalRelation.DEPENDENT);
  }

  private GrammaticalStructure makeGrammaticalStructure(List<TypedDependency> dependencies, TreeGraphNode rootNode) {
    switch (language) {
      case English: return new EnglishGrammaticalStructure(dependencies, rootNode);
      case Chinese: return new ChineseGrammaticalStructure(dependencies, rootNode);

      // TODO suboptimal: default to EnglishGrammaticalStructure return
      default: return new EnglishGrammaticalStructure(dependencies, rootNode);
    }
  }

  /**
   * Convenience method for {@link #predict(edu.stanford.nlp.util.CoreMap)}. The tokens of the provided sentence must
   * also have tag annotations (the parser requires part-of-speech tags).
   *
   * @see #predict(edu.stanford.nlp.util.CoreMap)
   */
  public GrammaticalStructure predict(List<? extends HasWord> sentence) {
    CoreLabel sentenceLabel = new CoreLabel();
    List<CoreLabel> tokens = new ArrayList<>();

    int i = 1;
    for (HasWord wd : sentence) {
      CoreLabel label;
      if (wd instanceof CoreLabel) {
        label = (CoreLabel) wd;
        if (label.tag() == null)
          throw new IllegalArgumentException("Parser requires words " +
              "with part-of-speech tag annotations");
      } else {
        label = new CoreLabel();
        label.setValue(wd.word());
        label.setWord(wd.word());

        if (!(wd instanceof HasTag))
          throw new IllegalArgumentException("Parser requires words " +
              "with part-of-speech tag annotations");

        label.setTag(((HasTag) wd).tag());
      }

      label.setIndex(i);
      i++;

      tokens.add(label);
    }

    sentenceLabel.set(CoreAnnotations.TokensAnnotation.class, tokens);

    return predict(sentenceLabel);
  }

  //TODO: support sentence-only files as input

  /** Run the parser in the modelFile on a testFile and perhaps save output.
   *
   *  @param testFile File to parse. In CoNLL-X format. Assumed to have gold answers included.
   *  @param outFile File to write results to in CoNLL-X format.  If null, no output is written
   *  @return The LAS score on the dataset
   */
  public double testCoNLL(String testFile, String outFile) {
    System.err.println("Test File: " + testFile);
    Timing timer = new Timing();
    List<CoreMap> testSents = new ArrayList<>();
    List<DependencyTree> testTrees = new ArrayList<DependencyTree>();
    Util.loadConllFile(testFile, testSents, testTrees);
    // count how much to parse
    int numWords = 0;
    int numSentences = 0;
    for (CoreMap testSent : testSents) {
      numSentences += 1;
      numWords += testSent.get(CoreAnnotations.TokensAnnotation.class).size();
    }

    List<DependencyTree> predicted = testSents.stream().map(this::predictInner).collect(toList());
    Map<String, Double> result = system.evaluate(testSents, predicted, testTrees);
    
    double uasNoPunc = result.get("UASwoPunc");
    double lasNoPunc = result.get("LASwoPunc");
    System.err.printf("UAS = %.4f%n", uasNoPunc);
    System.err.printf("LAS = %.4f%n", lasNoPunc);

    long millis = timer.stop();
    double wordspersec = numWords / (((double) millis) / 1000);
    double sentspersec = numSentences / (((double) millis) / 1000);
    System.err.printf("%s parsed %d words in %d sentences in %.1fs at %.1f w/s, %.1f sent/s.%n",
            StringUtils.getShortClassName(this), numWords, numSentences, millis / 1000.0, wordspersec, sentspersec);

    if (outFile != null) {
        Util.writeConllFile(outFile, testSents, predicted);
    }
    return lasNoPunc;
  }

  private void parseTextFile(BufferedReader input, PrintWriter output) {
    DocumentPreprocessor preprocessor = new DocumentPreprocessor(input);
    preprocessor.setSentenceFinalPuncWords(config.tlp.sentenceFinalPunctuationWords());
    preprocessor.setEscaper(config.escaper);
    preprocessor.setSentenceDelimiter(config.sentenceDelimiter);
    preprocessor.setTokenizerFactory(config.tlp.getTokenizerFactory());

    Timing timer = new Timing();

    MaxentTagger tagger = new MaxentTagger(config.tagger);
    List<List<TaggedWord>> tagged = new ArrayList<>();
    for (List<HasWord> sentence : preprocessor) {
      tagged.add(tagger.tagSentence(sentence));
    }

    System.err.printf("Tagging completed in %.2f sec.%n",
        timer.stop() / 1000.0);

    timer.start();

    int numSentences = 0;
    for (List<TaggedWord> taggedSentence : tagged) {
      GrammaticalStructure parse = predict(taggedSentence);

      Collection<TypedDependency> deps = parse.typedDependencies();
      for (TypedDependency dep : deps)
        output.println(dep);
      output.println();

      numSentences++;
    }

    long millis = timer.stop();
    double seconds = millis / 1000.0;
    System.err.printf("Parsed %d sentences in %.2f seconds (%.2f sents/sec).%n",
        numSentences, seconds, numSentences / seconds);
  }

  /**
   * Prepare for parsing after a model has been loaded.
   */
  private void initialize(boolean verbose) {
    if (knownLabels == null)
      throw new IllegalStateException("Model has not been loaded or trained");

    // NOTE: remove -NULL-, and then pass the label set to the ParsingSystem
    List<String> lDict = new ArrayList<>(knownLabels);
    lDict.remove(0);

    system = new ArcStandard(config.tlp, lDict, verbose);

    // Pre-compute matrix multiplications
    if (config.numPreComputed > 0) {
      classifier.preCompute();
    }
  }

  /**
   * Explicitly specifies the number of arguments expected with
   * particular command line options.
   */
  private static final Map<String, Integer> numArgs = new HashMap<>();
  static {
    numArgs.put("textFile", 1);
    numArgs.put("outFile", 1);
  }

  /**
   * A main program for training, testing and using the parser.
   *
   * <p>
   * You can use this program to train new parsers from treebank data,
   * evaluate on test treebank data, or parse raw text input.
   *
   * <p>
   * Sample usages:
   * <ul>
   *   <li>
   *     <strong>Train a parser with CoNLL treebank data:</strong>
   *     <code>java edu.stanford.nlp.parser.nndep.DependencyParser -trainFile trainPath -devFile devPath -embedFile wordEmbeddingFile -embeddingSize wordEmbeddingDimensionality -model modelOutputFile.txt.gz</code>
   *   </li>
   *   <li>
   *     <strong>Parse raw text from a file:</strong>
   *     <code>java edu.stanford.nlp.parser.nndep.DependencyParser -model modelOutputFile.txt.gz -textFile rawTextToParse -outFile dependenciesOutputFile.txt</code>
   *   </li>
   *   <li>
   *     <strong>Parse raw text from standard input, writing to standard output:</strong>
   *     <code>java edu.stanford.nlp.parser.nndep.DependencyParser -model modelOutputFile.txt.gz -textFile - -outFile -</code>
   *   </li>
   * </ul>
   *
   * <p>
   * See below for more information on all of these training / test options and more.
   *
   * <p>
   * Input / output options:
   * <table>
   *   <tr><th>Option</th><th>Required for training</th><th>Required for testing / parsing</th><th>Description</th></tr>
   *   <tr><td><tt>&#8209;devFile</tt></td><td>Optional</td><td>No</td><td>Path to a development-set treebank in <a href="http://ilk.uvt.nl/conll/#dataformat">CoNLL-X format</a>. If provided, the </td></tr>
   *   <tr><td><tt>&#8209;embedFile</tt></td><td>Optional (highly recommended!)</td><td>No</td><td>A word embedding file, containing distributed representations of English words. Each line of the provided file should contain a single word followed by the elements of the corresponding word embedding (space-delimited). It is not absolutely necessary that all words in the treebank be covered by this embedding file, though the parser's performance will generally improve if you are able to provide better embeddings for more words.</td></tr>
   *   <tr><td><tt>&#8209;model</tt></td><td>Yes</td><td>Yes</td><td>Path to a model file. If the path ends in <tt>.gz</tt>, the model will be read as a Gzipped model file. During training, we write to this path; at test time we read a pre-trained model from this path.</td></tr>
   *   <tr><td><tt>&#8209;textFile</tt></td><td>No</td><td>Yes (or <tt>testFile</tt>)</td><td>Path to a plaintext file containing sentences to be parsed.</td></tr>
   *   <tr><td><tt>&#8209;testFile</tt></td><td>No</td><td>Yes (or <tt>textFile</tt>)</td><td>Path to a test-set treebank in <a href="http://ilk.uvt.nl/conll/#dataformat">CoNLL-X format</a> for final evaluation of the parser.</td></tr>
   *   <tr><td><tt>&#8209;trainFile</tt></td><td>Yes</td><td>No</td><td>Path to a training treebank in <a href="http://ilk.uvt.nl/conll/#dataformat">CoNLL-X format</a></td></tr>
   * </table>
   *
   * Training/Parsing options:
   * <table>
   *   <tr><th>Option</th><th>Default</th><th>Description</th></tr>
   *   <tr><td><tt>&#8209;featureMode</tt></td><td>additional</td><td>Determines the mode for features set by <tt>-featureType</tt>. Available modes:<ul><li><i>additional</i>, Adds set features as additional input features.<li><i>replace</i>, Replaces the word input features with the set features.</ul></td></tr>
   *   <tr><td><tt>&#8209;featureType</tt></td><td>none</td><td>Select what additional semantic features to use. Is passed as a comma separated list with one or more feature names. Available features:<ul><li><i>mean</i>, For each word in the input adds a feature consisting of a mean value over the current sentence (excepting the current word).<li><i>pos</i>, For each word in the input adds feature consisting of a weighted mean value over selected POS-types from the current sentence (excepting the current word).</ul></td></tr>
   * </table>
   *
   * Training options:
   * <table>
   *   <tr><th>Option</th><th>Default</th><th>Description</th></tr>
   *   <tr><td><tt>&#8209;adaAlpha</tt></td><td>0.01</td><td>Global learning rate for AdaGrad training</td></tr>
   *   <tr><td><tt>&#8209;adaEps</tt></td><td>1e-6</td><td>Epsilon value added to the denominator of AdaGrad update expression for numerical stability</td></tr>
   *   <tr><td><tt>&#8209;batchSize</tt></td><td>10000</td><td>Size of mini-batch used for training</td></tr>
   *   <tr><td><tt>&#8209;clearGradientsPerIter</tt></td><td>0</td><td>Clear AdaGrad gradient histories every <em>n</em> iterations. If zero, no gradient clearing is performed.</td></tr>
   *   <tr><td><tt>&#8209;dropProb</tt></td><td>0.5</td><td>Dropout probability. For each training example we randomly choose some amount of units to disable in the neural network classifier. This parameter controls the proportion of units "dropped out."</td></tr>
   *   <tr><td><tt>&#8209;embeddingSize</tt></td><td>50</td><td>Dimensionality of word embeddings provided</td></tr>
   *   <tr><td><tt>&#8209;evalPerIter</tt></td><td>100</td><td>Run full UAS (unlabeled attachment score) evaluation every time we finish this number of iterations. (Only valid if a development treebank is provided with <tt>&#8209;devFile</tt>.)</td></tr>
   *   <tr><td><tt>&#8209;hiddenSize</tt></td><td>200</td><td>Dimensionality of hidden layer in neural network classifier</td></tr>
   *   <tr><td><tt>&#8209;initRange</tt></td><td>0.01</td><td>Bounds of range within which weight matrix elements should be initialized. Each element is drawn from a uniform distribution over the range <tt>[-initRange, initRange]</tt>.</td></tr>
   *   <tr><td><tt>&#8209;maxIter</tt></td><td>20000</td><td>Number of training iterations to complete before stopping and saving the final model.</td></tr>
   *   <tr><td><tt>&#8209;wordEmbeddingBackPropagation</tt></td><td>true</td><td>The classifier supports back-propagation on the word embedding. This parameter allows to turn that feature off.</td></tr>
   *   <tr><td><tt>&#8209;numPreComputed</tt></td><td>100000</td><td>The parser pre-computes hidden-layer unit activations for particular inputs words at both training and testing time in order to speed up feedforward computation in the neural network. This parameter determines how many words for which we should compute hidden-layer activations.</td></tr>
   *   <tr><td><tt>&#8209;regParameter</tt></td><td>1e-8</td><td>Regularization parameter for training</td></tr>
   *   <tr><td><tt>&#8209;saveIntermediate</tt></td><td><tt>true</tt></td><td>If <tt>true</tt>, continually save the model version which gets the highest UAS value on the dev set. (Only valid if a development treebank is provided with <tt>&#8209;devFile</tt>.)</td></tr>
   *   <tr><td><tt>&#8209;trainingThreads</tt></td><td>1</td><td>Number of threads to use during training. Note that depending on training batch size, it may be unwise to simply choose the maximum amount of threads for your machine. On our 16-core test machines: a batch size of 10,000 runs fastest with around 6 threads; a batch size of 100,000 runs best with around 10 threads.</td></tr>
   *   <tr><td><tt>&#8209;wordCutOff</tt></td><td>1</td><td>The parser can optionally ignore rare words by simply choosing an arbitrary "unknown" feature representation for words that appear with frequency less than <em>n</em> in the corpus. This <em>n</em> is controlled by the <tt>wordCutOff</tt> parameter.</td></tr>
   * </table>
   *
   * Runtime parsing options:
   * <table>
   *   <tr><th>Option</th><th>Default</th><th>Description</th></tr>
   *   <tr><td><tt>&#8209;escaper</tt></td><td>N/A</td><td>Only applicable for testing with <tt>-textFile</tt>. If provided, use this word-escaper when parsing raw sentences. (Should be a fully-qualified class name like <tt>edu.stanford.nlp.trees.international.arabic.ATBEscaper</tt>.)</td></tr>
   *   <tr><td><tt>&#8209;numPreComputed</tt></td><td>100000</td><td>The parser pre-computes hidden-layer unit activations for particular inputs words at both training and testing time in order to speed up feedforward computation in the neural network. This parameter determines how many words for which we should compute hidden-layer activations.</td></tr>
   *   <tr><td><tt>&#8209;sentenceDelimiter</tt></td><td>N/A</td><td>Only applicable for testing with <tt>-textFile</tt>.  If provided, assume that the given <tt>textFile</tt> has already been sentence-split, and that sentences are separated by this delimiter.</td></tr>
   *   <tr><td><tt>&#8209;tagger.model</tt></td><td>edu/stanford/nlp/models/pos-tagger/english-left3words/english-left3words-distsim.tagger</td><td>Only applicable for testing with <tt>-textFile</tt>. Path to a part-of-speech tagger to use to pre-tag the raw sentences before parsing.</td></tr>
   * </table>
   */
  public static void main(String[] args) {
    Properties props = StringUtils.argsToProperties(args, numArgs);
    DependencyParser parser = new DependencyParser(props);

    if (!parser.config.featureModeReplace && (parser.config.featureMean || parser.config.featurePOS)) {
      Config.numTokens = 66;
    }

    // Train with CoNLL-X data
    if (props.containsKey("trainFile"))
      parser.train(props.getProperty("trainFile"), props.getProperty("devFile"), props.getProperty("model"),
          props.getProperty("embedFile"));

    boolean loaded = false;
    // Test with CoNLL-X data
    if (props.containsKey("testFile")) {
      parser.loadModelFile(props.getProperty("model"));
      loaded = true;
      parser.testCoNLL(props.getProperty("testFile"), props.getProperty("outFile"));
    }

    // Parse raw text data
    if (props.containsKey("textFile")) {
      if (!loaded) {
        parser.loadModelFile(props.getProperty("model"));
        loaded = true;
      }

      String encoding = parser.config.tlp.getEncoding();
      String inputFilename = props.getProperty("textFile");
      BufferedReader input;
      try {
        input = inputFilename.equals("-")
                ? IOUtils.readerFromStdin(encoding)
                : IOUtils.readerFromString(inputFilename, encoding);
      } catch (IOException e) {
        throw new RuntimeIOException("No input file provided (use -textFile)", e);
      }

      String outputFilename = props.getProperty("outFile");
      PrintWriter output;
      try {
        output = outputFilename == null || outputFilename.equals("-")
            ? IOUtils.encodedOutputStreamPrintWriter(System.out, encoding, true)
            : IOUtils.getPrintWriter(outputFilename, encoding);
      } catch (IOException e) {
        throw new RuntimeIOException("Error opening output file", e);
      }

      parser.parseTextFile(input, output);
    }
  }
}