package de.tu_berlin.dima.bdapro.datagen.flink

import org.apache.commons.math3.distribution._
import org.apache.commons.math3.random.Well19937c

object Distributions {

  case class ContinousUniform(upper: Int, lower: Int = 0) {
    val distribution = new UniformRealDistribution(lower, upper)

    def sample(cp: Double) = distribution.inverseCumulativeProbability(cp)
  }

  case class MultiVariate(means: Array[Double], covariances: Array[Array[Double]], seed: Int) {
    val distribution = new MultivariateNormalDistribution(new Well19937c(seed), means, covariances)

    def sample() = distribution.sample()
  }

}
