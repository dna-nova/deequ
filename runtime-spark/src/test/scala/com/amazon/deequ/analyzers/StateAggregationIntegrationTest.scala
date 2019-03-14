/**
  * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the "License"). You may not
  * use this file except in compliance with the License. A copy of the License
  * is located at
  *
  *     http://aws.amazon.com/apache2.0/
  *
  * or in the "license" file accompanying this file. This file is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
  * express or implied. See the License for the specific language governing
  * permissions and limitations under the License.
  *
  */

package com.amazon.deequ.analyzers

import com.amazon.deequ.SparkContextSpec
import com.amazon.deequ.checks.{Check, CheckLevel}
import com.amazon.deequ.metrics.Metric
import com.amazon.deequ.runtime.spark.{InMemorySparkStateProvider, OperatorList, SparkEngine}
import com.amazon.deequ.runtime.spark.executor.SparkExecutor
import com.amazon.deequ.runtime.spark.operators._
import com.amazon.deequ.utils.FixtureSupport
import org.apache.spark.sql.Row
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.scalatest.{Matchers, WordSpec}
import org.apache.spark.sql.functions.expr

class StateAggregationIntegrationTest extends WordSpec with Matchers with SparkContextSpec
  with FixtureSupport {

  "State aggregation" should {
    "work correctly over data partitions" in withSparkSession { session =>

      val schema = StructType(
        StructField("item", StringType, nullable = false) ::
        StructField("origin", StringType, nullable = true) ::
        StructField("sales", IntegerType, nullable = false) ::
        StructField("marketplace", StringType, nullable = false) :: Nil)

      val rowData = Seq(
        Row("item1", "US", 100, "EU"),
        Row("item1", "US", 1000, "NA"),
        Row("item1", "US", 20, "IN"),

        Row("item2", "DE", 20, "EU"),
        Row("item2", "DE", 333, "NA"),

        Row("item3", null, 12, "NA"),
        Row("item4", null, 45, "NA"),
        Row("item5", null, 123, "NA"))

      val data = session.createDataFrame(session.sparkContext.parallelize(rowData, 2), schema)

      val partitionNA = data.where(expr("marketplace = 'NA'"))
      val partitionEU = data.where(expr("marketplace = 'EU'"))
      val partitionIN = data.where(expr("marketplace = 'IN'"))

      val completeness = CompletenessOp("origin")

      val completenessStateNA = completeness.computeStateFrom(partitionNA)
      val completenessStateEU = completeness.computeStateFrom(partitionEU)
      val completenessStateIN = completeness.computeStateFrom(partitionIN)

      val completenessState = Operators.merge(completenessStateNA, completenessStateEU,
        completenessStateIN)

      val completenessNA = completeness.computeMetricFrom(completenessStateNA)
      val completenessEU = completeness.computeMetricFrom(completenessStateEU)
      val completenessIN = completeness.computeMetricFrom(completenessStateIN)
      val overallCompleteness = completeness.computeMetricFrom(completenessState)

      println(s"Completeness of origin in NA partition: ${completenessNA.value}")
      println(s"Completeness of origin in EU partition: ${completenessEU.value}")
      println(s"Completeness of origin in IN partition: ${completenessIN.value}")
      println(s"Completeness of origin overall: ${overallCompleteness.value}")

      assert(overallCompleteness == completeness.calculate(data))

      println()

      val standardDeviation = StandardDeviationOp("sales")

      val standardDeviationStateNA = standardDeviation.computeStateFrom(partitionNA)
      val standardDeviationStateEU = standardDeviation.computeStateFrom(partitionEU)
      val standardDeviationStateIN = standardDeviation.computeStateFrom(partitionIN)

      val standardDeviationState = Operators.merge(standardDeviationStateNA,
        standardDeviationStateEU, standardDeviationStateIN)

      val standardDeviationNA =
        standardDeviation.computeMetricFrom(standardDeviationStateNA)
      val standardDeviationEU =
        standardDeviation.computeMetricFrom(standardDeviationStateEU)
      val standardDeviationIN =
        standardDeviation.computeMetricFrom(standardDeviationStateIN)
      val overallStandardDeviation =
        standardDeviation.computeMetricFrom(standardDeviationState)

      println(s"Standard deviation of sales in NA partition: ${standardDeviationNA.value}")
      println(s"Standard deviation of sales in EU partition: ${standardDeviationEU.value}")
      println(s"Standard deviation of sales in IN partition: ${standardDeviationIN.value}")
      println(s"Standard deviation of sales overall: ${overallStandardDeviation.value}")

      assert(overallStandardDeviation == standardDeviation.calculate(data))

      println()

      val distinctness = DistinctnessOp("item")

      val distinctnessStateNA = distinctness.computeStateFrom(partitionNA)
      val distinctnessStateEU = distinctness.computeStateFrom(partitionEU)
      val distinctnessStateIN = distinctness.computeStateFrom(partitionIN)

      val distinctnessState = Operators.merge(distinctnessStateNA, distinctnessStateEU,
        distinctnessStateIN)

      val distinctnessNA = distinctness.computeMetricFrom(distinctnessStateNA)
      val distinctnessEU = distinctness.computeMetricFrom(distinctnessStateEU)
      val distinctnessIN = distinctness.computeMetricFrom(distinctnessStateIN)
      val overallDistinctness = distinctness.computeMetricFrom(distinctnessState)

      println(s"Distinctness of item in NA partition: ${distinctnessNA.value}")
      println(s"Distinctness of item in EU partition: ${distinctnessEU.value}")
      println(s"Distinctness of item in IN partition: ${distinctnessIN.value}")
      println(s"Distinctness of item overall: ${overallDistinctness.value}")

      assert(overallDistinctness == distinctness.calculate(data))
    }

    "work correctly via AnalysisRunner" in withSparkSession { session =>

      val schema = StructType(
        StructField("item", StringType, nullable = false) ::
        StructField("origin", StringType, nullable = true) ::
        StructField("sales", IntegerType, nullable = false) ::
        StructField("marketplace", StringType, nullable = false) :: Nil)

      val rowData = Seq(
        Row("item1", "US", 100, "EU"),
        Row("item1", "US", 1000, "NA"),
        Row("item1", "US", 20, "IN"),

        Row("item2", "DE", 20, "EU"),
        Row("item2", "DE", 333, "NA"),

        Row("item3", null, 12, "NA"),
        Row("item4", null, 45, "NA"),
        Row("item5", null, 123, "NA"))

      val data = session.createDataFrame(session.sparkContext.parallelize(rowData, 2), schema)

      val partitionNA = data.where(expr("marketplace = 'NA'"))
      val partitionEU = data.where(expr("marketplace = 'EU'"))
      val partitionIN = data.where(expr("marketplace = 'IN'"))

      val greaterThanHalf: Double => Boolean = { _ > 0.5 }

      val check = Check(CheckLevel.Error, "check")
        .isComplete("item")
        .isNonNegative("sales")
        .isContainedIn("marketplace", Array("EU", "NA", "IN"))
        .hasApproxCountDistinct("item", _ < 10)
        .hasUniqueness(Seq("item"), greaterThanHalf)
        .hasUniqueValueRatio(Seq("item"), greaterThanHalf)

      val analyzersFromChecks = Seq(check)
        .flatMap { _.requiredAnalyzers() }
        .map { SparkEngine.matchingOperator }

      val analysis = analyzersFromChecks.map { _.asInstanceOf[Operator[State[_], Metric[_]]] }

      val statesNA = InMemorySparkStateProvider()
      val statesEU = InMemorySparkStateProvider()
      val statesIN = InMemorySparkStateProvider()

      // TODO: Since the new fluent API does not support saving of states yet, the deprecated
      // TODO: method is still needed here
      SparkExecutor.run(partitionNA, analysis, saveStatesWith = Some(statesNA))
      SparkExecutor.run(partitionEU, analysis, saveStatesWith = Some(statesEU))
      SparkExecutor.run(partitionIN, analysis, saveStatesWith = Some(statesIN))

      val resultsFromAggregation = SparkExecutor
        .runOnAggregatedStates(schema, analysis, Seq(statesNA, statesEU, statesIN))

      val results = SparkExecutor.doAnalysisRun(
        data,
        analyzersFromChecks
      )

      assert(resultsFromAggregation == results)
    }

//    "work correctly via VerificationSuite" in withSparkSession { session =>
//
//      val schema = StructType(
//        StructField("item", StringType, nullable = false) ::
//        StructField("origin", StringType, nullable = true) ::
//        StructField("sales", IntegerType, nullable = false) ::
//        StructField("marketplace", StringType, nullable = false) :: Nil)
//
//      val rowData = Seq(
//        Row("item1", "US", 100, "EU"),
//        Row("item1", "US", 1000, "NA"),
//        Row("item1", "US", 20, "IN"),
//
//        Row("item2", "DE", 20, "EU"),
//        Row("item2", "DE", 333, "NA"),
//
//        Row("item3", null, 12, "NA"),
//        Row("item4", null, 45, "NA"),
//        Row("item5", null, 123, "NA"))
//
//      val data = session.createDataFrame(session.sparkContext.parallelize(rowData, 2), schema)
//
//      val partitionNA = data.where(expr("marketplace = 'NA'"))
//      val partitionEU = data.where(expr("marketplace = 'EU'"))
//      val partitionIN = data.where(expr("marketplace = 'IN'"))
//
//      val greaterThanHalf: Double => Boolean = { _ > 0.5 }
//
//      val check = Check(CheckLevel.Error, "check")
//        .isComplete("item")
//        .isNonNegative("sales")
//        .isContainedIn("marketplace", Array("EU", "NA", "IN"))
//        .hasApproxCountDistinct("item", _ < 10)
//        .hasUniqueness(Seq("item"), greaterThanHalf)
//        .hasUniqueValueRatio(Seq("item"), greaterThanHalf)
//
//      val analyzersFromChecks = Seq(check)
//        .flatMap { _.requiredAnalyzers() }
//        .map { SparkEngine.matchingOperator }
//
//      val analysis = Analysis(analyzersFromChecks)
//
//      val statesNA = InMemoryStateProvider()
//      val statesEU = InMemoryStateProvider()
//      val statesIN = InMemoryStateProvider()
//
//      AnalysisRunner.run(partitionNA, analysis, saveStatesWith = Some(statesNA))
//      AnalysisRunner.run(partitionEU, analysis, saveStatesWith = Some(statesEU))
//      AnalysisRunner.run(partitionIN, analysis, saveStatesWith = Some(statesIN))
//
//      val resultsDirect = VerificationSuite().onData(data).addCheck(check).run()
//      val resultsFromStates = VerificationSuite.runOnAggregatedStates(schema, Seq(check),
//        Seq(statesNA, statesEU, statesIN))
//
//      assert(resultsFromStates == resultsDirect)
//    }

  }

}