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
package io.github.marcelovca90.main;

import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.LogManager;

import org.apache.commons.math3.primes.Primes;

import io.github.marcelovca90.common.DataSetMetadata;
import io.github.marcelovca90.common.FilterConfiguration;
import io.github.marcelovca90.common.MethodConfiguration;
import io.github.marcelovca90.common.MethodEvaluation;
import io.github.marcelovca90.helper.IOHelper;
import io.github.marcelovca90.helper.MetaHelper;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.core.Instances;

public class Runner
{
    private void run(String[] args) throws Exception
    {
        // change global setting for Logger instances to WARNING level
        Arrays
            .stream(LogManager.getLogManager().getLogger("").getHandlers())
            .forEach(h -> h.setLevel(Level.WARNING));

        // initialize the CLI helper with the provided arguments
        MetaHelper.getCliHelper().initialize(args);

        // print the parsed, args-provided parameters
        MetaHelper.getCliHelper().printConfiguration();

        // objects that will hold all kinds of data sets
        Instances dataSet = null;
        Instances trainingSet = null;
        Instances testingSet = null;
        Instances emptySet = null;

        for (MethodConfiguration method : MetaHelper.getCliHelper().getMethods())
        {
            MetaHelper.getFormatHelper().printHeader();

            for (DataSetMetadata metadata : MetaHelper.getCliHelper().getDataSetsMetadata())
            {
                // import data set
                String hamFilePath = metadata.getFolder() + File.separator + IOHelper.TAG_HAM;
                String spamFilePath = metadata.getFolder() + File.separator + IOHelper.TAG_SPAM;
                dataSet = MetaHelper.getIoHelper().loadInstancesFromFile(hamFilePath, spamFilePath);

                // apply attribute and instance filters to the data set, if specified
                int numberOfTotalFeatures = dataSet.numAttributes() - 1;
                if (MetaHelper.getCliHelper().shrinkFeatures())
                    dataSet = FilterConfiguration.buildAndApply(dataSet, FilterConfiguration.AttributeFilter.CfsSubsetEval_GreedyStepwise);
                if (MetaHelper.getCliHelper().balanceClasses())
                    dataSet = FilterConfiguration.buildAndApply(dataSet, FilterConfiguration.InstanceFilter.ClassBalancer);
                int numberOfActualFeatures = dataSet.numAttributes() - 1;

                // build empty patterns set, if specified
                if (MetaHelper.getCliHelper().includeEmptyInstances())
                    emptySet = MetaHelper.getIoHelper().createEmptyInstances(dataSet.numAttributes() - 1, metadata.getEmptyHamCount(), metadata.getEmptySpamCount());

                // initialize random number generator
                Random random = new Random();

                // build the classifier for the given configuration
                Classifier baseClassifier = MethodConfiguration.buildClassifierFor(method);

                // create the object that will hold the overall evaluations result
                MethodEvaluation baseEvaluation = new MethodEvaluation(metadata.getFolder(), method);

                // reset random number generator seed
                Integer randomSeed = 1;

                // reset run results keeper
                MetaHelper.getResultHelper().reset();

                for (int run = 0; run < MetaHelper.getCliHelper().getNumberOfRuns(); run++)
                {
                    // set random number generator's seed
                    random.setSeed(randomSeed = Primes.nextPrime(++randomSeed));

                    // randomize the data set to assure balance and avoid biasing
                    dataSet.randomize(random);

                    // build train and test sets
                    double splitPercent = method.getSplitPercent();
                    int trainingSetSize = (int) Math.round(dataSet.numInstances() * splitPercent);
                    int testingSetSize = dataSet.numInstances() - trainingSetSize;
                    trainingSet = new Instances(dataSet, 0, trainingSetSize);
                    testingSet = new Instances(dataSet, trainingSetSize, testingSetSize);

                    // add empty patterns to test set
                    if (MetaHelper.getCliHelper().includeEmptyInstances())
                        testingSet.addAll(emptySet);

                    // save the data sets to csv files, if specified
                    if (MetaHelper.getCliHelper().saveSets())
                    {
                        MetaHelper.getIoHelper().saveInstancesToFile(trainingSet, metadata.getFolder() + File.separator + "training.csv");
                        MetaHelper.getIoHelper().saveInstancesToFile(testingSet, metadata.getFolder() + File.separator + "testing.csv");
                    }

                    // if the training should be skipped, then read the classifier from the filesystem; else, clone and train the base classifier
                    String classifierFilename = MetaHelper.getIoHelper().buildClassifierFilename(metadata.getFolder(), method, splitPercent, randomSeed);
                    Classifier classifier = MetaHelper.getCliHelper().skipTrain() ? MetaHelper.getIoHelper().loadModelFromFile(classifierFilename) : AbstractClassifier.makeCopy(baseClassifier);

                    // create the object that will hold the single evaluation result
                    Evaluation evaluation = new Evaluation(testingSet);

                    // setup the classifier evaluation
                    baseEvaluation.setClassifier(classifier);
                    baseEvaluation.setEvaluation(evaluation);
                    baseEvaluation.setNumberOfTotalFeatures(numberOfTotalFeatures);
                    baseEvaluation.setNumberOfActualFeatures(numberOfActualFeatures);

                    // if the classifier could not be loaded from the filesystem, then train it
                    if (!MetaHelper.getCliHelper().skipTrain())
                        baseEvaluation.train(trainingSet);

                    // if the testing should not be skipped
                    if (!MetaHelper.getCliHelper().skipTest())
                    {
                        // evaluate the classifier
                        baseEvaluation.test(testingSet);

                        // compute and log the partial results for this configuration
                        MetaHelper.getResultHelper().computeSingleRunResults(baseEvaluation);
                        MetaHelper.getFormatHelper().summarizeResults(baseEvaluation, false, true);

                        // if at the end of last run, detect and remove outliers; this may lead to additional runs
                        if (run == (MetaHelper.getCliHelper().getNumberOfRuns() - 1))
                            run -= MetaHelper.getResultHelper().detectAndRemoveOutliers();
                    }

                    // persist the classifier, if specified in args
                    if (MetaHelper.getCliHelper().saveModel())
                        MetaHelper.getIoHelper().saveModelToFile(classifierFilename, classifier);
                }

                // log the final results for this configuration
                if (!MetaHelper.getCliHelper().skipTest())
                    MetaHelper.getFormatHelper().summarizeResults(baseEvaluation, true, true);
            }
        }
    }

    public static void main(String[] args) throws Exception
    {
        new Runner().run(args);
    }
}
