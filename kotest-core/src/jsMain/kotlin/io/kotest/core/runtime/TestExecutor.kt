package io.kotest.core.runtime

import io.kotest.core.extensions.TestCaseExtension
import io.kotest.core.test.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.promise
import kotlin.coroutines.CoroutineContext
import kotlin.js.Promise
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@UseExperimental(ExperimentalTime::class)
class TestExecutor {

   /**
    * Executes a [TestCase] and returns a Javascript [Promise] which
    * can be used by the underlying test framework for async support.
    */
   fun execute(testCase: TestCase) {
      require(testCase.type == TestType.Test) { "Spec styles that support nested tests are disabled in kotest-js because the underlying JS frameworks do not support promises for outer test scopes. Please use FunSpec or StringSpec which ensure that only top level tests are used." }
      it(testCase.name) { done -> executeWithCallback(testCase, done) }
   }

   private fun executeWithCallback(testCase: TestCase, done: dynamic) {
      GlobalScope.promise {
         val context = object : TestContext() {
            override val testCase: TestCase = testCase
            override val coroutineContext: CoroutineContext = this@promise.coroutineContext
            override suspend fun registerTestCase(nested: NestedTest) {
               throw IllegalStateException("Spec styles that support nested tests are disabled in kotest-js because the underlying JS frameworks do not support promises for outer test scopes. Please use FunSpec or StringSpec which ensure that only top level tests are used.")
            }
         }
         runExtensions(testCase, context, testCase.extensions()) {
            done(it.error)
            Unit
         }
      }
   }

   /**
    * Recursively runs the given [TestCaseExtension]s until no extensions are left.
    * Each extension must invoke the callback given to it, or the test will hang.
    */
   private suspend fun runExtensions(
      testCase: TestCase,
      context: TestContext,
      extensions: List<TestCaseExtension>,
      onComplete: suspend (TestResult) -> Unit
   ) {
      when {
         extensions.isEmpty() -> {
            when (testCase.isActive()) {
               true -> {
                  val result = executeActiveTest(testCase, context)
                  onComplete(result)
               }
               false -> TestResult.Ignored
            }
         }
         else -> {
            extensions.first().intercept(
               testCase,
               { test, callback -> runExtensions(test, context, extensions.drop(1), callback) },
               { onComplete(it) }
            )
         }
      }
   }

   private suspend fun executeActiveTest(testCase: TestCase, context: TestContext): TestResult {
      testCase.invokeBeforeTest()
      val timeout = testCase.config.resolvedTimeout()
      val error = try {
         testCase.executeWithTimeout(context, timeout)
         null
      } catch (e: TimeoutCancellationException) {
         RuntimeException("Execution of test took longer than ${timeout}ms")
      } catch (e: Throwable) {
         e
      } catch (e: AssertionError) {
         e
      }
      val result = TestResult.throwable(error, Duration.ZERO)
      testCase.invokeAfterTest(result)
      return result
   }
}



