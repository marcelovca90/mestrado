/*******************************************************************************
 * Copyright (C) 2017 Marcelo Vinícius Cysneiros Aragão
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
package xyz.marcelo.main;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.LogManager;

import org.apache.commons.math3.primes.Primes;

import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.core.Instances;
import xyz.marcelo.common.DataSetMetadata;
import xyz.marcelo.common.MethodConfiguration;
import xyz.marcelo.common.MethodEvaluation;
import xyz.marcelo.helper.CLIHelper;
import xyz.marcelo.helper.FilterHelper;
import xyz.marcelo.helper.FormatHelper;
import xyz.marcelo.helper.IOHelper;
import xyz.marcelo.helper.ResultHelper;

public final class Main
{
    public static void main(String[] args) throws Exception
    {
        // change global setting for Logger instances to WARNING level
        Arrays.stream(LogManager.getLogManager().getLogger("").getHandlers()).forEach(h -> h.setLevel(Level.WARNING));

        // initialize the CLI helper with the provided arguments
        CLIHelper.initialize(args);

        // print the parsed, args-provided parameters
        CLIHelper.printConfiguration();

        // objects that will hold all kinds of data sets
        Instances dataSet = null, trainingSet = null, testingSet = null, emptySet = null;

        for (MethodConfiguration method : CLIHelper.getMethods())
        {
            FormatHelper.printHeader();

            for (DataSetMetadata metadata : CLIHelper.getDataSetsMetadata())
            {
                // import data set
                String hamFilePath = metadata.getFolder() + File.separator + IOHelper.TAG_HAM;
                String spamFilePath = metadata.getFolder() + File.separator + IOHelper.TAG_SPAM;
                dataSet = IOHelper.loadInstancesFromFile(hamFilePath, spamFilePath);

                // apply attribute and instance filters to the data set, if specified
                if (CLIHelper.shrinkFeatures()) dataSet = FilterHelper.applyAttributeFilter(dataSet);
                if (CLIHelper.balanceClasses()) dataSet = FilterHelper.applyInstanceFilter(dataSet);

                // build empty patterns set, if specified
                if (CLIHelper.includeEmptyInstances())
                {
                    emptySet = IOHelper.createEmptyInstances(dataSet.numAttributes() - 1, metadata.getEmptyHamCount(), metadata.getEmptySpamCount());
                }

                // initialize random number generator
                Random random = new Random();

                // build the classifier for the given configuration
                Classifier baseClassifier = MethodConfiguration.buildClassifierFor(method);

                // create the object that will hold the overall evaluations result
                MethodEvaluation baseEvaluation = new MethodEvaluation(metadata.getFolder(), method);

                // reset random number generator seed
                Integer randomSeed = 1;

                // reset run results keeper
                ResultHelper.reset();

                for (int run = 0; run < CLIHelper.getNumberOfRuns(); run++)
                {
                    // set random number generator's seed
                    random.setSeed(randomSeed = Primes.nextPrime(++randomSeed));

                    // randomize the data set to assure balance and avoid biasing
                    dataSet.randomize(random);

                    // build train and test sets
                    double splitPercent = 0.5;
                    int trainingSetSize = (int) Math.round(dataSet.numInstances() * splitPercent);
                    int testingSetSize = dataSet.numInstances() - trainingSetSize;
                    trainingSet = new Instances(dataSet, 0, trainingSetSize);
                    testingSet = new Instances(dataSet, trainingSetSize, testingSetSize);

                    // add empty patterns to test set
                    if (CLIHelper.includeEmptyInstances()) testingSet.addAll(emptySet);

                    // save the data sets to csv files, if specified
                    if (CLIHelper.saveSets())
                    {
                        IOHelper.saveInstancesToFile(trainingSet, metadata.getFolder() + File.separator + "training.csv");
                        IOHelper.saveInstancesToFile(testingSet, metadata.getFolder() + File.separator + "testing.csv");
                    }

                    // if the training should be skipped, then read the classifier from the filesystem; else, clone and train the base classifier
                    String classifierFilename = IOHelper.buildClassifierFilename(metadata.getFolder(), method, splitPercent, randomSeed);
                    Classifier classifier = CLIHelper.skipTrain() ? IOHelper.loadModelFromFile(classifierFilename) : AbstractClassifier.makeCopy(baseClassifier);

                    // create the object that will hold the single evaluation result
                    Evaluation evaluation = new Evaluation(testingSet);

                    // setup the classifier evaluation
                    baseEvaluation.setClassifier(classifier);
                    baseEvaluation.setEvaluation(evaluation);
                    baseEvaluation.setNumberOfActualFeatures(dataSet.numAttributes() - 1);

                    // if the classifier could not be loaded from the filesystem, then train it
                    if (!CLIHelper.skipTrain()) baseEvaluation.train(trainingSet);

                    // if the testing should not be skipped
                    if (!CLIHelper.skipTest())
                    {
                        // evaluate the classifier
                        baseEvaluation.test(testingSet);

                        // compute and log the partial results for this configuration
                        ResultHelper.computeSingleRunResults(baseEvaluation);
                        FormatHelper.summarizeResults(baseEvaluation, false, true);

                        // if at the end of last run, detect and remove outliers; this may lead to additional runs
                        if (run == (CLIHelper.getNumberOfRuns() - 1))
                        {
                            run -= ResultHelper.detectAndRemoveOutliers();
                        }
                    }

                    // persist the classifier, if specified in args
                    if (CLIHelper.saveModel()) IOHelper.saveModelToFile(classifierFilename, classifier);
                }

                // log the final results for this configuration
                if (!CLIHelper.skipTest())
                {
                    FormatHelper.summarizeResults(baseEvaluation, true, true);
                }
            }
        }
    }
}
