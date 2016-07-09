package de.tu_berlin.dima.bdapro.benchmarks.kmeans.flink

import org.apache.flink.api.common.functions._
import org.apache.flink.api.java.functions.FunctionAnnotation.ForwardedFields
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.api.scala._
import org.apache.flink.configuration.Configuration
import org.apache.flink.util.Collector
import org.apache.flink.api.java.sampling._

import scala.collection.JavaConverters._

/**
  * Based on the Flink KMeans example.
  *
  * Parameters:
  *   --centroids <inputCSVPath>
  *   --points <inputCSVPath>
  *   --output <outputCSVPath>
  *   --outputcentroids (instead of the data points, saves centroids with their respective point count)
  *   --algorithm [default|minibatch|plusplus|bisecting]
  *   --samplesize n (for minibatch)
  *   --iterations n (for minibatch)
  *   --clustersize n (for plusplus)
  *
  * Expected CSV format:
  *   x,y
  *   centroidID,x,y
  */

object KMeans {

  def main(args: Array[String]) {

    val params: ParameterTool = ParameterTool.fromArgs(args)
    val env: ExecutionEnvironment = ExecutionEnvironment.getExecutionEnvironment

    val points: DataSet[Point] = getPointDataSet(params, env)
    val centroids: DataSet[Centroid] = getCentroidDataSet(params, env)

    var finalCentroids: DataSet[Centroid] = null

    val algorithm = params.get("algorithm", "default")
    if(algorithm == "minibatch")
      finalCentroids = miniBatch(params, env, points, centroids)
    else if(algorithm == "plusplus")
      finalCentroids = plusPlus(params, env, points, centroids)
    else if(algorithm == "bisecting")
      finalCentroids = bisecting(params, env, points, centroids)
    else
      finalCentroids = defaultAlgorithm(params, env, points, centroids)

    val clusteredPoints: DataSet[(Int, Point)] = points.map(new SelectNearestCenter).withBroadcastSet(finalCentroids, "centroids")
    var clusteredCentroids: DataSet[(Centroid, Int)] = null

    if(params.has("outputcentroids"))
      clusteredCentroids = finalCentroids.map(new CentroidGrouper).withBroadcastSet(clusteredPoints, "groupedpoints")

    if(params.has("output")) {

      if(params.has("outputcentroids"))
        clusteredCentroids.writeAsCsv(params.get("output"), "\n", ",")
      else
        clusteredPoints.writeAsCsv(params.get("output"), "\n", ",")

      env.execute()
    }
    else {
      println("Printing result to stdout. Use --output to specify output path.")

      if(params.has("outputcentroids"))
        clusteredCentroids.print()
      else
        clusteredPoints.print()

    }

  }

  def defaultAlgorithm(params: ParameterTool, env: ExecutionEnvironment, points: DataSet[Point], centroids: DataSet[Centroid]): DataSet[Centroid] = {

    centroids.iterate(params.getInt("iterations", 10)) { currentCentroids =>
      val newCentroids = points
        .map(new SelectNearestCenter).withBroadcastSet(currentCentroids, "centroids")
        .map { x => (x._1, x._2, 1L) }.withForwardedFields("_1; _2")
        .groupBy(0)
        .reduce { (p1, p2) => (p1._1, p1._2.add(p2._2), p1._3 + p2._3) }.withForwardedFields("_1")
        .map { x => new Centroid(x._1, x._2.div(x._3)) }.withForwardedFields("_1->id")
      newCentroids
    }

  }

  def miniBatch(params: ParameterTool, env: ExecutionEnvironment, points: DataSet[Point], centroids: DataSet[Centroid]): DataSet[Centroid] = {

    val randomSampler = new RandomSampler(params.getInt("samplesize", 10))

    centroids.iterate(params.getInt("iterations", 10)) { currentCentroids =>
      val samplePoints = points.reduceGroup(randomSampler)
      val newCentroids = samplePoints
        .map(new SelectNearestCenter).withBroadcastSet(currentCentroids, "centroids")
        .map { x => (x._1, x._2, 1L) }.withForwardedFields("_1; _2")
        .groupBy(0)
        .reduce { (p1, p2) => (p1._1, p1._2.add(p2._2), p1._3 + p2._3) }.withForwardedFields("_1")
        .map { x => new Centroid(x._1, x._2.div(x._3)) }.withForwardedFields("_1->id")
      newCentroids
    }

  }

