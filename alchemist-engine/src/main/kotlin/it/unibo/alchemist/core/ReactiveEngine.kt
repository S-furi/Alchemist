/*
 * Copyright (C) 2010-2026, Danilo Pianini and contributors
 * listed, for each module, in the respective subproject's build.gradle.kts file.
 *
 * This file is part of Alchemist, and is distributed under the terms of the
 * GNU General Public License, with a linking exception,
 * as described in the file LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.core

import it.unibo.alchemist.boundary.OutputMonitor
import it.unibo.alchemist.model.Actionable
import it.unibo.alchemist.model.Environment
import it.unibo.alchemist.model.Neighborhood
import it.unibo.alchemist.model.Node
import it.unibo.alchemist.model.Position
import it.unibo.alchemist.model.Time
import java.util.Optional
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.function.BooleanSupplier
import org.jooq.lambda.fi.lang.CheckedRunnable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A reactive implementation of the [Simulation] engine.
 * This engine does not use a dependency graph. Instead, it relies on [Actionable]s
 * (Reactions) being reactive: they observe the [Environment] and [Node]s, and
 * emit reschedule requests when their state or dependencies change.
 *
 * @param T the concentration type
 * @param P the position type, extending [Position]
 * @property environment the simulation environment
 * @property scheduler the scheduler managing event execution
 */
