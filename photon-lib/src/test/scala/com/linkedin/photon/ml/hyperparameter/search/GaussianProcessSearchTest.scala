/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.hyperparameter.search

import breeze.linalg.{DenseMatrix, DenseVector}
import org.apache.spark.rdd.RDD
import org.mockito.Mockito._
import org.testng.Assert._
import org.testng.annotations.{DataProvider, Test}

import com.linkedin.photon.ml.evaluation.{Evaluator, EvaluatorType}
import com.linkedin.photon.ml.hyperparameter.EvaluationFunction
import com.linkedin.photon.ml.hyperparameter.estimators.kernels.Matern52

/**
 * Unit tests for [[GaussianProcessSearch]]
 */
class GaussianProcessSearchTest {

  import GaussianProcessSearchTest._

  val searcher = new GaussianProcessSearch[TestModel](
    DIM,
    EVALUATION_FUNCTION,
    EVALUATOR,
    DISCRETE_PARAMS,
    KERNEL,
    seed = SEED)

  var observedPoints: Option[DenseMatrix[Double]] = None
  var observedEvals: Option[DenseVector[Double]] = None
  var bestEval: Double = EVALUATOR.defaultScore
  var priorObservedPoints: Option[DenseMatrix[Double]] = None
  var priorObservedEvals: Option[DenseVector[Double]] = None
  var priorBestEval: Double = EVALUATOR.defaultScore

  @DataProvider
  def priorDataProvider: Array[Array[Any]] = {

    val candidate1 = (DenseVector(0.25, 0.2, 0.1999, 0.125, 0.999), 0.1)
    val candidate2 = (DenseVector(0.2, 0.2, 0.2, 0.2, 0.2), 0.2)
    val candidate3 = (DenseVector(0.3, 0.3, 0.3, 0.3, 0.3), 0.3)
    val observations = Seq(candidate1, candidate2)
    val priorObservations = Seq(candidate2, candidate3)

    Array(
      Array(observations, Seq(), 0),
      Array(observations, priorObservations, 1))
  }

  @Test(dataProvider = "priorDataProvider")
  def testFindWithPriors(
      currentCandidates: Seq[(DenseVector[Double], Double)],
      priorCandidates: Seq[(DenseVector[Double], Double)],
      testSetIndex: Int): Unit = {

    val candidates = searcher.findWithPriors(N, currentCandidates, priorCandidates)

    assertEquals(candidates.length, N)
    assertEquals(candidates.toSet.size, N)
    assertTrue(candidates.forall(_.params.toArray.forall(x => x >= 0 && x < 1)))
  }

  @Test(dataProvider = "priorDataProvider", dependsOnMethods = Array[String]("testFindWithPriors"))
  def testFindWithPriorObservations(
      currentCandidates: Seq[(DenseVector[Double], Double)],
      priorCandidates: Seq[(DenseVector[Double], Double)],
      testSetIndex: Int): Unit = {

    val candidates = searcher.findWithPriorObservations(N, priorCandidates)

    assertEquals(candidates.length, N)
    assertTrue(candidates.forall(_.params.toArray.forall(x => x >= 0 && x < 1)))
    assertEquals(candidates.size, N)
  }

  @Test(dependsOnMethods = Array[String]("testFindWithPriorObservations"))
  def testFind(): Unit = {
    val candidates = searcher.find(N)

    assertEquals(candidates.length, N)
    assertEquals(candidates.toSet.size, N)
    assertTrue(candidates.forall(_.params.toArray.forall(x => x >= 0 && x < 1)))
  }

  @DataProvider
  def bestCandidateDataProvider: Array[Array[Any]] = {
    val candidate1 = DenseVector(1.0)
    val candidate2 = DenseVector(2.0)
    val candidate3 = DenseVector(3.0)
    val candidates = DenseMatrix.vertcat(
      candidate1.asDenseMatrix,
      candidate2.asDenseMatrix,
      candidate3.asDenseMatrix)

    Array(
      Array(candidates, DenseVector(2.0, 1.0, 0.0), candidate1),
      Array(candidates, DenseVector(1.0, 2.0, 0.0), candidate2),
      Array(candidates, DenseVector(0.0, 1.0, 2.0), candidate3))
  }

