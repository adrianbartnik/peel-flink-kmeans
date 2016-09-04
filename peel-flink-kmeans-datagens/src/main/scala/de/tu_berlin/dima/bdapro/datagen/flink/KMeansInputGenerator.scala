package de.tu_berlin.dima.bdapro.datagen.flink

import de.tu_berlin.dima.bdapro.datagen.flink.Distributions.{ContinousUniform, MultiVariate}
import de.tu_berlin.dima.bdapro.datagen.util.RanHash
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.api.scala._
import org.apache.flink.core.fs.FileSystem
import org.apache.flink.util.{Collector, NumberSequenceIterator}

/**
  *
  */
object KMeansInputGenerator {

  def main(args: Array[String]): Unit = {

    val params = ParameterTool.fromArgs(args)

    if (!params.has("outputPath")) {
      Console.err.println("Usage: <jar> --outputPath path \n" +
        "Optional parameters are: \n" +
        "--numberOfTasks - Number of tasks to use for the generation\n" +
        "--numberOfClusters - Number of clusters to generate\n" +
        "--maxPointsPerCluster - Maxmial points per cluster. Concrete number is determined randomly\n" +
        "--sizeX - Maximum X value\n" +
        "--sizeY - Maximum Y value\n" +
        "--varianceMax - Maximal variance for each cluster\n" +
        "--covarianceMax - Maximal covariance for each cluster\n" +
        "or --covariance - Covariance for each cluster as list, e.g. 1,2,3 for 3 clusters\n" +
        "--seed - Additional seed used for the random number generator")
      System.exit(-1)
    }

    val numberOfTasks = params.getInt("numberOfTasks", 1)
    val numberOfClusters = params.getInt("numberOfClusters", 5)
    val maxPointsPerCluster = params.getInt("maxPointsPerCluster", 1000)
    val sizeX = params.getInt("sizeX", 100)
    val sizeY = params.getInt("sizeY", 100)
    val varianceMax = params.getDouble("varianceMax", 3)
    val covarianceMax = Math.abs(params.getDouble("covarianceMax", 3))
    val outputPath = params.get("outputPath")

    val SEED = if (params.has("seed")) {
      0xC00FFEE + params.getInt("seed")
    } else {
      0xC00FFEE
    }

    val covariance = if (params.has("covariance")) {
      params.get("covariance").split(",").map(_.toDouble)
    } else {
      Array.empty[Double]
    }

    val xDistribution = ContinousUniform(sizeX)
    val yDistribution = ContinousUniform(sizeY)
    val varianceDistribution = ContinousUniform(varianceMax)
    val covarianceDistribution = ContinousUniform(lower = -covarianceMax, upper = covarianceMax)

    val environment = ExecutionEnvironment.getExecutionEnvironment

    environment
      .fromParallelCollection(new NumberSequenceIterator(1, numberOfClusters))
      .setParallelism(numberOfTasks)
      .flatMap((clusterIndex, collector: Collector[(Long, Array[Double])]) => {

        val random = new RanHash(SEED + clusterIndex * 100)

        val mean = Array[Double](xDistribution.sample(random.next()),
          yDistribution.sample(random.next()))

        val covar = Array.ofDim[Double](2, 2)

        val varx = varianceDistribution.sample(random.next())
        val vary = varianceDistribution.sample(random.next())

        val correlation = if (params.has("covariance")) {
          covariance(clusterIndex.toInt - 1)
        } else {
          covarianceDistribution.sample(random.next())
        }

        // Multiply with transposed matrix
        covar(0)(0) = varx * varx + correlation * correlation
        covar(0)(1) = varx * correlation + vary * correlation
        covar(1)(0) = covar(0)(1)
        covar(1)(1) = vary * vary + correlation * correlation

        val distribution = MultiVariate(mean, covar, SEED + clusterIndex.toInt)

        for (a <- 1 to Math.max(random.nextInt(maxPointsPerCluster), 1)) {
          collector.collect((clusterIndex, distribution.sample()))
        }
      })
      .map(point => s"${point._1},${point._2(0)},${point._2(1)}")
      .writeAsText(outputPath, FileSystem.WriteMode.OVERWRITE)

    environment.execute(s"KMeansInputGenerator")
  }
}
