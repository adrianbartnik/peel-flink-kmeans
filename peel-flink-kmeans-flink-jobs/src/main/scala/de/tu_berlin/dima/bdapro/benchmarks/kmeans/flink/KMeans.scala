package de.tu_berlin.dima.bdapro.benchmarks.kmeans.flink

import org.apache.flink.api.common.functions._
import org.apache.flink.api.java.functions.FunctionAnnotation.ForwardedFields
import org.apache.flink.api.java.sampling._
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.api.scala._
import org.apache.flink.configuration.Configuration
import org.apache.flink.core.fs.FileSystem.WriteMode
import org.apache.flink.util.Collector

import scala.collection.JavaConverters._
import scala.util.Random

/**
  * Based on the Flink KMeans example.
  *
  * Parameters:
  * --points <inputCSVPath>
  * --output <outputCSVPath>
  * --outputcentroids (instead of the data points, saves centroids with their respective point count)
  * --centroids <inputCSVPath> (optional, otherwise randomly chosen if not using --plusplus or --bisect)
  * --clustersize n
  * --iterations n
  * --diffiteration (terminate early if an iteration doesn't yield different results)
  * --minibatch (samples a smaller random batch of points instead of all on every iteration)
  * --samplesize n (for minibatch)
  * --plusplus (uses K-Means++ to source the centroids instead of random)
  * --bisect (uses bisecting K-Means algorithm to find clusters)
  * --iterationsamples n (for bisect)
  *
  * Expected CSV format:
  * x,y
  * centroidID,x,y
  */

object KMeans {

  private class Options {
    var env: ExecutionEnvironment = null
    var params: ParameterTool = null
    var iterations: Int = 10
    var iterateWithTermination: Boolean = false
    var clusterSize: Integer = 5
    var outputCentroids: Boolean = false
    var minibatchSampling: Boolean = false
    var minibatchSampleSize: Integer = 1000
    var plusplusCentroidSourcing: Boolean = false
    var bisectClustering: Boolean = false
    var bisectIterationSamples: Integer = 5
  }

  def main(args: Array[String]) {

    val params: ParameterTool = ParameterTool.fromArgs(args)
    val env: ExecutionEnvironment = ExecutionEnvironment.getExecutionEnvironment

    val opt = new Options()
    opt.env = env
    opt.params = params
    opt.iterations = params.getInt("iterations", opt.iterations)
    opt.iterateWithTermination = (params.has("diffiteration") || params.has("diffiterations") || opt.iterateWithTermination)
    opt.clusterSize = params.getInt("clustersize", opt.clusterSize)
    opt.outputCentroids = (params.has("outputcentroids") || opt.outputCentroids)
    opt.minibatchSampling = (params.has("minibatch") || opt.minibatchSampling)
    opt.minibatchSampleSize = params.getInt("samplesize", opt.minibatchSampleSize)
    opt.plusplusCentroidSourcing = (params.has("plusplus") || opt.plusplusCentroidSourcing)
    opt.bisectClustering = (params.has("bisect") || params.has("bisecting") || opt.bisectClustering)

    val points: DataSet[Point] = getPointDataSet(opt)

    var finalCentroids: DataSet[Centroid] = null
    if (opt.bisectClustering) {
      opt.env.setParallelism(1) //...
      finalCentroids = bisectFlow(opt, points)
    }
    else
      finalCentroids = standardFlow(opt, points)

    val clusteredPoints: DataSet[(Int, Point)] = points.map(new SelectNearestCenter).withBroadcastSet(finalCentroids, "centroids")
    var clusteredCentroids: DataSet[(Centroid, Int)] = null

    if (opt.outputCentroids)
      clusteredCentroids = finalCentroids.map(new CentroidGrouper).withBroadcastSet(clusteredPoints, "groupedpoints")

    if (params.has("output")) {
      if (opt.outputCentroids)
        clusteredCentroids.writeAsCsv(params.get("output"), "\n", ",", WriteMode.OVERWRITE)
      else
        clusteredPoints.writeAsCsv(params.get("output"), "\n", ",", WriteMode.OVERWRITE)

      env.execute()
    }
    else {
      println("Printing result to stdout. Use --output to specify output path.")

      if (opt.outputCentroids)
        clusteredCentroids.print()
      else
        clusteredPoints.print()
    }

  }