  def plusPlus(params: ParameterTool, env: ExecutionEnvironment, points: DataSet[Point], centroids: DataSet[Centroid]): DataSet[Centroid] = {

    val clusterSize = params.getInt("clustersize", 10)

    // execute K-Means++ to construct initial centroids
    // select a centroid uniformly at random from among the points data set
    val firstCentroid: DataSet[Centroid] = getFirstCentroid(points)

    val weightedRandomSampler = new WeightedRandomSampler(1)

    // select k centroids by weighted probability distribution
    val initialCentroids = firstCentroid.iterate(clusterSize - 1) { currentCentroids =>

      // create the new dataset consisting of (centroid id, point, squared distance)
      val squaredDistanceSet = points
        .map(new GetSquaredDistance).withBroadcastSet(currentCentroids, "centroids")

      // get sum of squared distance
      val squaredDistanceSum = squaredDistanceSet.sum(2)
        .map { x => (x._1, x._3) }.withForwardedFields("_1")

      // select a new centroid from the points data set
      val newCentroid = squaredDistanceSum.join(squaredDistanceSet).where(0).equalTo(0) {
        (l, r) => new IntermediateSampleData(r._3 / l._2, (r._1, r._2))
      }
        .reduceGroup(weightedRandomSampler)
        .map { x => new Centroid(x.getElement._1, x.getElement._2) }

      // add the selected centroid to the centroids data set
      currentCentroids.union(newCentroid)
    }

    // execute K-Means with initial centroids
    initialCentroids.iterateWithTermination(Int.MaxValue) { currentCentroids =>
      val newCentroids = points
        .map(new SelectNearestCenter).withBroadcastSet(currentCentroids, "centroids")
        .map { x => (x._1, x._2, 1L) }.withForwardedFields("_1; _2")
        .groupBy(0)
        .reduce { (p1, p2) => (p1._1, p1._2.add(p2._2), p1._3 + p2._3) }.withForwardedFields("_1")
        .map { x => new Centroid(x._1, x._2.div(x._3)) }.withForwardedFields("_1->id")

      // use the difference set as terminal condition
      val term: DataSet[Centroid] = newCentroids.coGroup(currentCentroids)
        .where { x => (x.id, x.x, x.y) }
        .equalTo { x => (x.id, x.x, x.y) }
        .apply {
          (l, r, out: Collector[Centroid]) =>
            if (!r.hasNext)
              out.collect(l.next())
        }

      (newCentroids, term)
    }

  }

  def bisecting(params: ParameterTool, env: ExecutionEnvironment, points: DataSet[Point], centroids: DataSet[Centroid]): DataSet[Centroid] = {
    null
  }

  /*** Data types ***/

  trait Coordinate extends Serializable {

    var x: Double
    var y: Double

    def add(other: Coordinate): this.type = {
      x += other.x
      y += other.y
      this
    }

    def div(other: Long): this.type = {
      x /= other
      y /= other
      this
    }

