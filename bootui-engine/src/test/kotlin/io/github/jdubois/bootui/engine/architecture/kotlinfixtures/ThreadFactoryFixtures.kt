package io.github.jdubois.bootui.engine.architecture.kotlinfixtures

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.function.Function

class KotlinScheduledThreadFactory {
    private val retries = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "replica-bridge-subscribe").apply { isDaemon = true }
    }
}

class KotlinCapturedThreadFactory {
    fun factory(name: String): ThreadFactory =
        ThreadFactory { runnable -> Thread(runnable, name).apply { isDaemon = true } }
}

class KotlinNonFactoryThreadLambda {
    fun function(): Function<Runnable, Thread> =
        Function { runnable -> Thread(runnable) }
}