  def standardFlow(opt: Options, points: DataSet[Point]): DataSet[Centroid] = {
    var centroids: DataSet[Centroid] = null
    if (opt.plusplusCentroidSourcing)
      centroids = sourceCentroidsWithPlusPlus(opt, points)
    else {
      centroids = getCentroidDataSet(opt, points)
      opt.clusterSize = centroids.count().toInt
    }

    var finalCentroids: DataSet[Centroid] = null
    if (opt.iterateWithTermination) {
      finalCentroids = centroids.iterateWithTermination(opt.iterations) { currentCentroids =>
        val newCentroids = iterationStep(opt, points, currentCentroids)
        (newCentroids, terminalConditionDataSet(currentCentroids, newCentroids))
      }
    }
    else {
      finalCentroids = centroids.iterate(opt.iterations) { currentCentroids =>
        iterationStep(opt, points, currentCentroids)
      }
    }

    finalCentroids
  }

  def bisectFlow(opt: Options, points: DataSet[Point]): DataSet[Centroid] = {
    // A cluster with its size
    val clusters = scala.collection.mutable.ListBuffer.empty[(DataSet[Point], Long, Centroid)]
    clusters += ((points, points.count(), new Centroid(0, 0, 0)))

    for (numberOfClusters <- 1 until opt.clusterSize) {

      val (currentLargestCluster, currentLargestClusterSize, currentCentroid) = clusters.maxBy(_._2)

      //println(s"Taking cluster with size: $currentLargestClusterSize")

      var centroidSamples: DataSet[(Int, Centroid)] = null
      if (opt.plusplusCentroidSourcing) {
        val ppOpt = new Options
        ppOpt.clusterSize = 2
        ppOpt.env = opt.env
        centroidSamples = sourceCentroidsWithPlusPlus(ppOpt, currentLargestCluster).map(c => (0, new Centroid(c.id - 1, c.x, c.y)))
      }
      else {
        centroidSamples = getRandomCentroids(opt.env, currentLargestCluster, 2, opt.bisectIterationSamples)
      }

      val iterationStepFn = (currentCentroids: DataSet[(Int, Centroid)]) => {
        var sourcePoints = currentLargestCluster
        if (opt.minibatchSampling)
          sourcePoints = sourcePoints.reduceGroup(new RandomSampler(opt.minibatchSampleSize))

        sourcePoints
          .flatMap(new SelectNearestCenter3).withBroadcastSet(currentCentroids, "centroids")
          .map { x => (x._1, x._2, x._3, 1L) }.withForwardedFields("_1; _2; _3")
          .groupBy(0, 1)
          .reduce { (p1, p2) => (p1._1, p1._2, p1._3.add(p2._3), p1._4 + p2._4) }.withForwardedFields("_1; _2")
          .map { x =>
            val point = x._3.div(x._4)
            val centroid = new Centroid(x._2, point)
            centroid.similarity = point.square()
            (x._1, centroid)
          }.withForwardedFields("_1") // Missing forward for 2nd field
      }

      var finalCentroids: DataSet[(Int, Centroid)] = null
      /*if(opt.iterateWithTermination)
        finalCentroids = centroidSamples.iterateWithTermination(opt.iterations) {currentCentroids =>
          var newCentroids = iterationStepFn(currentCentroids)
          (newCentroids, terminalConditionDataSet())
        }
      else*/
      //iterateWithTermination not yet implemented for bisecting
      finalCentroids = centroidSamples.iterate(opt.iterations) { currentCentroids => iterationStepFn(currentCentroids) }

      //println(s"Final centroids")
      //finalCentroids.print()

      // Finds the best clustering, based on the smallest Sum of squared errors per clustering
      val maxClustering = currentLargestCluster.flatMap(new GetSquaredDistanceCentroid).withBroadcastSet(finalCentroids, "SumOfSquaredErrors")
        .groupBy(x => (x._1, x._2.id))
        .reduce((p1, p2) => (p1._1, p1._2, p1._3 + p2._3)) // Sum squared error per centroid
        .groupBy(_._1)
        .reduceGroup((reducer) => {
          // Creates a list of all centroids and sums similarity of each
          val result = reducer.foldLeft((List[Centroid](), 0D)) {
            (result, currentTuple) => {
              (currentTuple._2 :: result._1, result._2 + currentTuple._3)
            }
          }
          result
        }).filter(_._1.size > 1)
        .min(1)
        .flatMap((element, col: Collector[Centroid]) => {
          element._1.foreach(col.collect)
        })

      //println(s"Max cluster")
      //maxClustering.print()

      // Get separate datasets for left and right cluster
      val clusteredPoints = currentLargestCluster.map(new SelectNearestCenterSingle).withBroadcastSet(maxClustering, "singleCentroids")

      val clusteredPointsLeft: DataSet[Point] = clusteredPoints.filter(_._1 == 0).map(_._2)

      //println(s"Points left")
      //clusteredPointsLeft.print()

      val clusteredPointsRight: DataSet[Point] = clusteredPoints.filter(_._1 == 1).map(_._2)

      //println(s"Points right:")
      //clusteredPointsRight.print()

      val leftSize = clusteredPointsLeft.count()
      val rightSize = clusteredPointsRight.count()

      //println(s"New clusters have size: $leftSize and $rightSize")

      val leftCentroid = maxClustering.filter(_.id == 0).collect()(0)
      val rightCentroid = maxClustering.filter(_.id == 1).collect()(0)

      clusters -= ((currentLargestCluster, currentLargestClusterSize, currentCentroid))
      clusters +=((clusteredPointsLeft, leftSize, leftCentroid), (clusteredPointsRight, rightSize, rightCentroid))
    }

    //fix centroid IDs so they have re-usable numbering
    val centroids = clusters.map(_._3).zipWithIndex.map { case (centroid, i) => new Centroid(i + 1, centroid.x, centroid.y) }

    opt.env.fromCollection(centroids)
  }

