package de.tu_berlin.dima.bdapro.datagen.flink

import org.apache.commons.math3.distribution._
import org.apache.commons.math3.random.Well19937c

object Distributions {

  trait ContinousDistribution {
    def sample(cumulativeProbability: Double): Double
  }

  case class ContinousUniform(k: Int) extends ContinousDistribution {
    val distribution = new UniformRealDistribution(0, k - 1)

    def sample(cp: Double) = distribution.inverseCumulativeProbability(cp)
  }

  case class MultiVariate(means: Array[Double], covariances: Array[Array[Double]], seed: Int) {
    val distribution = new MultivariateNormalDistribution(new Well19937c(seed), means, covariances)

    def sample() = distribution.sample()
  }

}