  @Test(dataProvider = "bestCandidateDataProvider")
  def testSelectBestCandidate(
      candidates: DenseMatrix[Double],
      predictions: DenseVector[Double],
      expected: DenseVector[Double]): Unit = {

    val selected = searcher.selectBestCandidate(candidates, predictions)
    assertEquals(selected, expected)
  }

  @Test(dataProvider = "priorDataProvider")
  def testOnPriorObservation(
      currentCandidates: Seq[(DenseVector[Double], Double)],
      priorCandidates: Seq[(DenseVector[Double], Double)],
      testSetIndex: Int): Unit = {

    // Load the initial observations
    currentCandidates.foreach { case (candidate, value) =>
      observedPoints = observedPoints
        .map(DenseMatrix.vertcat(_, candidate.toDenseMatrix))
        .orElse(Some(candidate.toDenseMatrix))

      observedEvals = observedEvals
        .map(DenseVector.vertcat(_, DenseVector(value)))
        .orElse(Some(DenseVector(value)))

      if (EVALUATOR.betterThan(value, bestEval)) {
        bestEval = value
      }
    }

    priorCandidates.foreach { case (candidate, value) =>
      priorObservedPoints = priorObservedPoints
        .map(DenseMatrix.vertcat(_, candidate.toDenseMatrix))
        .orElse(Some(candidate.toDenseMatrix))

      priorObservedEvals = priorObservedEvals
        .map(DenseVector.vertcat(_, DenseVector(value)))
        .orElse(Some(DenseVector(value)))

      if (EVALUATOR.betterThan(value, priorBestEval)) {
        priorBestEval = value
      }
    }

    testSetIndex match {
      case 0 =>
        assertEquals(observedPoints.get.rows, 2)
        assertEquals(observedPoints.get.cols, 5)
        assertEquals(observedEvals.get.length, 2)
        assertEquals(bestEval, 0.2)
        assertFalse(priorObservedPoints.isDefined)
        assertFalse(priorObservedEvals.isDefined)
      case 1 =>
        assertEquals(observedPoints.get.rows, 4)
        assertEquals(observedPoints.get.cols, 5)
        assertEquals(observedEvals.get.length, 4)
        assertEquals(bestEval, 0.2)
        assertEquals(priorObservedPoints.get.rows, 2)
        assertEquals(priorObservedPoints.get.cols, 5)
        assertEquals(priorObservedEvals.get.length, 2)
        assertEquals(priorBestEval, 0.3)
    }
  }
}

object GaussianProcessSearchTest {

  val SEED = 1L
  val DIM = 5
  val N = 25
  val DISCRETE_PARAMS = Map(0 -> 5, 1 -> 5, 2 -> 5, 4 -> 9)
  val KERNEL = new Matern52
  val TOLERANCE = 1E-12

  case class TestModel(params: DenseVector[Double], evaluation: Double)

  val EVALUATION_FUNCTION: EvaluationFunction[TestModel] = new EvaluationFunction[TestModel] {

    def apply(hyperParameters: DenseVector[Double]): (Double, TestModel) = {
      (0.0, TestModel(hyperParameters, 0.0))
    }
    def convertObservations(results: Seq[TestModel]): Seq[(DenseVector[Double], Double)] = {
      results.map { result =>
        val candidate = vectorizeParams(result)
        val value = getEvaluationValue(result)
        (candidate, value)
      }
    }
    def vectorizeParams(result: TestModel): DenseVector[Double] = result.params
    def getEvaluationValue(result: TestModel): Double = result.evaluation
  }

  val EVALUATOR: Evaluator = new Evaluator {

    override protected[ml] val labelAndOffsetAndWeights: RDD[(Long, (Double, Double, Double))] =
      mock(classOf[RDD[(Long, (Double, Double, Double))]])
    override val evaluatorType: EvaluatorType = EvaluatorType.AUC
    override protected[ml] def evaluateWithScoresAndLabelsAndWeights(
        scoresAndLabelsAndWeights: RDD[(Long, (Double, Double, Double))]): Double = 0.0

    override def betterThan(score1: Double, score2: Double): Boolean = score1 > score2
  }
}