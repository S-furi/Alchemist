/*
 * Copyright (C) 2010-2026, Danilo Pianini and contributors
 * listed, for each module, in the respective subproject's build.gradle.kts file.
 *
 * This file is part of Alchemist, and is distributed under the terms of the
 * GNU General Public License, with a linking exception,
 * as described in the file LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.core.reactive

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import it.unibo.alchemist.boundary.OutputMonitor
import it.unibo.alchemist.core.Engine
import it.unibo.alchemist.core.ReactiveEngine
import it.unibo.alchemist.core.Simulation
import it.unibo.alchemist.model.Actionable
import it.unibo.alchemist.model.Environment
import it.unibo.alchemist.model.Node
import it.unibo.alchemist.model.Position
import it.unibo.alchemist.model.Reaction
import it.unibo.alchemist.model.Time
import it.unibo.alchemist.model.TimeDistribution
import it.unibo.alchemist.model.biochemistry.BiochemistryIncarnation
import it.unibo.alchemist.model.biochemistry.molecules.Biomolecule
import it.unibo.alchemist.model.conditions.MoleculeHasConcentration
import it.unibo.alchemist.model.environments.Continuous2DEnvironment
import it.unibo.alchemist.model.linkingrules.NoLinks
import it.unibo.alchemist.model.nodes.GenericNode
import it.unibo.alchemist.model.reactions.AbstractReaction
import it.unibo.alchemist.model.timedistributions.DiracComb
import it.unibo.alchemist.model.times.DoubleTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.optionals.getOrNull
import org.junit.jupiter.api.fail

class TestEngineComparison : FreeSpec({

    data class TraceEntry(val time: Double, val nodeId: Int, val molecule: String, val concentration: Double)

    class TraceMonitor<T, P : Position<out P>> : OutputMonitor<T, P> {
        val trace = CopyOnWriteArrayList<TraceEntry>()

        override fun stepDone(environment: Environment<T, P>, reaction: Actionable<T>?, time: Time, step: Long) {
            environment.nodes.forEach { node ->
                node.contents.forEach { (mol, conc) ->
                    trace.add(TraceEntry(time.toDouble(), node.id, mol.name, (conc as Number).toDouble()))
                }
            }
        }
    }

    class SimpleReaction<T>(
        node: Node<T>,
        distribution: TimeDistribution<T>,
        val action: () -> Unit,
    ) : AbstractReaction<T>(node, distribution) {
        override fun updateInternalStatus(
            currentTime: Time,
            hasBeenExecuted: Boolean,
            environment: Environment<T, *>,
        ) = Unit

        override fun cloneOnNewNode(node: Node<T>, currentTime: Time): Reaction<T> = throw NotImplementedError()

        override fun execute() {
            action()
        }
    }

    fun prepareEnvironment(): Continuous2DEnvironment<Double> {
        val incarnation = BiochemistryIncarnation()
        val environment = Continuous2DEnvironment(incarnation)
        environment.linkingRule = NoLinks()
        environment.addTerminator { it.simulation.time > DoubleTime(10.0) }
        return environment
    }

    fun runAndTrace(
        setup: (Continuous2DEnvironment<Double>) -> Unit,
        engineFactory: (Environment<Double, *>) -> Simulation<Double, *>,
    ): List<TraceEntry> {
        val environment = prepareEnvironment()
        setup(environment)
        val monitor = TraceMonitor<Double, Position<*>>()

        @Suppress("UNCHECKED_CAST")
        val engine = engineFactory(environment as Environment<Double, *>) as Simulation<Double, Position<*>>
        engine.addOutputMonitor(monitor)

        engine.play()
        engine.run()

        engine.error.getOrNull()?.let { err ->
            fail { "Simulation failed with an error: ${err.message}: ${err.stackTraceToString()}" }
        }

        return monitor.trace
    }

    "Compare Engine and ReactiveEngine" - {

        "Single reaction" {
            val setup = { env: Continuous2DEnvironment<Double> ->
                val node = GenericNode(env)
                val a = Biomolecule("A")
                val b = Biomolecule("B")
                val c = Biomolecule("C")

                node.setConcentration(a, 1.0)
                node.setConcentration(b, 0.0)
                node.setConcentration(c, 0.0)

                SimpleReaction(node, DiracComb(1.0)) {
                    node.setConcentration(a, 0.0)
                    node.setConcentration(b, 1.0)
                }.apply {
                    conditions = listOf(MoleculeHasConcentration(node, a, 1.0))
                    node.addReaction(this)
                }

                env.addNode(node, env.makePosition(0, 0))
                Unit
            }

            val traceEngine = runAndTrace(setup) { Engine(it) }
            val traceReactive = runAndTrace(setup) { ReactiveEngine(it) }

            traceReactive shouldContainExactly traceEngine
        }

        "Simple mutiple reactions" {
            val setup = { env: Continuous2DEnvironment<Double> ->
                val node = GenericNode(env)
                val a = Biomolecule("A")
                val b = Biomolecule("B")
                val c = Biomolecule("C")

                node.setConcentration(a, 1.0)
                node.setConcentration(b, 0.0)
                node.setConcentration(c, 0.0)

                SimpleReaction(node, DiracComb(1.0)) {
                    node.setConcentration(b, node.getConcentration(b) + 1.0)
                }.apply {
                    conditions = listOf(MoleculeHasConcentration(node, a, 1.0))
                    node.addReaction(this)
                }

                SimpleReaction(node, DiracComb(0.5)) {
                    node.setConcentration(c, node.getConcentration(c) + 1.0)
                }.apply {
                    conditions = listOf(MoleculeHasConcentration(node, a, 1.0))
                    node.addReaction(this)
                }

                env.addNode(node, env.makePosition(0, 0))
                Unit
            }

            val traceEngine = runAndTrace(setup) { Engine(it) }
            val traceReactive = runAndTrace(setup) { ReactiveEngine(it) }

            traceReactive shouldContainExactly traceEngine
        }

        "Mutliple interleaving reactions" {
            val setup = { env: Continuous2DEnvironment<Double> ->
                val node = GenericNode(env)
                val a = Biomolecule("A")
                val b = Biomolecule("B")

                node.setConcentration(a, 1.0)
                node.setConcentration(b, 0.0)

                SimpleReaction(node, DiracComb(1.0)) {
                    node.setConcentration(a, 0.0)
                    node.setConcentration(b, 1.0)
                }.apply {
                    conditions = listOf(MoleculeHasConcentration(node, a, 1.0))
                    node.addReaction(this)
                }

                SimpleReaction(node, DiracComb(1.0)) {
                    node.setConcentration(b, 0.0)
                    node.setConcentration(a, 1.0)
                }.apply {
                    conditions = listOf(MoleculeHasConcentration(node, b, 1.0))
                    node.addReaction(this)
                }

                env.addNode(node, env.makePosition(0, 0))
                Unit
            }

            val traceEngine = runAndTrace(setup) { Engine(it) }
            val traceReactive = runAndTrace(setup) { ReactiveEngine(it) }

            traceReactive shouldContainExactly traceEngine
        }
    }
})
