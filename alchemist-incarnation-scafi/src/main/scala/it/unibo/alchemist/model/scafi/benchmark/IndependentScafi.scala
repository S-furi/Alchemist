package it.unibo.alchemist.model.scafi.benchmark

import it.unibo.alchemist.model.scafi.ScafiIncarnationForAlchemist._

class IndependentScafi extends AggregateProgram with StandardSensors with ScafiAlchemistSupport {
  override def main(): Double = {
    val isSource = sense[Boolean]("source")
    val g = rep(Double.PositiveInfinity) { d =>
      mux(isSource) {
        0.0
      } {
        minHood(nbr(d) + 1.0)
      }
    }
    val result = if (g > 30.0) Double.PositiveInfinity else g
    node.put("gradient", result)
    result
  }
}