open class ReactiveEngine<T, P : Position<out P>>(
    private val environment: Environment<T, P>,
    protected val scheduler: Scheduler<T>,
) : Simulation<T, P> {

    private val statusLock: Lock = ReentrantLock()

    @Volatile private var status: Status = Status.INIT

    private var error: Optional<Throwable> = Optional.empty()

    protected var currentTime: Time = Time.ZERO
        @Synchronized get

        @Synchronized set

    protected var currentStep: Long = 0
        @Synchronized get

        @Synchronized set

    private var simulationThread: Thread? = null

    protected val statusLocks: Map<Status, SynchBox> = Status.entries.associateWith { SynchBox() }

    protected val commands: BlockingQueue<CheckedRunnable> = LinkedBlockingQueue()

    protected val monitors: MutableList<OutputMonitor<T, P>> = CopyOnWriteArrayList()

    constructor(environment: Environment<T, P>) : this(environment, ArrayIndexedPriorityQueue())

    init {
        LOGGER.trace("ReactiveEngine created")
        environment.simulation = this
    }

    override fun addOutputMonitor(op: OutputMonitor<T, P>) {
        monitors.add(op)
    }

    private fun checkCaller() {
        check(Thread.currentThread() == simulationThread) {
            "This method must be called from the simulation thread."
        }
    }

    private fun <R> doOnStatus(action: () -> R): R = statusLock.run {
        lock()
        try {
            action()
        } finally {
            unlock()
        }
    }

    @Suppress("DuplicatedCode")
    protected open fun doStep() {
        val nextEvent = scheduler.getNext() ?: run {
            newStatus(Status.TERMINATED)
            LOGGER.info("No more reactions.")
            return
        }
        val scheduledTime = nextEvent.tau
        check(scheduledTime >= time) {
            "$nextEvent is scheduled in the past at time $scheduledTime. Current time: $time; current step: $step."
        }
        currentTime = scheduledTime
        if (scheduledTime.isFinite && nextEvent.canExecute()) {
            nextEvent.conditions.forEach { it.reactionReady() }
            nextEvent.execute()
        }
        nextEvent.update(time, true, environment)
        scheduler.updateReaction(nextEvent)

        monitors.forEach { it.stepDone(environment, nextEvent, time, step) }
        if (environment.isTerminated) {
            newStatus(Status.TERMINATED)
            LOGGER.info("Termination condition reached.")
        }
        currentStep = step + 1
    }

    override fun getEnvironment(): Environment<T, P> = environment

    override fun getError(): Optional<Throwable> = error

    protected fun setError(error: Optional<Throwable>) {
        this.error = error
    }

    override fun getStatus(): Status = status

    protected fun setStatus(status: Status) {
        this.status = status
    }

    @Synchronized override fun getStep(): Long = currentStep

    @Synchronized override fun getTime(): Time = currentTime

    override fun goToStep(step: Long): CompletableFuture<Unit> = pauseWhen { getStep() >= step }

    override fun goToTime(t: Time): CompletableFuture<Unit> = pauseWhen { time >= t }

    protected open fun newStatus(next: Status): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        schedule {
            doOnStatus {
                if (next.isReachableFrom(status)) {
                    status = next
                    lockForStatus(next).releaseAll()
                }
                future.complete(null)
            }
        }
        return future
    }

    /* No dependency graph to update */
    override fun neighborAdded(node: Node<T>, n: Node<T>) { }

    override fun neighborRemoved(node: Node<T>, n: Node<T>) { }

    override fun nodeMoved(node: Node<T>) { }

    override fun nodeAdded(node: Node<T>) {
        schedule {
            node.reactions.forEach { scheduleReaction(it) }
        }
    }

    override fun nodeRemoved(node: Node<T>, oldNeighborhood: Neighborhood<T>) {
        schedule {
            node.reactions.forEach { removeReaction(it) }
        }
    }

    override fun pause(): CompletableFuture<Unit> = newStatus(Status.PAUSED)

    override fun play(): CompletableFuture<Unit> = newStatus(Status.RUNNING)

    override fun reactionAdded(reactionToAdd: Actionable<T>) {
        schedule { scheduleReaction(reactionToAdd) }
    }

    override fun reactionRemoved(reactionToRemove: Actionable<T>) {
        schedule { removeReaction(reactionToRemove) }
    }

    override fun removeOutputMonitor(op: OutputMonitor<T, P>) {
        monitors.remove(op)
    }

    protected fun processCommandsWhileIn(status: Status) {
        while (this.status == status) {
            commands.take().run()
        }
    }

    @Suppress("TooGenericExceptionCaught", "DuplicatedCode")
    override fun run() {
        synchronized(environment) {
            try {
                LOGGER.info("Starting ReactiveEngine {} with scheduler {}", javaClass, scheduler.javaClass)
                simulationThread = Thread.currentThread()
                environment.globalReactions.forEach(::scheduleReaction)
                environment.forEach { it.reactions.forEach(::scheduleReaction) }
                status = Status.READY
                LOGGER.trace("Thread {} started running.", Thread.currentThread().id)
                monitors.forEach { it.initialized(environment) }
                processCommandsWhileIn(Status.READY)
                while (status != Status.TERMINATED && time < Time.INFINITY) {
                    while (commands.isNotEmpty()) {
                        commands.poll().run()
                    }
                    if (status == Status.RUNNING) {
                        doStep()
                    }
                    processCommandsWhileIn(Status.PAUSED)
                }
            } catch (e: Throwable) {
                error = Optional.of(e)
                LOGGER.error("The simulation engine crashed.", e)
            } finally {
                status = Status.TERMINATED
                commands.clear()
                try {
                    monitors.forEach { it.finished(environment, time, step) }
                } catch (e: Throwable) {
                    error.ifPresentOrElse({ it.addSuppressed(e) }, { error = Optional.of(e) })
                }
                afterRun()
            }
        }
    }

    protected fun afterRun() = Unit

    @Suppress("DuplicatedCode")
    private fun pauseWhen(condition: BooleanSupplier): CompletableFuture<Unit> {
        val future = CompletableFuture<Unit>()
        val monitor = object : OutputMonitor<T, P> {
            @Volatile
            private var hasTriggered = false

            override fun initialized(initializedEnvironment: Environment<T, P>) {
                checkConditionAndPause()
            }
            override fun stepDone(
                targetEnvironment: Environment<T, P>,
                reaction: Actionable<T>?,
                time: Time,
                step: Long,
            ) {
                checkConditionAndPause()
            }

            private fun checkConditionAndPause() {
                if (!hasTriggered && condition.asBoolean) {
                    hasTriggered = true
                    monitors.remove(this)
                    pause().thenRun { future.complete(null) }
                }
            }
        }
        addOutputMonitor(monitor)
        return future
    }

    override fun schedule(runnable: CheckedRunnable) {
        check(status != Status.TERMINATED) { "This simulation is terminated and cannot be resumed." }
        commands.add(runnable)
    }

    private fun scheduleReaction(reaction: Actionable<T>) {
        reaction.initializationComplete(time, environment)
        scheduler.addReaction(reaction)

        reaction.rescheduleRequest.onChange(this) {
            updateReaction(reaction)
        }
    }

    protected fun updateReaction(reaction: Actionable<T>) {
        val previousTau = reaction.tau
        reaction.update(time, false, environment)
        if (reaction.tau != previousTau) {
            scheduler.updateReaction(reaction)
        }
    }

    private fun removeReaction(reaction: Actionable<T>) {
        reaction.rescheduleRequest.stopWatching(this)
        scheduler.removeReaction(reaction)
    }

    private fun lockForStatus(futureStatus: Status): SynchBox = checkNotNull(statusLocks[futureStatus]) {
        "Inconsistent state: the Alchemist engine tried to synchronize on a non-existing lock. " +
            "Searching for status: $futureStatus, available locks: $statusLocks"
    }

    override fun waitFor(next: Status, timeout: Long, tu: TimeUnit): Status =
        lockForStatus(next).waitFor(next, timeout, tu)

    override fun terminate(): CompletableFuture<Unit> = newStatus(Status.TERMINATED)

    override fun getOutputMonitors(): List<OutputMonitor<T, P>> = java.util.Collections.unmodifiableList(monitors)

    override fun toString(): String = "${javaClass.simpleName} t: $time, s: $step"

    @Suppress("DuplicatedCode")
    protected inner class SynchBox {
        private val queueLength = AtomicInteger()
        private val statusReached: Condition = statusLock.newCondition()
        private val allReleased: Condition = statusLock.newCondition()

        fun waitFor(next: Status, timeout: Long, tu: TimeUnit): Status = doOnStatus {
            var notTimedOut = true
            while (notTimedOut && next != status && next.isReachableFrom(status)) {
                try {
                    queueLength.incrementAndGet()
                    notTimedOut = statusReached.await(timeout, tu)
                    queueLength.decrementAndGet()
                } catch (e: InterruptedException) {
                    LOGGER.info("Spurious wakeup?", e)
                }
            }
            if (queueLength.get() == 0) allReleased.signal()
            status
        }

        fun releaseAll() {
            doOnStatus {
                while (queueLength.get() != 0) {
                    statusReached.signalAll()
                    allReleased.awaitUninterruptibly()
                }
            }
        }
    }

    protected companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(ReactiveEngine::class.java)
    }
}
