package org.jetbrains.research.testspark.tools.llm.generation

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.research.testspark.core.generation.llm.network.LLMResponse
import org.jetbrains.research.testspark.core.generation.llm.network.ResponseErrorCode
import org.jetbrains.research.testspark.core.progress.CustomProgressIndicator
import org.jetbrains.research.testspark.core.test.TestsAssembler
import org.jetbrains.research.testspark.tools.llm.generation.openai.ChatMessage


abstract class RequestManager(var token: String) {
    enum class SendResult {
        OK,
        PROMPT_TOO_LONG,
        OTHER,
    }

    val chatHistory = mutableListOf<ChatMessage>()

    protected val log: Logger = Logger.getInstance(this.javaClass)

    /**
     * Sends a request to LLM with the given prompt and returns the generated TestSuite.
     *
     * @param prompt the prompt to send to LLM
     * @param indicator the progress indicator to show progress during the request
     * @param packageName the name of the package for the generated TestSuite
     * @param isUserFeedback indicates if this request is a test generation request or a user feedback
     * @return the generated TestSuite, or null and prompt message
     */
    open fun request(
        prompt: String,
        indicator: CustomProgressIndicator,
        packageName: String,
        isUserFeedback: Boolean = false,
    ): LLMResponse {
        // save the prompt in chat history
        chatHistory.add(ChatMessage("user", prompt))

        // Send Request to LLM
        log.info("Sending Request...")

        val (sendResult, testsAssembler) = send(prompt, indicator)

        if (sendResult == SendResult.PROMPT_TOO_LONG) {
            return LLMResponse(ResponseErrorCode.PROMPT_TOO_LONG, null)
        }

        // we remove the user request because we don't store user's requests in chat history
        if (isUserFeedback) {
            chatHistory.removeLast()
        }

        return when (isUserFeedback) {
            true -> processUserFeedbackResponse(testsAssembler, packageName)
            false -> processResponse(testsAssembler, packageName)
        }
    }

    open fun processResponse(
        testsAssembler: TestsAssembler,
        packageName: String,
    ): LLMResponse {
        // save the full response in the chat history
        val response = testsAssembler.getContent()

        log.info("The full response: \n $response")
        chatHistory.add(ChatMessage("assistant", response))

        // check if response is empty
        if (response.isEmpty() || response.isBlank()) {
            return LLMResponse(ResponseErrorCode.EMPTY_LLM_RESPONSE, null)
        }

        val testSuiteGeneratedByLLM = testsAssembler.assembleTestSuite(packageName)

        return if (testSuiteGeneratedByLLM == null) {
            LLMResponse(ResponseErrorCode.TEST_SUITE_PARSING_FAILURE, null)
        }
        else {
            LLMResponse(ResponseErrorCode.OK, testSuiteGeneratedByLLM.reformat())
        }
    }


    abstract fun send(
        prompt: String,
        indicator: CustomProgressIndicator,
    ): Pair<SendResult, TestsAssembler>


    open fun processUserFeedbackResponse(
        testsAssembler: TestsAssembler,
        packageName: String,
    ): LLMResponse {
        val response = testsAssembler.getContent()

        log.info("The full response: \n $response")

        // check if response is empty
        if (response.isEmpty() || response.isBlank()) {
            return LLMResponse(ResponseErrorCode.EMPTY_LLM_RESPONSE, null)
        }

        val testSuiteGeneratedByLLM = testsAssembler.assembleTestSuite(packageName)

        return if (testSuiteGeneratedByLLM == null) {
            LLMResponse(ResponseErrorCode.TEST_SUITE_PARSING_FAILURE, null)
        }
        else {
            LLMResponse(ResponseErrorCode.OK, testSuiteGeneratedByLLM)
        }
    }
}
