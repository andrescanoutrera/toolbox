/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package eu.amidst.core.learning.parametric.bayesian;

import eu.amidst.core.datastream.Attribute;
import eu.amidst.core.datastream.DataInstance;
import eu.amidst.core.datastream.DataOnMemory;
import eu.amidst.core.datastream.DataStream;
import eu.amidst.core.distribution.UnivariateDistribution;
import eu.amidst.core.exponentialfamily.*;
import eu.amidst.core.models.BayesianNetwork;
import eu.amidst.core.models.DAG;
import eu.amidst.core.utils.ArrayVector;
import eu.amidst.core.utils.CompoundVector;
import eu.amidst.core.utils.Vector;
import eu.amidst.core.variables.Assignment;
import eu.amidst.core.variables.HashMapAssignment;
import eu.amidst.core.variables.Variable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// TODO By iterating several times over the data we can get better approximations.
// TODO Trick. Initialize the Q's of the parameters variables with the final posteriors in the previous iterations.

/**
 * This class implements the {@link BayesianParameterLearningAlgorithm} interface.
 * It defines the Streaming Variational Bayes (SVB) algorithm.
 *
 * <p> For an example of use follow this link </p>
 * <p> <a href="http://amidst.github.io/toolbox/CodeExamples.html#svbexample"> http://amidst.github.io/toolbox/CodeExamples.html#svbexample </a>  </p>
 *
 */
public class SVB implements BayesianParameterLearningAlgorithm, Serializable {

    /** Represents the serial version ID for serializing the object. */
    private static final long serialVersionUID = 4107783324901370839L;

    /** Represents the transition method {@link TransitionMethod}. */
    TransitionMethod transitionMethod = null;

    /** Represents a {@link EF_LearningBayesianNetwork} object. */
    EF_LearningBayesianNetwork ef_extendedBN;

    /** Represents the plateu structure {@link PlateuStructure}*/
    PlateuStructure plateuStructure = new PlateuIIDReplication();

    /** Represents a directed acyclic graph {@link DAG}. */
    DAG dag;

    /** Represents the data stream to be used for parameter learning. */
    transient DataStream<DataInstance> dataStream;

    /** Represents the Evidence Lower BOund (elbo). */
    double elbo;

    /** Indicates if the model is non sequential, initialized to {@code false}. */
    boolean nonSequentialModel=false;

    /** Indicates if this SVB can random restarted, initialized to {@code false}. */
    boolean randomRestart=false;

    /** Represents the window size, initialized to 100. */
    int windowsSize=100;

    /** Represents the seed, initialized to 0. */
    int seed = 0;

    /** Represents the total number of batches. */
    int nBatches = 0;

    /** Represents the total number of iterations, initialized to 0. */
    int nIterTotal = 0;

    /** Represents the natural vector prior. */
    CompoundVector naturalVectorPrior = null;

    /** Represents the natural vector posterior. */
    BatchOutput naturalVectorPosterior = null;

    /**
     * Returns the window size.
     * @return the window size.
     */
    public int getWindowsSize() {
        return windowsSize;
    }

    /**
     * Sets a random restart for this SVB.
     * @param randomRestart {@code true} if a random restart is to be set, {@code false} otherwise.
     */
    public void setRandomRestart(boolean randomRestart) {
        this.randomRestart = randomRestart;
    }

    /**
     * Returns the plateu structure of this SVB.
     * @return a {@link PlateuStructure} object.
     */
    public PlateuStructure getPlateuStructure() {
        return plateuStructure;
    }

    /**
     * Sets the plateu structure of this SVB.
     * @param plateuStructure a valid {@link PlateuStructure} object.
     */
    public void setPlateuStructure(PlateuStructure plateuStructure) {
        this.plateuStructure = plateuStructure;
    }

