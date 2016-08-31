package de.tu_berlin.dima.bdapro.datagen.flink

import de.tu_berlin.dima.bdapro.datagen.flink.Distributions.{ContinousUniform, MultiVariate}
import de.tu_berlin.dima.bdapro.datagen.util.RanHash
import org.apache.flink.api.scala._
import org.apache.flink.core.fs.FileSystem
import org.apache.flink.util.{Collector, NumberSequenceIterator}


object KMeansInputGenerator {

  def main(args: Array[String]): Unit = {

    if (args.length <= 6) {
      Console.err.println("Usage: <jar> #Tasks numberOfClusters pointsPerCluster sizeX sizeY varianceDeviationMax outputPath seed")
      System.exit(-1)
    }

    val numberOfTasks = args(0).toInt
    val numberOfClusters = args(1).toInt
    val pointsPerCluster = args(2).toInt
    val sizeX = args(3).toInt
    val sizeY = args(4).toInt
    val varianceDeviationMax = args(5).toInt
    val outputPath = args(6)

    val SEED = if (args.length != 8) {
      0xC00FFEE
    } else {
      0xC00FFEE + args(7).toInt
    }

    val xDistribution = ContinousUniform(sizeX)
    val yDistribution = ContinousUniform(sizeY)
    val varianceDeviationDistribution = ContinousUniform(varianceDeviationMax)
    val correlationDeviationDistribution = ContinousUniform(lower = -1, upper = 1)

    val environment = ExecutionEnvironment.getExecutionEnvironment

    environment
      .fromParallelCollection(new NumberSequenceIterator(1, numberOfClusters))
      .setParallelism(numberOfTasks)
      .flatMap((clusterIndex, collector: Collector[(Long, Array[Double])]) => {

        val random = new RanHash(SEED + clusterIndex)

        val mean = Array[Double](xDistribution.sample(random.next()),
          yDistribution.sample(random.next()))

        val covar = Array.ofDim[Double](2, 2)

        val varx = varianceDeviationDistribution.sample(random.next())
        val vary = varianceDeviationDistribution.sample(random.next())
        val correlation = correlationDeviationDistribution.sample(random.next())

        // Multiply with transposed matrix
        covar(0)(0) = varx * varx + correlation * correlation
        covar(0)(1) = varx * correlation + vary * correlation
        covar(1)(0) = covar(0)(1)
        covar(1)(1) = vary * vary + correlation * correlation

        val distribution = MultiVariate(mean, covar, SEED + clusterIndex.toInt)

        for (a <- 1 to random.nextInt(pointsPerCluster)) {
          collector.collect((clusterIndex, distribution.sample()))
        }
      })
      .map(point => s"${point._1},${point._2(0)},${point._2(1)}")
      .writeAsText(outputPath, FileSystem.WriteMode.OVERWRITE)

    environment.execute(s"ConwayInputGenerator")
  }
}
