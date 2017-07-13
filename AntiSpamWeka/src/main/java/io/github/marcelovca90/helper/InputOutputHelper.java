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
package io.github.marcelovca90.helper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.pmw.tinylog.Logger;

import com.arturmkrtchyan.sizeof4j.SizeOf;

import io.github.marcelovca90.common.Constants.MessageType;
import io.github.marcelovca90.common.DataSetMetadata;
import io.github.marcelovca90.common.MethodConfiguration;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

public class InputOutputHelper
{
    private static final int SIZE_INT = SizeOf.intSize();
    private static final int SIZE_DOUBLE = SizeOf.doubleSize();

    public String buildClassifierFilename(String folder, MethodConfiguration method, double splitPercent, int seed)
    {
        StringBuilder sb = new StringBuilder();

        sb.append(folder + File.separator);
        sb.append(method.getClazz().getSimpleName());
        sb.append("_TRAIN=" + (int) (100 * splitPercent));
        sb.append("_TEST=" + (int) (100 * (1.0 - splitPercent)));
        sb.append("_SEED=" + seed);
        sb.append(".model");

        return sb.toString();
    }

    public Instances createEmptyInstances(int featureAmount, int emptyHamCount, int emptySpamCount)
    {
        Logger.debug("Creating empty [{}] data set with [{}] features and [{}] instances.", "ham", featureAmount, emptyHamCount);

        // declare ham instance to be reused
        Instance hamInstance;

        // create ham attributes
        ArrayList<Attribute> hamAttributes = createAttributes(featureAmount);

        // create ham data set
        Instances hamDataSet = new Instances(MessageType.HAM.name(), hamAttributes, emptyHamCount);
        hamDataSet.setClassIndex(hamAttributes.size() - 1);

        // read ham data and insert in data set
        for (int i = 0; i < emptyHamCount; i++)
        {
            hamInstance = new DenseInstance(featureAmount + 1);
            hamInstance.setDataset(hamDataSet);
            for (int j = 0; j < featureAmount; j++)
                hamInstance.setValue(j, 0.0);
            hamInstance.setClassValue(MessageType.HAM.name());
            hamDataSet.add(hamInstance);
        }

        Logger.debug("Creating empty [{}] data set with [{}] features and [{}] instances.", "spam", featureAmount, emptySpamCount);

        // declare spam instance to be reused
        Instance spamInstance;

        // create spam attributes
        ArrayList<Attribute> spamAttributes = createAttributes(featureAmount);

        // create spam data set
        Instances spamDataSet = new Instances(MessageType.SPAM.name(), spamAttributes, emptySpamCount);
        spamDataSet.setClassIndex(spamAttributes.size() - 1);

        // read spam data and insert in data set
        for (int i = 0; i < emptySpamCount; i++)
        {
            spamInstance = new DenseInstance(featureAmount + 1);
            spamInstance.setDataset(spamDataSet);
            for (int j = 0; j < featureAmount; j++)
                spamInstance.setValue(j, 0.0);
            spamInstance.setClassValue(MessageType.SPAM.name());
            spamDataSet.add(spamInstance);
        }

        // create merged data set attributes
        ArrayList<Attribute> dataSetAttributes = createAttributes(featureAmount);

        // create and fill merged data set
        Instances dataSet = new Instances(UUID.randomUUID().toString(), dataSetAttributes, hamDataSet.size() + spamDataSet.size());
        dataSet.addAll(hamDataSet);
        dataSet.addAll(spamDataSet);
        dataSet.setClassIndex(dataSet.numAttributes() - 1);

        return dataSet;
    }

    public Set<DataSetMetadata> loadDataSetsMetadataFromFile(String filename) throws IOException
    {
        Set<DataSetMetadata> metadata = new LinkedHashSet<>();

        BufferedReader reader = new BufferedReader(new FileReader(new File(filename)));

        String line = null;

        while ((line = reader.readLine()) != null)
        {
            // only process the line if it is not empty and does not start with a comment mark (#)
            if (!StringUtils.isEmpty(line) && !line.startsWith("#"))
            {
                // replaces the user home symbol (~) with the actual folder path
                if (line.startsWith("~"))
                    line = line.replaceAll("~", System.getProperty("user.home"));
                String[] parts = line.split(",");
                String folder = parts[0];
                Integer emptyHamAmount = Integer.parseInt(parts[1]);
                Integer emptySpamAmount = Integer.parseInt(parts[2]);

                metadata.add(new DataSetMetadata(folder, emptyHamAmount, emptySpamAmount));
            }
        }

        reader.close();

        return metadata;
    }

