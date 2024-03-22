package org.jetbrains.research.testspark.core.generation.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.research.testspark.core.data.Report
import org.jetbrains.research.testspark.core.data.TestCase
import org.jetbrains.research.testspark.core.generation.llm.network.LLMResponse
import org.jetbrains.research.testspark.core.generation.llm.network.RequestManager
import org.jetbrains.research.testspark.core.generation.llm.network.ResponseErrorCode
import org.jetbrains.research.testspark.core.generation.llm.prompt.PromptSizeReductionStrategy
import org.jetbrains.research.testspark.core.progress.CustomProgressIndicator
import org.jetbrains.research.testspark.core.test.TestCompiler
import org.jetbrains.research.testspark.core.test.TestsPersistentStorage
import org.jetbrains.research.testspark.core.test.TestsAssembler
import org.jetbrains.research.testspark.core.test.TestsPresenter
import org.jetbrains.research.testspark.core.test.data.TestCaseGeneratedByLLM
import org.jetbrains.research.testspark.core.test.data.TestSuiteGeneratedByLLM
import java.io.File

enum class FeedbackCycleExecutionResult {
    OK,
    NO_COMPILABLE_TEST_CASES_GENERATED,
    CANCELED,
    PROVIDED_PROMPT_TOO_LONG,
    SAVING_TEST_FILES_ISSUE,
}

data class FeedbackResponse(
    val executionResult: FeedbackCycleExecutionResult,
    val generatedTestSuite: TestSuiteGeneratedByLLM?,
    val compilableTestCases: MutableSet<TestCaseGeneratedByLLM>,
) {
    init {
        if (executionResult == FeedbackCycleExecutionResult.OK && generatedTestSuite == null) {
            throw IllegalArgumentException("Test suite must be provided when FeedbackCycleExecutionResult is OK, got null")
        }
        else if (executionResult != FeedbackCycleExecutionResult.OK && generatedTestSuite != null) {
            throw IllegalArgumentException(
                "Test suite must not be provided when FeedbackCycleExecutionResult is not OK, got $generatedTestSuite")
        }
    }
}