  def sourceCentroidsWithPlusPlus(opt: Options, points: DataSet[Point]): DataSet[Centroid] = {

    // execute K-Means++ to construct initial centroids
    // select a centroid uniformly at random from among the points data set
    //val firstCentroid: DataSet[Centroid] = getFirstCentroid(points)
    val firstCentroid = getRandomCentroids(opt, points, 1)

    val weightedRandomSampler = new WeightedRandomSampler(1)

    // select k centroids by weighted probability distribution
    val centroids = firstCentroid.iterate(opt.clusterSize - 1) { currentCentroids =>

      // create the new dataset consisting of (centroid id, point, squared distance)
      val squaredDistanceSet = points
        .map(new GetSquaredDistance).withBroadcastSet(currentCentroids, "centroids")

      // get sum of squared distance
      val squaredDistanceSum = squaredDistanceSet.sum(2)
        .map { x => (x._1, x._3) }.withForwardedFields("_1")

      // select a new centroid from the points data set
      val weightedPoints = squaredDistanceSum.join(squaredDistanceSet).where(0).equalTo(0) {
        (l, r) => new IntermediateSampleData(r._3 / l._2, (r._1, r._2))
      }

      val newCentroid = weightedPoints.reduceGroup(weightedRandomSampler)
        .map { x => new Centroid(x.getElement._1, x.getElement._2) }

      // add the selected centroid to the centroids data set
      currentCentroids.union(newCentroid)
    }

    //make sure randomization is only applied once
    opt.env.fromCollection(centroids.collect())
  }

  def iterationStep(opt: Options, points: DataSet[Point], currentCentroids: DataSet[Centroid]): DataSet[Centroid] = {
    var sourcePoints = points
    if (opt.minibatchSampling)
      sourcePoints = sourcePoints.reduceGroup(new RandomSampler(opt.minibatchSampleSize))

    //println("SAMPLES: ")
    //sourcePoints.print()

    sourcePoints
      .map(new SelectNearestCenter).withBroadcastSet(currentCentroids, "centroids")
      .map { x => (x._1, x._2, 1L) }.withForwardedFields("_1; _2")
      .groupBy(0)
      .reduce { (p1, p2) => (p1._1, p1._2.add(p2._2), p1._3 + p2._3) }.withForwardedFields("_1")
      .map { x => new Centroid(x._1, x._2.div(x._3)) }.withForwardedFields("_1->id")
  }

