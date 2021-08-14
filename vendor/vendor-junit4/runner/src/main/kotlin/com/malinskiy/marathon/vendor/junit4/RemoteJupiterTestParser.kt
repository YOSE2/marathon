package com.malinskiy.marathon.vendor.junit4

import com.malinskiy.marathon.execution.Configuration
import com.malinskiy.marathon.execution.TestParser
import com.malinskiy.marathon.log.MarathonLogging
import com.malinskiy.marathon.test.Test
import com.malinskiy.marathon.vendor.junit4.booter.Booter
import com.malinskiy.marathon.vendor.junit4.booter.Mode
import com.malinskiy.marathon.vendor.junit4.configuration.Junit4Configuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.system.measureTimeMillis

class RemoteJupiterTestParser(private val testBundleIdentifier: Junit4TestBundleIdentifier) : TestParser {
    private val logger = MarathonLogging.logger {}

    private val controlPort = 49000

    override suspend fun extract(configuration: Configuration): List<Test> {
        val discoveredTests = mutableListOf<Test>()

        measureTimeMillis {
            val conf = configuration.vendorConfiguration as Junit4Configuration
            var counter = 1
            val job = SupervisorJob()

            val workerPool = ConcurrentLinkedQueue<Booter>()
            for (i in 1..conf.parallelism) {
                workerPool.add(Booter(conf, controlPort, Mode.DISCOVER).apply { prepare() })
            }

            conf.testBundlesCompat()
                .asSequence()
                .forEach { bundle ->
                    var worker: Booter? = null
                    while (worker == null) {
                        worker = workerPool.poll()
                        if (worker != null) break
                        delay(10)
                    }
                    val booter = worker!!

                    GlobalScope.launch(context = Dispatchers.IO + job) {
                        logger.info { "Parsing ${bundle.id} ${counter++}/${conf.testBundlesCompat().size}" }
                        val bundleTests = booter.testDiscoveryClient!!.execute(
                            conf.testPackageRoot ?: "",
                            bundle.applicationClasspath ?: emptyList(),
                            bundle.testClasspath ?: emptyList()
                        )

                        bundleTests.forEach {
                            testBundleIdentifier.put(it, bundle)
                        }
                        discoveredTests.addAll(bundleTests)

                        workerPool.offer(booter)
                    }
                }

            job.complete()
            job.join()

            workerPool.forEach { it.dispose() }
        }.let {
            logger.debug { "Parsing finished in ${it}ms" }
        }

        return discoveredTests.toList()
    }
}
