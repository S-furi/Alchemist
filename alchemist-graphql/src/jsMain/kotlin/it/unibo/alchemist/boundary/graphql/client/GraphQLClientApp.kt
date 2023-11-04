/*
 * Copyright (C) 2010-2023, Danilo Pianini and contributors
 * listed, for each module, in the respective subproject's build.gradle.kts file.
 *
 * This file is part of Alchemist, and is distributed under the terms of the
 * GNU General Public License, with a linking exception,
 * as described in the file LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.boundary.graphql.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.dom.clear
import org.jetbrains.letsPlot.Figure
import org.jetbrains.letsPlot.asDiscrete
import org.jetbrains.letsPlot.frontend.JsFrontendUtil
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.label.ggtitle
import org.jetbrains.letsPlot.label.xlab
import org.jetbrains.letsPlot.label.ylab
import org.jetbrains.letsPlot.letsPlot
import org.jetbrains.letsPlot.tooltips.layerTooltips

private val graphqlClient: GraphQLClient = GraphQLClientFactory.subscriptionClient()
private val simulationHandler: SimulationHandler = SimulationHandler(graphqlClient)
private const val NODE_ID = 1
private const val PLOT_HEIGHT: Int = 700
private const val PLOT_WIDTH: Int = 350
private const val REFRESH_RATE: Long = 300 // milliseconds

/**
 * Main entrypoint of the JS application.
 */
fun main() {
    window.onload = { setUpEnvironment(MainScope()) }
}

private fun setUpEnvironment(scope: CoroutineScope) {
    setButton("subscribe", ::subscribe, scope)
    setButton("play", simulationHandler::play, scope)
    setButton("pause", simulationHandler::pause, scope)
    setButton("term", simulationHandler::terminate, scope)
    scope.launch { updateSimulationStatus() }
}

private fun setButton(id: String, action: suspend () -> Unit, scope: CoroutineScope) {
    document.getElementById(id)?.addEventListener("click", {
        scope.launch {
            try {
                action()
                updateSimulationStatus()
            } catch (e: IllegalArgumentException) {
                setError(e.message)
            }
        }
    })
}

private fun setError(message: String?) {
    document.getElementById("error")?.textContent = message
}

private suspend fun updateSimulationStatus() {
    document.getElementById("status")?.textContent = simulationHandler.status()
}

private suspend fun subscribe() {
    println("SUBSCRIBING")
    val neighborhoodSubscription = graphqlClient.subscription(NeighborNodesSubscription(NODE_ID))
    disableSubscriptionButton()
    neighborhoodSubscription.toFlow()
        .onEach {
            addNeigborsToPlot(it.data?.neighborhood?.getNeighbors!!)
            delay(REFRESH_RATE)
        }.collect()
}

private suspend fun addNeigborsToPlot(neighbors: List<NeighborNodesSubscription.GetNeighbor>) {
    val nodes = getNodesAsDodgeballNode(neighbors)
    val data = mapOf(
        "id" to nodes.map { it.id },
        "x" to nodes.map { it.position.x },
        "y" to nodes.map { it.position.y },
        "concentration" to nodes.map { it.concentration },
        "molecule" to nodes.map { it.molecule },
        "nodeType" to nodes.map { it.type },
    )
    addPlotToDiv(addNeighborsToPlot(data))
}

private fun addNeighborsToPlot(data: Map<String, List<Any>>): Figure {
    return letsPlot(data) {
        x = "x"
        y = "y"
        color = asDiscrete("id")
        shape = "nodeType"
    } +
        geomPoint(
            size = 6.0,
            tooltips = layerTooltips("molecule", "concentration"),
        ) +
        xlab("X position") +
        ylab("Y position") +
        ggtitle("Position in space of Surrogates for node $NODE_ID") +
        ggsize(PLOT_HEIGHT, PLOT_WIDTH)
}

private suspend fun getNodesAsDodgeballNode(
    neighbors: List<NeighborNodesSubscription.GetNeighbor>,
): List<DodgeballNode> {
    val positions = neighbors.associate { it.id to nodePosition(it.id) } + mapOf(NODE_ID to nodePosition(NODE_ID))
    val center = getNodeInfo(NODE_ID)

    return neighbors.map {
        DodgeballNode(
            id = it.id,
            position = positions[it.id]!!,
            molecule = it.contents.entries[0].molecule.name,
            concentration = it.contents.entries[0].concentration,
            type = "Neighbor",
        )
    } + DodgeballNode(
        id = center.id,
        position = positions[center.id]!!,
        molecule = center.contents.entries[0].molecule.name,
        concentration = center.contents.entries[0].concentration,
        type = "Center",
    )
}

private fun disableSubscriptionButton() {
    document.getElementById("subscribe")?.setAttribute("disabled", "true")
}

private suspend fun getNodeInfo(nodeId: Int) =
    graphqlClient.query(NodeQuery(nodeId)).execute().data?.environment?.nodeById!!

private suspend fun nodePosition(nodeId: Int): NodePositionQuery.OnPosition2DSurrogate =
    graphqlClient.query(NodePositionQuery(nodeId))
        .execute().dataOrThrow().nodePosition?.onPosition2DSurrogate!!

private fun addPlotToDiv(p: Figure) {
    document.getElementById("plot")?.apply {
        clear()
        appendChild(JsFrontendUtil.createPlotDiv(p))
    }
}

private data class DodgeballNode(
    val id: Int,
    val position: NodePositionQuery.OnPosition2DSurrogate,
    val molecule: String,
    val concentration: String,
    val type: String,
)