    /**
     * Returns the seed.
     * @return the seed.
     */
    public int getSeed() {
        return seed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSeed(int seed) {
        this.seed = seed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double getLogMarginalProbability() {
        return elbo;
    }

    /**
     * Sets the window size.
     * @param windowsSize the window size.
     */
    public void setWindowsSize(int windowsSize) {
        this.windowsSize = windowsSize;
    }

    /**
     * Sets the transition method of this SVB.
     * @param transitionMethod a valid {@link TransitionMethod} object.
     */
    public void setTransitionMethod(TransitionMethod transitionMethod) {
        this.transitionMethod = transitionMethod;
    }

    /**
     * Returns the transition method of this SVB.
     * @param <E> the type of elements.
     * @return a valid {@link TransitionMethod} object.
     */
    public <E extends TransitionMethod> E getTransitionMethod() {
        return (E)this.transitionMethod;
    }

    /**
     * Returns the natural parameter priors.
     * @return a {@link CompoundVector} including the natural parameter priors.
     */
    public CompoundVector getNaturalParameterPrior(){
        if (naturalVectorPrior==null){
            naturalVectorPrior = this.computeNaturalParameterVectorPrior();
        }
        return naturalVectorPrior;
    }

    /**
     * Returns the natural parameter posterior.
     * @return a {@link BatchOutput} object including the natural parameter posterior.
     */
    protected BatchOutput getNaturalParameterPosterior() {
        if (this.naturalVectorPosterior==null){
            naturalVectorPosterior = new BatchOutput(this.computeNaturalParameterVectorPrior(), 0);
        }
        return naturalVectorPosterior;
    }

    /**
     * Computes the natural parameter priors.
     * @return a {@link CompoundVector} including the natural parameter priors.
     */
    protected CompoundVector computeNaturalParameterVectorPrior(){
        List<Vector> naturalParametersPriors =  this.ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                .filter( var -> this.getPlateuStructure().getNodeOfVar(var,0).isActive())
                .map(var -> {
                    NaturalParameters parameter =this.ef_extendedBN.getDistribution(var).getNaturalParameters();
                    NaturalParameters copy = new ArrayVector(parameter.size());
                    copy.copy(parameter);
                    return copy;
                }).collect(Collectors.toList());

        CompoundVector compoundVectorPrior = new CompoundVector(naturalParametersPriors);

        return compoundVectorPrior;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runLearning() {
        this.initLearning();
        if (!nonSequentialModel) {
            this.elbo = this.dataStream.streamOfBatches(this.windowsSize).mapToDouble(this::updateModel).sum();
        }else {
            this.elbo = this.dataStream.streamOfBatches(this.windowsSize).mapToDouble(this::updateModelParallel).sum();
       }
    }

    /**
     * Sets the model as a non sequential.
     * @param nonSequentialModel_ {@code true} if the model is to be set as a non sequential, {@code false} otherwise.
     */
    public void setNonSequentialModel(boolean nonSequentialModel_) {
        this.nonSequentialModel = nonSequentialModel_;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setOutput(boolean activateOutput) {
        this.getPlateuStructure().getVMP().setOutput(activateOutput);
        this.getPlateuStructure().getVMP().setTestELBO(activateOutput);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double updateModel(DataOnMemory<DataInstance> batch) {
        double elboBatch = 0;
        if (!nonSequentialModel){
            if (this.randomRestart) this.getPlateuStructure().resetQs();
            elboBatch =  this.updateModelSequential(batch);
        }else{
            if (this.randomRestart) this.getPlateuStructure().resetQs();
            elboBatch =  this.updateModelParallel(batch);
        }

        this.applyTransition();

        return elboBatch;
    }

    /**
     * Apply the transition method defined by the method setTransitionMethod.
     */
    public void applyTransition(){
        if (transitionMethod!=null)
            this.ef_extendedBN=this.transitionMethod.transitionModel(this.ef_extendedBN, this.plateuStructure);

    }

    /**
     * Updates the model sequentially using a given {@link DataOnMemory} object.
     * @param batch a {@link DataOnMemory} object.
     * @return a double value representing the log probability of an evidence.
     */
    private double updateModelSequential(DataOnMemory<DataInstance> batch) {
        nBatches++;
        //System.out.println("\n Batch:");
        this.plateuStructure.setEvidence(batch.getList());
        this.plateuStructure.runInference();
        nIterTotal+=this.plateuStructure.getVMP().getNumberOfIterations();

        ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream().forEach(var -> {
            EF_UnivariateDistribution uni = plateuStructure.getEFParameterPosterior(var).deepCopy();
            ef_extendedBN.setDistribution(var, uni);
            this.plateuStructure.getNodeOfVar(var,0).setPDist(uni);
        });

        //this.plateuVMP.resetQs();
        return this.plateuStructure.getLogProbabilityOfEvidence();
    }

    /**
     * Updates the model on batch in parallel using a given {@link DataOnMemory} object.
     * @param batch a {@link DataOnMemory} object.
     * @return a {@link BatchOutput} object.
     */
    public BatchOutput updateModelOnBatchParallel(DataOnMemory<DataInstance> batch) {

        nBatches++;
        this.plateuStructure.setEvidence(batch.getList());
        this.plateuStructure.runInference();
        nIterTotal+=this.plateuStructure.getVMP().getNumberOfIterations();

        List<Vector> naturalParametersPosterior =  this.ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                .filter(var -> this.getPlateuStructure().getNodeOfVar(var, 0).isActive())
                .map(var -> plateuStructure.getEFParameterPosterior(var).deepCopy().getNaturalParameters()).collect(Collectors.toList());


        CompoundVector compoundVectorEnd = new CompoundVector(naturalParametersPosterior);

        compoundVectorEnd.substract(this.getNaturalParameterPrior());

        return new BatchOutput(compoundVectorEnd, this.plateuStructure.getLogProbabilityOfEvidence());

    }


    /**
     * {@inheritDoc}
     */
    @Override
    public List<DataPosterior> computePosterior(DataOnMemory<DataInstance> batch) {

        List<Variable> latentVariables = this.dag.getVariables().getListOfVariables().stream().filter(var -> var.getAttribute()!=null).collect(Collectors.toList());

        return this.computePosterior(batch, latentVariables);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<DataPosterior> computePosterior(DataOnMemory<DataInstance> batch, List<Variable> latentVariables) {
        Attribute seq_id = batch.getAttributes().getSeq_id();
        if (seq_id==null)
            throw new IllegalArgumentException("Functionality only available for data sets with a seq_id attribute");

        this.ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                .forEach(var -> this.getPlateuStructure().getNodeOfVar(var, 0).setActive(false));


        this.plateuStructure.setEvidence(batch.getList());
        this.plateuStructure.runInference();

        this.ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                .forEach(var -> this.getPlateuStructure().getNodeOfVar(var, 0).setActive(true));

        List<DataPosterior> posteriors = new ArrayList<>();
        for (int i = 0; i < batch.getNumberOfDataInstances(); i++) {
            List<UnivariateDistribution> posteriorsQ = new ArrayList<>();
            for (Variable latentVariable : latentVariables) {
                posteriorsQ.add(plateuStructure.getEFVariablePosterior(latentVariable, i).deepCopy().toUnivariateDistribution());
            }
            posteriors.add(new DataPosterior((int) batch.getDataInstance(i).getValue(seq_id), posteriorsQ));
        }

        return posteriors;
    }


    public List<DataPosteriorAssignment> computePosteriorAssignment(DataOnMemory<DataInstance> batch, List<Variable> variables) {
        Attribute seq_id = batch.getAttributes().getSeq_id();
        if (seq_id==null)
            throw new IllegalArgumentException("Functionality only available for data sets with a seq_id attribute");

        this.ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                .forEach(var -> this.getPlateuStructure().getNodeOfVar(var, 0).setActive(false));


        this.plateuStructure.setEvidence(batch.getList());
        this.plateuStructure.runInference();

        this.ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                .forEach(var -> this.getPlateuStructure().getNodeOfVar(var, 0).setActive(true));

        List<DataPosteriorAssignment> posteriors = new ArrayList<>();
        for (int i = 0; i < batch.getNumberOfDataInstances(); i++) {
            List<UnivariateDistribution> posteriorsQ = new ArrayList<>();
            Assignment assignment = new HashMapAssignment();
            for (Variable variable : variables) {
                EF_UnivariateDistribution dist = plateuStructure.getEFVariablePosterior(variable, i);
                if (dist!=null)
                    posteriorsQ.add(dist.deepCopy().toUnivariateDistribution());
                else
                    assignment.setValue(variable,batch.getDataInstance(i).getValue(variable));

            }
            DataPosterior dataPosterior = new DataPosterior((int) batch.getDataInstance(i).getValue(seq_id), posteriorsQ);
            posteriors.add(new DataPosteriorAssignment(dataPosterior,assignment));
        }

        return posteriors;
    }

    /**
     * Updates the model in parallel using a given {@link DataOnMemory} object.
     * @param batch a {@link DataOnMemory} object.
     * @return a double value.
     */
    private double updateModelParallel(DataOnMemory<DataInstance> batch) {

        nBatches++;
        this.plateuStructure.setEvidence(batch.getList());
        this.plateuStructure.runInference();
        nIterTotal+=this.plateuStructure.getVMP().getNumberOfIterations();

        List<Vector> naturalParametersPosterior =  this.ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                .filter( var -> this.getPlateuStructure().getNodeOfVar(var,0).isActive())
                .map(var -> plateuStructure.getEFParameterPosterior(var).deepCopy().getNaturalParameters()).collect(Collectors.toList());

        CompoundVector compoundVectorEnd = new CompoundVector(naturalParametersPosterior);

        compoundVectorEnd.substract(this.getNaturalParameterPrior());

        BatchOutput out = new BatchOutput(compoundVectorEnd, this.plateuStructure.getLogProbabilityOfEvidence());

        this.naturalVectorPosterior = BatchOutput.sum(out, this.getNaturalParameterPosterior());

        return out.getElbo();
    }

    /**
     * Returns the number of batches.
     * @return the number of batches.
     */
    public int getNumberOfBatches() {
        return nBatches;
    }

    /**
     * Returns the average number of iterations.
     * @return the average number of iterations.
     */
    public double getAverageNumOfIterations(){
        return ((double)nIterTotal)/nBatches;
    }


    /**
     * Returns the associated DAG defining the PGM structure
     * @return A {@link DAG} object.
     */
    public DAG getDAG() {
        return dag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDAG(DAG dag) {
        this.dag = dag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initLearning(){

        plateuStructure.setNRepetitions(windowsSize);

        this.nBatches = 0;
        this.nIterTotal = 0;
        List<EF_ConditionalDistribution> dists = this.dag.getParentSets().stream()
                .map(pSet -> pSet.getMainVar().getDistributionType().<EF_ConditionalDistribution>newEFConditionalDistribution(pSet.getParents()))
                .collect(Collectors.toList());

        this.ef_extendedBN = new EF_LearningBayesianNetwork(dists);
        this.plateuStructure.setSeed(seed);
        plateuStructure.setEFBayesianNetwork(ef_extendedBN);
        plateuStructure.replicateModel();
        this.plateuStructure.resetQs();
        if (transitionMethod!=null)
           this.ef_extendedBN = this.transitionMethod.initModel(this.ef_extendedBN, plateuStructure);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setDataStream(DataStream<DataInstance> data) {
        this.dataStream=data;
    }

    /**
     * Converts a {@link DAG} to an extended Exponential Family (EF) Bayesian network (BN).
     * @param dag a directed acyclic graph {@link DAG}.
     * @return an {@link EF_BayesianNetwork} object.
     */
    private static EF_BayesianNetwork convertDAGToExtendedEFBN(DAG dag){
        return null;
    }

    /**
     * Updates the Natural Parameter Prior from a given parameter vector.
     * @param parameterVector a {@link CompoundVector} object.
     */
    public void updateNaturalParameterPrior(CompoundVector parameterVector){

        List<Variable> params  = ef_extendedBN.getParametersVariables().getListOfParamaterVariables();

        int count=0;
        for (Variable var : params) {
            if (!this.getPlateuStructure().getNodeOfVar(var, 0).isActive())
                continue;
            EF_UnivariateDistribution uni = plateuStructure.getEFParameterPosterior(var).deepCopy();
            uni.getNaturalParameters().copy(parameterVector.getVectorByPosition(count));
            uni.fixNumericalInstability();
            uni.updateMomentFromNaturalParameters();
            ef_extendedBN.setDistribution(var, uni);
            this.plateuStructure.getNodeOfVar(var,0).setPDist(uni);
            count++;
        }
        this.naturalVectorPrior=this.computeNaturalParameterVectorPrior();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BayesianNetwork getLearntBayesianNetwork() {
        if(!nonSequentialModel)
            return new BayesianNetwork(this.dag, ef_extendedBN.toConditionalDistribution());
        else{
            CompoundVector posterior = (CompoundVector)this.getNaturalParameterPosterior().getVector();

            final int[] count = new int[1];
            ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                    .filter( var -> this.getPlateuStructure().getNodeOfVar(var,0).isActive())
                    .forEach( var -> {
                        EF_UnivariateDistribution uni = plateuStructure.getEFParameterPosterior(var).deepCopy();
                        uni.getNaturalParameters().copy(posterior.getVectorByPosition(count[0]));
                        uni.fixNumericalInstability();
                        uni.updateMomentFromNaturalParameters();
                        ef_extendedBN.setDistribution(var, uni);
                        count[0]++;
            });

            BayesianNetwork learntBN =  new BayesianNetwork(this.dag, ef_extendedBN.toConditionalDistribution());

            CompoundVector priors = this.getNaturalParameterPrior();
            count[0] = 0;
            ef_extendedBN.getParametersVariables().getListOfParamaterVariables().stream()
                    .filter( var -> this.getPlateuStructure().getNodeOfVar(var,0).isActive())
                    .forEach( var -> {
                        EF_UnivariateDistribution uni = plateuStructure.getEFParameterPosterior(var).deepCopy();
                        uni.getNaturalParameters().copy(priors.getVectorByPosition(count[0]));
                        uni.fixNumericalInstability();
                        uni.updateMomentFromNaturalParameters();
                        ef_extendedBN.setDistribution(var, uni);
                        count[0]++;
                    });
            return learntBN;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setParallelMode(boolean parallelMode) {
        throw new UnsupportedOperationException("Non Parallel Mode Supported. Use class ParallelSVB");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <E extends UnivariateDistribution> E getParameterPosterior(Variable parameter){
        return this.getPlateuStructure().getEFVariablePosterior(parameter, 0).toUnivariateDistribution();
    }


    /**
     * Defines the batch Output.
     */
    public static class BatchOutput implements Serializable{

        /** Represents the serial version ID for serializing the object. */
        private static final long serialVersionUID = 4107783324901370839L;

        CompoundVector vector;
        double elbo;

        public BatchOutput(CompoundVector vector_, double elbo_) {
            this.vector = vector_;
            this.elbo = elbo_;
        }

        public CompoundVector getVector() {
            return vector;
        }

        public double getElbo() {
            return elbo;
        }

        public void setElbo(double elbo) {
            this.elbo = elbo;
        }

        public static BatchOutput sum(BatchOutput batchOutput1, BatchOutput batchOutput2){
            batchOutput2.getVector().sum(batchOutput1.getVector());
            batchOutput2.setElbo(batchOutput2.getElbo()+batchOutput1.getElbo());
            return batchOutput2;
        }
    }



}
