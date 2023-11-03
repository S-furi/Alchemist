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

private val graphqlClient: GraphQLClient = GraphQLClientFactory.subscriptionClient()
private val simulationHandler: SimulationHandler = SimulationHandler(graphqlClient)
private const val NODE_ID = 1
private const val PLOT_HEIGHT: Int = 700
private const val PLOT_WIDTH: Int = 350

/**
 * Main entrypoint of the JS application.
 */
fun main() {
    window.onload = { setUpEnvironmnet(MainScope()) }
}

private fun setUpEnvironmnet(scope: CoroutineScope) {
    setButton("subscribe", ::subscribe, scope)
    setButton("play", simulationHandler::play, scope)
    setButton("pause", simulationHandler::pause, scope)
    setButton("term", simulationHandler::terminate, scope)
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
    val nodeSubscription = graphqlClient.subscription(NodeSubscription(NODE_ID))
    disableSubscriptionButton()
    nodeSubscription.toFlow()
        .onEach {
            neighborhood(NODE_ID)
                .associate { it.id to nodePosition(it.id) }
                .apply { addPositionToPlot(this + mapOf(NODE_ID to nodePosition(NODE_ID)), NODE_ID) }
        }.collect()
}

private fun disableSubscriptionButton() {
    document.getElementById("subscribe")?.setAttribute("disabled", "true")
}

private suspend fun neighborhood(nodeId: Int) =
    graphqlClient.query(NeighborhoodQuery(nodeId)).execute()
        .data?.neighborhood?.getNeighbors!!

private suspend fun nodePosition(nodeId: Int): NodePositionQuery.OnPosition2DSurrogate =
    graphqlClient.query(NodePositionQuery(nodeId))
        .execute().dataOrThrow().nodePosition?.onPosition2DSurrogate!!

private fun addPlotToDiv(p: Figure) {
    document.getElementById("plot")?.apply {
        clear()
        appendChild(JsFrontendUtil.createPlotDiv(p))
    }
}

private fun addPositionToPlot(data: Map<Int, NodePositionQuery.OnPosition2DSurrogate>, center: Int) {
    addPlotToDiv(
        getPlot(
            mapOf(
                "id" to data.keys,
                "x" to data.values.map { it.x },
                "y" to data.values.map { it.y },
                "node" to data.keys.map { if (it == center) "Center" else "Neighbor" },
            ),
        ),
    )
}

private fun getPlot(data: Map<String, Any>) =
    letsPlot(data) {
        x = "x"
        y = "y"
        color = asDiscrete("id")
        shape = "node"
    } +
        geomPoint(size = 6.0) +
        xlab("X position") +
        ylab("Y position") +
        ggtitle("Position in space of Surrogates for node $NODE_ID") +
        ggsize(PLOT_HEIGHT, PLOT_WIDTH)