    public Instances loadInstancesFromFile(String hamDataFilename, String spamDataFilename) throws IOException
    {
        Logger.debug("Reading [{}] data from file [{}]. This may take a while.", "ham", hamDataFilename);
        Instances hamDataSet = loadInstancesFromFile(hamDataFilename, MessageType.HAM);

        Logger.debug("Reading [{}] data from file [{}]. This may take a while.", "spam", spamDataFilename);
        Instances spamDataSet = loadInstancesFromFile(spamDataFilename, MessageType.SPAM);

        // create merged data set attributes
        ArrayList<Attribute> dataSetAttributes = createAttributes(hamDataSet.numAttributes() - 1);

        // create and fill merged data set
        Instances dataSet = new Instances(UUID.randomUUID().toString(), dataSetAttributes, hamDataSet.size() + spamDataSet.size());
        dataSet.addAll(hamDataSet);
        dataSet.addAll(spamDataSet);
        dataSet.setClassIndex(dataSet.numAttributes() - 1);

        return dataSet;
    }

    public Classifier loadModelFromFile(String filename) throws Exception
    {
        return (Classifier) weka.core.SerializationHelper.read(filename);
    }

    public File saveInstancesToFile(Instances instances, String filename) throws IOException
    {
        Logger.debug("Saving data set to file [{}].", filename);

        // create output file's buffered writer
        File file = new File(filename);

        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(file));

        // write attribute names
        for (int attrIndex = 0; attrIndex < instances.numAttributes(); attrIndex++)
        {
            if (attrIndex > 0)
                bufferedWriter.write(",");
            bufferedWriter.write(attrIndex != instances.classIndex() ? instances.attribute(attrIndex).name() : "class");
        }
        bufferedWriter.write(System.lineSeparator());

        // write instances
        for (int i = 0; i < instances.size(); i++)
        {
            bufferedWriter.write(instances.get(i).toStringNoWeight());
            bufferedWriter.write(System.lineSeparator());
        }

        // flush and close the buffered writer
        bufferedWriter.flush();
        bufferedWriter.close();

        return file;
    }

    public File saveModelToFile(String filename, Classifier classifier) throws Exception
    {
        Logger.debug("Saving model to file [{}].", filename);

        weka.core.SerializationHelper.write(filename, classifier);

        return new File(filename);
    }

    private ArrayList<Attribute> createAttributes(long featureAmount)
    {
        ArrayList<Attribute> attributes = new ArrayList<>();
        for (long i = 0; i < featureAmount; i++)
            attributes.add(new Attribute("x" + i));
        attributes.add(new Attribute("class", Arrays.asList(MessageType.HAM.name(), MessageType.SPAM.name())));
        return attributes;
    }

    private Instances loadInstancesFromFile(String filename, MessageType messageType) throws IOException
    {
        InputStream inputStream = new FileInputStream(filename);

        byte[] byteBufferA = new byte[SIZE_INT];
        inputStream.read(byteBufferA);
        int numberOfInstances = ByteBuffer.wrap(byteBufferA).getInt();

        byte[] byteBufferB = new byte[SIZE_INT];
        inputStream.read(byteBufferB);
        int numberOfAttributes = ByteBuffer.wrap(byteBufferB).getInt();

        // create attributes
        ArrayList<Attribute> attributes = createAttributes(numberOfAttributes);

        // create data set
        Instances dataSet = new Instances(UUID.randomUUID().toString(), attributes, numberOfInstances);
        dataSet.setClassIndex(attributes.size() - 1);

        // create instance placeholder
        Instance instance = new DenseInstance(numberOfAttributes + 1);
        instance.setDataset(dataSet);
        instance.setClassValue(messageType.name());

        byte[] byteBufferC = new byte[SIZE_DOUBLE];
        DoubleBuffer doubleBuffer = DoubleBuffer.allocate(numberOfAttributes);

        while (inputStream.read(byteBufferC) != -1)
        {
            doubleBuffer.put(ByteBuffer.wrap(byteBufferC).getDouble());

            if (!doubleBuffer.hasRemaining())
            {
                double[] values = doubleBuffer.array();
                for (int j = 0; j < numberOfAttributes; j++)
                    instance.setValue(j, values[j]);
                dataSet.add(instance);

                doubleBuffer.clear();
            }
        }

        inputStream.close();

        return dataSet;
    }
}