  def terminalConditionDataSet(beforeCentroids: DataSet[Centroid], afterCentroids: DataSet[Centroid]): DataSet[Centroid] = {
    afterCentroids.coGroup(beforeCentroids)
      .where { x => (x.id, x.x, x.y) }
      .equalTo { x => (x.id, x.x, x.y) }
      .apply {
        (l, r, out: Collector[Centroid]) =>
          if (!r.hasNext)
            out.collect(l.next())
      }
  }

  /** * Data types ***/

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

    def length(): Double = {
      Math.sqrt(x * x + y * y)
    }

    def square(): Double =
      length() * length()

    def dotProduct(other: Coordinate): Double = {
      this.x * other.x + this.y * other.y
    }

    def euclideanDistance(other: Coordinate): Double =
      Math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y))

    def squaredDistance(other: Coordinate): Double =
      (x - other.x) * (x - other.x) + (y - other.y) * (y - other.y)

    def cosineDistance(other: Coordinate): Double = {
      dotProduct(other) / (this.length() * other.length())
    }

    def clear(): Unit = {
      x = 0
      y = 0
    }

    override def toString: String =
      s"$x,$y"

  }

  case class Point(var x: Double = 0, var y: Double = 0) extends Coordinate

  case class Centroid(var id: Int = 0, var x: Double = 0, var y: Double = 0) extends Coordinate {

    var similarity: Double = 0.0

    def this(id: Int, p: Point) {
      this(id, p.x, p.y)
    }

    def this(id: Int, p: Point, similarity: Double) {
      this(id, p.x, p.y)
      this.similarity = similarity
    }

    override def toString: String =
      s"$id,${super.toString}"

  }

  /** * Helper functions ***/

  def getCentroidDataSet(opt: Options, points: DataSet[Point]): DataSet[Centroid] = {
    if (opt.params.has("centroids")) {
      opt.env.readCsvFile[Centroid](
        opt.params.get("centroids"),
        fieldDelimiter = ",",
        includedFields = Array(0, 1, 2))
    } else {
      getRandomCentroids(opt, points, opt.clusterSize)
    }
  }

  def getPointDataSet(opt: Options): DataSet[Point] = {
    if (opt.params.has("points")) {
      opt.env.readCsvFile[Point](
        opt.params.get("points"),
        fieldDelimiter = ",",
        includedFields = Array(0, 1))
    } else {
      println("Executing K-Means example with example points data set.")
      println("Use --points to specify file input.")
      KMeansData.getDefaultPointDataSet(opt.env)
    }
  }

  def getFirstCentroid(points: DataSet[Point]): DataSet[Centroid] = {
    val centroid = points.reduceGroup(new RandomSampler(1)) // select a point from the dataset
      .map { x => new Centroid(1, x) }
    centroid
  }

  def getRandomCentroids(opt: Options, points: DataSet[Point], count: Int): DataSet[Centroid] = {
    //val centroids = points.reduceGroup(new RandomSampler(count)).collect().zipWithIndex.map{case(point, i) => new Centroid(i+1, point)}
    //opt.env.fromCollection(centroids)

    points.map(p => (p.x, p.x, p.y, p.y)).reduce((p1, p2) => {
      val left = if (p1._1 < p2._1) p1._1 else p2._1
      val right = if (p1._2 > p2._2) p1._2 else p2._2
      val bottom = if (p1._3 < p2._3) p1._3 else p2._3
      val top = if (p1._4 > p2._4) p1._4 else p2._4

      (left, right, bottom, top)
    })
      .flatMap((bounds, collector: Collector[Centroid]) => {
        for (i <- 1 to count)
          collector.collect(new Centroid(i, randomInRange(bounds._1, bounds._2), randomInRange(bounds._3, bounds._4)))
      })
  }

  def getRandomCentroids(env: ExecutionEnvironment, points: DataSet[Point], numberOfCentroids: Int = 2, numberOfClusters: Int = 1): DataSet[(Int, Centroid)] = {
    val limits = points
      .map(p => (p.x, p.x, p.y, p.y))
      .reduce((p1, p2) => {

        val minX = if (p1._1 < p2._1) p1._1 else p2._1
        val maxX = if (p1._2 > p2._2) p1._2 else p2._2
        val minY = if (p1._3 < p2._3) p1._3 else p2._3
        val maxY = if (p1._4 > p2._4) p1._4 else p2._4

        (minX, maxX, minY, maxY)
      })
      .flatMap((element, collector: Collector[Point]) => {
        for (counter <- 1 to numberOfCentroids * numberOfClusters) {
          collector.collect(new Point(randomInRange(element._1, element._2), randomInRange(element._3, element._4)))
        }
      }).collect()

    //println("RandomPoints: " + limits)

    env.generateSequence(0, numberOfCentroids * numberOfClusters - 1)
      .map(_.toInt)
      .map(number => (number % numberOfClusters, new Centroid(number % numberOfCentroids, limits(number), 0))) // TODO Does not work for even numberOfCluster
  }

  def randomInRange(min: Double, max: Double): Double = {
    val range = max - min
    val scaled = randomInRange_random.nextDouble() * range
    scaled + min
  }

  protected val randomInRange_random = new Random()

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

  @ForwardedFields(Array("*->_3"))
  final class SelectNearestCenter3 extends RichFlatMapFunction[Point, (Int, Int, Point)] {
    private var centroids: Map[Int, Traversable[Centroid]] = null

    /** Reads the centroid values from a broadcast variable into a collection. */
    override def open(parameters: Configuration) {
      centroids = getRuntimeContext.getBroadcastVariable[(Int, Centroid)]("centroids")
        .asScala
        .groupBy(_._1)
        .mapValues(_.map(_._2))
    }

    override def flatMap(p: Point, out: Collector[(Int, Int, Point)]): Unit = {

      centroids.foreach {
        case (clusterID, centroidList) =>

          var minDistance: Double = Double.MaxValue
          var closestCentroidId: Int = -1

          for (centroid <- centroidList) {
            val distance = p.euclideanDistance(centroid)
            if (distance < minDistance) {
              minDistance = distance
              closestCentroidId = centroid.id
            }
          }
          out.collect((clusterID, closestCentroidId, p))
      }
    }
  }

  @ForwardedFields(Array("*->_2"))
  final class SelectNearestCenterSingle extends RichMapFunction[Point, (Int, Point)] {
    private var centroids: Traversable[Centroid] = null

    /** Reads the centroid values from a broadcast variable into a collection. */
    override def open(parameters: Configuration) {
      centroids = getRuntimeContext.getBroadcastVariable[Centroid]("singleCentroids").asScala
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

  @ForwardedFields(Array("*->_1"))
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

  final class GetSquaredDistanceCentroid extends RichFlatMapFunction[Point, (Int, Centroid, Double)] {
    private var centroids: Map[Int, Traversable[Centroid]] = null

    /** Reads the centroid values from a broadcast variable into a collection. */
    override def open(parameters: Configuration) {
      centroids = getRuntimeContext.getBroadcastVariable[(Int, Centroid)]("SumOfSquaredErrors")
        .asScala
        .groupBy(_._1)
        .mapValues(_.map(_._2))
    }

    override def flatMap(p: Point, out: Collector[(Int, Centroid, Double)]): Unit = {

      centroids.foreach {
        case (clusterID, centroidList) =>

          var minDistance: Double = Double.MaxValue
          var closestCentroid: Centroid = null

          for (centroid <- centroidList) {
            val distance = p.euclideanDistance(centroid)
            if (distance < minDistance) {
              minDistance = distance
              closestCentroid = centroid
            }
          }
          out.collect((clusterID, closestCentroid, minDistance * minDistance))
      }
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
      //if (sample.hasNext)
      //  out.collect(sample.next().getElement)
      sample.asScala.foreach(value => out.collect(value.getElement()))
    }

  }

}