class LLMWithFeedback(
    private val report: Report,
    private val initialPromptMessage: String,
    private val promptSizeReductionStrategy: PromptSizeReductionStrategy,
    private val testSuiteFilename: String, // TODO: write a comment of what this is
    private val packageName: String,
    private val resultPath: String, // TODO: write a comment of what this is
    private val buildPath: String, // TODO: write a comment of what this is
    private val requestManager: RequestManager,
    private val testsAssembler: TestsAssembler,
    private val testCompiler: TestCompiler,
    private val testStorage: TestsPersistentStorage,
    private val testsPresenter: TestsPresenter,
    private val indicator: CustomProgressIndicator,
    private val requestsCountThreshold: Int,
) {
    enum class WarningType {
        TEST_SUITE_PARSING_FAILED,
        NO_TEST_CASES_GENERATED,
        COMPILATION_ERROR_OCCURRED,
    }

    private val log = KotlinLogging.logger { this::class.java }

    fun run(onWarningCallback: ((WarningType) -> Unit)? = null): FeedbackResponse {
        var requestsCount = 0
        var generatedTestsArePassing = false
        var nextPromptMessage = initialPromptMessage

        var executionResult = FeedbackCycleExecutionResult.OK
        val compilableTestCases: MutableSet<TestCaseGeneratedByLLM> = mutableSetOf()

        var generatedTestSuite: TestSuiteGeneratedByLLM? = null

        while (!generatedTestsArePassing) {
            requestsCount++

            log.info { "Iteration #$requestsCount of feedback cycle" }

            // Process stopped checking
            if (indicator.isCanceled()) {
                executionResult = FeedbackCycleExecutionResult.CANCELED
                break
            }

            if (isLastIteration(requestsCount) && compilableTestCases.isEmpty()) {
                executionResult = FeedbackCycleExecutionResult.NO_COMPILABLE_TEST_CASES_GENERATED
                break
            }

            // clearing test assembler's collected text on the previous attempts
            testsAssembler.clear()
            val response: LLMResponse = requestManager.request(
                        prompt = nextPromptMessage,
                        indicator = indicator,
                        packageName = packageName,
                        testsAssembler = testsAssembler,
                        isUserFeedback = false,
                    )

            when (response.errorCode) {
                ResponseErrorCode.OK -> {
                    log.info{ "Test suite generated successfully: ${response.testSuite!!}" }
                }
                ResponseErrorCode.PROMPT_TOO_LONG -> {
                    if (promptSizeReductionStrategy.isReductionPossible()) {
                        nextPromptMessage = promptSizeReductionStrategy.reduceSizeAndGeneratePrompt()
                        /**
                         * Current attempt does not count as a failure since it was rejected due to the prompt size exceeding the threshold
                         */
                        requestsCount--
                        continue
                    }
                    else {
                        executionResult = FeedbackCycleExecutionResult.PROVIDED_PROMPT_TOO_LONG
                        break
                    }
                }
                ResponseErrorCode.EMPTY_LLM_RESPONSE -> {
                    nextPromptMessage =
                            // TODO: "... same constraints?"
                            "You have provided an empty answer! Please, answer my previous question with the same formats"
                    continue
                }
                ResponseErrorCode.TEST_SUITE_PARSING_FAILURE -> {
                    onWarningCallback?.invoke(WarningType.TEST_SUITE_PARSING_FAILED)
                    log.info { "Cannot parse a test suite from the LLM response. LLM response: '$response'" }
                    // llmErrorManager.warningProcess(TestSparkBundle.message("emptyResponse") + "LLM response: $response", project)
                    nextPromptMessage = "The provided code is not parsable. Please, generate the correct code"
                    continue
                }
            }

            generatedTestSuite = response.testSuite!!

            // Empty response checking
            if (generatedTestSuite.testCases.isEmpty()) {
                onWarningCallback?.invoke(WarningType.NO_TEST_CASES_GENERATED)
                // warningMessage = TestSparkBundle.message("emptyResponse")
                nextPromptMessage =
                    "You have provided an empty answer! Please answer my previous question with the same formats."
                continue
            }

            // Process stopped checking
            if (indicator.isCanceled()) {
                executionResult = FeedbackCycleExecutionResult.CANCELED
                break
            }

            // Save the generated TestSuite into a temp file
            val generatedTestCasesPaths: MutableList<String> = mutableListOf()

            if (isLastIteration(requestsCount)) {
                generatedTestSuite.updateTestCases(compilableTestCases.toMutableList())
            }
            else {
                for (testCaseIndex in generatedTestSuite.testCases.indices) {
                    val testCaseFilename = "${getClassWithTestCaseName(generatedTestSuite.testCases[testCaseIndex].name)}.java"

                    val testCaseRepresentation = testsPresenter.representTestCase(generatedTestSuite, testCaseIndex)

                    val saveFilepath = testStorage.saveGeneratedTest(
                        generatedTestSuite.packageString,
                        testCaseRepresentation,
                        resultPath,
                        testCaseFilename,
                    )

                    generatedTestCasesPaths.add(saveFilepath)
                }
            }

            val generatedTestSuitePath: String = testStorage.saveGeneratedTest(
                generatedTestSuite.packageString,
                testsPresenter.representTestSuite(generatedTestSuite),
                resultPath,
                testSuiteFilename,
            )

            // Correct files creating checking
            var allFilesCreated = true
            for (path in generatedTestCasesPaths) {
                allFilesCreated = allFilesCreated && File(path).exists()
            }
            if (!(allFilesCreated && File(generatedTestSuitePath).exists())) {
                // either some test case file or the test suite file was not created
                executionResult = FeedbackCycleExecutionResult.SAVING_TEST_FILES_ISSUE
                break
            }

            // Get test cases
            val testCases: MutableList<TestCaseGeneratedByLLM> =
                    if (!isLastIteration(requestsCount)) {
                        generatedTestSuite.testCases
                    } else {
                        compilableTestCases.toMutableList()
                    }

            // Compile the test file
            indicator.setText("Compilation tests checking")

            val testCasesCompilationResult = testCompiler.compileTestCases(generatedTestCasesPaths, buildPath, testCases)
            val testSuiteCompilationResult = testCompiler.compileCode(File(generatedTestSuitePath).absolutePath, buildPath)

            // saving the compilable test cases
            compilableTestCases.addAll(testCasesCompilationResult.compilableTestCases)

            if (!testCasesCompilationResult.allTestCasesCompilable && !isLastIteration(requestsCount)) {
                log.info { "Non-compilable test suite: \n${testsPresenter.representTestSuite(generatedTestSuite)}" }
                // log.info { "Incorrect result: \n${testSuitePresenter.toString(generatedTestSuite)}" }

                onWarningCallback?.invoke(WarningType.COMPILATION_ERROR_OCCURRED)
                // warningMessage = TestSparkBundle.message("compilationError")

                nextPromptMessage = "I cannot compile the tests that you provided. The error is:\n${testSuiteCompilationResult.second}\n Fix this issue in the provided tests.\nGenerate public classes and public methods. Response only a code with tests between ```, do not provide any other text."
                continue
            }

            log.info { "Result is compilable" }

            generatedTestsArePassing = true

            for (index in testCases.indices) {
                report.testCaseList[index] = TestCase(index, testCases[index].name, testCases[index].toString(), setOf())
            }
        }

        return FeedbackResponse(
            executionResult = executionResult,
            generatedTestSuite = generatedTestSuite,
            compilableTestCases = compilableTestCases,
        )
    }

    private fun isLastIteration(requestsCount: Int): Boolean = requestsCount > requestsCountThreshold
}