    def euclideanDistance(other: Coordinate): Double =
      Math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y))

    def squaredDistance(other: Coordinate): Double =
      (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y)

    def clear(): Unit = {
      x = 0
      y = 0
    }

    override def toString: String =
      s"$x $y"

  }

  case class Point(var x: Double = 0, var y: Double = 0) extends Coordinate

  case class Centroid(var id: Int = 0, var x: Double = 0, var y: Double = 0) extends Coordinate {

    def this(id: Int, p: Point) {
      this(id, p.x, p.y)
    }

    override def toString: String =
      s"$id ${super.toString}"

  }

  /*** Helper functions ***/

  def getCentroidDataSet(params: ParameterTool, env: ExecutionEnvironment): DataSet[Centroid] = {
    if (params.has("centroids")) {
      env.readCsvFile[Centroid](
        params.get("centroids"),
        fieldDelimiter = ",",
        includedFields = Array(0, 1, 2))
    } else {
      println("Executing K-Means example with default centroid data set.")
      println("Use --centroids to specify file input.")
      KMeansData.getDefaultCentroidDataSet(env)
    }
  }

  def getPointDataSet(params: ParameterTool, env: ExecutionEnvironment): DataSet[Point] = {
    if (params.has("points")) {
      env.readCsvFile[Point](
        params.get("points"),
        fieldDelimiter = ",",
        includedFields = Array(0, 1))
    } else {
      println("Executing K-Means example with default points data set.")
      println("Use --points to specify file input.")
      KMeansData.getDefaultPointDataSet(env)
    }
  }

  def getFirstCentroid(points: DataSet[Point]): DataSet[Centroid] = {
    val centroid = points.reduceGroup(new RandomSampler(1)) // select a point from the dataset
      .map { x => new Centroid(1, x) }
    centroid
  }

  @ForwardedFields(Array("*->_2"))
  final class SelectNearestCenter extends RichMapFunction[Point, (Int, Point)] {
    private var centroids: Traversable[Centroid] = null

    override def open(parameters: Configuration) {
      centroids = getRuntimeContext.getBroadcastVariable[Centroid]("centroids").asScala
    }

    def map(p: Point): (Int, Point) = {
      var minDistance: Double = Double.MaxValue
      var closestCentroidId: Int = -1
      for (centroid <- centroids) {
        val distance = p.euclideanDistance(centroid)
        if (distance < minDistance) {
          minDistance = distance
          closestCentroidId = centroid.id
        }
      }
      (closestCentroidId, p)
    }

  }

  final class CentroidGrouper extends RichMapFunction[Centroid, (Centroid, Int)] {
    private var groupedPoints: Traversable[(Int, Point)] = null

    override def open(parameters: Configuration) {
      groupedPoints = getRuntimeContext.getBroadcastVariable[(Int, Point)]("groupedpoints").asScala
    }

    def map(centroid: Centroid): (Centroid, Int) = {
      (centroid, groupedPoints.count(p => p._1 == centroid.id))
    }

  }

  @ForwardedFields(Array("*->_2"))
  final class GetSquaredDistance extends RichMapFunction[Point, (Int, Point, Double)] {
    private var centroids: Traversable[Centroid] = null

    // Reads the centroid values from a broadcast variable into a collection.
    override def open(parameters: Configuration) {
      centroids = getRuntimeContext.getBroadcastVariable[Centroid]("centroids").asScala
    }

    def map(p: Point): (Int, Point, Double) = {
      var minDistance: Double = Double.MaxValue
      val centroidId: Int = centroids.size + 1
      for (centroid <- centroids) {
        val distance = p.squaredDistance(centroid)
        if (distance < minDistance)
          minDistance = distance
      }
      (centroidId, p, minDistance)
    }

  }

  class RandomSampler(sampleSize: Int) extends GroupReduceFunction[Point, Point] {

    //private val sampler = new ReservoirSamplerWithoutReplacement[Point](sampleSize)
    private val batchSize = sampleSize

    def reduce(values: java.lang.Iterable[Point], out: Collector[Point]): Unit = {
      val sampler = new ReservoirSamplerWithoutReplacement[Point](batchSize)
      val sample = sampler.sampleInPartition(values.iterator())
      sample.asScala.foreach(value => out.collect(value.getElement()))
    }

  }

  class WeightedRandomSampler(sampleSize: Int) extends GroupReduceFunction[IntermediateSampleData[(Int, Point)], IntermediateSampleData[(Int, Point)]] {
    private val batchSize = sampleSize

    def reduce(values: java.lang.Iterable[IntermediateSampleData[(Int, Point)]], out: Collector[IntermediateSampleData[(Int, Point)]]): Unit = {
      val sampler = new WeightedRandomSamplerWithoutReplacement[IntermediateSampleData[(Int, Point)]](batchSize)
      val sample = sampler.sampleInPartition(values.iterator())
      sample.asScala.foreach(value => out.collect(value.getElement()))
    }

  }

}
