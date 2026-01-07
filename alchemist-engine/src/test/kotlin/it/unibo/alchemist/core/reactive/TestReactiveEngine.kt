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
import io.kotest.matchers.shouldBe
import it.unibo.alchemist.core.ReactiveEngine
import it.unibo.alchemist.model.Environment
import it.unibo.alchemist.model.Node
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
import kotlin.jvm.optionals.getOrNull
import org.junit.jupiter.api.fail

class TestReactiveEngine : FreeSpec({

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

    "ReactiveEngine should execute a reaction when condition is met" {
        val incarnation = BiochemistryIncarnation()
        val environment = Continuous2DEnvironment(incarnation)
        val node = GenericNode(environment)
        val molecule = Biomolecule("A")
        val initialConcentration = 1.0
        node.setConcentration(molecule, initialConcentration)

        SimpleReaction(node, DiracComb(1.0)) {
            node.setConcentration(molecule, 0.0)
        }.apply {
            conditions = listOf(MoleculeHasConcentration(node, molecule, initialConcentration))
            node.addReaction(this)
        }

        environment.linkingRule = NoLinks()
        environment.addNode(node, environment.makePosition(0, 0))

        environment.addTerminator { it.simulation.time.toDouble() > 5.0 }

        val engine = ReactiveEngine(environment)

        engine.play()
        engine.run()

        engine.error.getOrNull()?.let { err ->
            fail { "Simulation failed with an error: ${err.message}: ${err.stackTraceToString()}" }
        }

        node.getConcentration(molecule) shouldBe 0.0
    }

    "ReactiveEngine should react to environment changes" {
        val incarnation = BiochemistryIncarnation()
        val environment = Continuous2DEnvironment(incarnation)
        val node = GenericNode(environment)
        val molecule = Biomolecule("B")
        node.setConcentration(molecule, 0.0)

        SimpleReaction(node, DiracComb(0.5)) {
            node.setConcentration(molecule, 1.0)
        }.apply { node.addReaction(this) }

        SimpleReaction(node, DiracComb(1.0)) {
            node.setConcentration(molecule, 2.0)
        }.apply {
            conditions = listOf(MoleculeHasConcentration(node, molecule, 1.0))
            node.addReaction(this)
        }

        environment.addNode(node, environment.makePosition(0, 0))
        environment.addTerminator { it.simulation.time > DoubleTime(5.0) }

        val engine = ReactiveEngine(environment)
        engine.play()
        engine.run()

        engine.error.getOrNull()?.let { err ->
            fail { "Simulation failed with an error: ${err.message}: ${err.stackTraceToString()}" }
        }

        node.getConcentration(molecule) shouldBe 2.0
    }
})
