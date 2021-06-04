package com.malinskiy.marathon.vendor.junit4.client

import com.malinskiy.marathon.vendor.junit4.contract.EventType
import com.malinskiy.marathon.vendor.junit4.contract.TestEvent
import com.malinskiy.marathon.vendor.junit4.contract.TestExecutorGrpcKt
import com.malinskiy.marathon.vendor.junit4.contract.TestRequest
import com.malinskiy.marathon.vendor.junit4.executor.listener.JUnit4TestRunListener
import com.malinskiy.marathon.vendor.junit4.model.TestIdentifier
import io.grpc.ManagedChannel
import io.grpc.StatusException
import kotlinx.coroutines.flow.collect
import java.io.Closeable
import java.util.concurrent.TimeUnit

class TestExecutorClient(
    private val channel: ManagedChannel
) : Closeable {
    private val stub: TestExecutorGrpcKt.TestExecutorCoroutineStub =
        TestExecutorGrpcKt.TestExecutorCoroutineStub(channel).withWaitForReady()

    suspend fun execute(tests: List<String>, listener: JUnit4TestRunListener) {
        val request = TestRequest.newBuilder()
            .addAllFqtn(tests)
            .build()

        val responseFlow = stub.execute(request)
        try {
            responseFlow.collect { event: TestEvent ->
                when (event.eventType) {
                    EventType.RUN_STARTED -> {
                        listener.testRunStarted("Marathon JUnit4 Test Run", event.testCount)
                    }
                    EventType.RUN_FINISHED -> {
                        listener.testRunEnded(event.totalDurationMillis, emptyMap())
                    }
                    EventType.TEST_STARTED -> {
                        listener.testStarted(TestIdentifier(event.classname, event.method))
                    }
                    EventType.TEST_FINISHED -> {
                        listener.testEnded(TestIdentifier(event.classname, event.method), emptyMap())
                    }
                    EventType.TEST_FAILURE -> {
                        listener.testFailed(TestIdentifier(event.classname, event.method), event.stacktrace)
                    }
                    EventType.TEST_ASSUMPTION_FAILURE -> {
                        listener.testAssumptionFailure(TestIdentifier(event.classname, event.method), event.stacktrace)
                    }
                    EventType.TEST_IGNORED -> {
                        listener.testIgnored(TestIdentifier(event.classname, event.method))
                    }
                    EventType.UNRECOGNIZED -> Unit
                }
            }
        } catch (e: StatusException) {
            //gRPC connection closed
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun close() {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
    }
}