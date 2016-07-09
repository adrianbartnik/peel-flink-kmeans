package de.tu_berlin.dima.bdapro.benchmarks.kmeans.flink

import org.apache.flink.api.scala._
import scala.collection.JavaConverters._

object KMeansData {
  val CENTROIDS = Array(
    Array(1, -31.85, -44.77),
    Array(2, 35.16, 17.46),
    Array(3, -5.16, 21.93),
    Array(4, -24.06, 6.81)
  )

  val POINTS = Array(
    Array(-14.22, -48.01),
    Array(-22.78, 37.10),
    Array(56.18, -42.99),
    Array(35.04, 50.29),
    Array(-9.53, -46.26),
    Array(-34.35, 48.25),
    Array(55.82, -57.49),
    Array(21.03, 54.64),
    Array(-13.63, -42.26),
    Array(-36.57, 32.63),
    Array(50.65, -52.40),
    Array(24.48, 34.04),
    Array(-2.69, -36.02),
    Array(-38.80, 36.58),
    Array(24.00, -53.74),
    Array(32.41, 24.96),
    Array(-4.32, -56.92),
    Array(-22.68, 29.42),
    Array(59.02, -39.56),
    Array(24.47, 45.07),
    Array(5.23, -41.20),
    Array(-23.00, 38.15),
    Array(44.55, -51.50),
    Array(14.62, 59.06),
    Array(7.41, -56.05),
    Array(-26.63, 28.97),
    Array(47.37, -44.72),
    Array(29.07, 51.06),
    Array(0.59, -31.89),
    Array(-39.09, 20.78),
    Array(42.97, -48.98),
    Array(34.36, 49.08),
    Array(-21.91, -49.01),
    Array(-46.68, 46.04),
    Array(48.52, -43.67),
    Array(30.05, 49.25),
    Array(4.03, -43.56),
    Array(-37.85, 41.72),
    Array(38.24, -48.32),
    Array(20.83, 57.85)
  )

  def getDefaultCentroidDataSet(env: ExecutionEnvironment) : DataSet[KMeans.Centroid] = {
    val centroidList = scala.collection.mutable.ListBuffer.empty[KMeans.Centroid]
    for(centroid <- CENTROIDS){
      centroidList += new KMeans.Centroid(centroid(0).toInt, centroid(1), centroid(2))
    }

    env.fromCollection(centroidList.toArray)
  }

  def getDefaultPointDataSet(env: ExecutionEnvironment) : DataSet[KMeans.Point] = {
    val pointList = scala.collection.mutable.ListBuffer.empty[KMeans.Point]
    for(point <- POINTS){
      pointList += new KMeans.Point(point(0), point(1))
    }

    env.fromCollection(pointList.toArray)
  }
}