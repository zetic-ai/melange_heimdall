package com.example.migration

import android.content.Context
import com.zeticai.melangelm.MelangeLmProxy
import com.zeticai.melangelm.model.ChatChoice
import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ChatRequest
import com.zeticai.melangelm.model.ChatResponse
import com.zeticai.melangelm.model.ProxyResult
import com.zeticai.melangelm.model.TokenUsage
import com.zeticai.melangelm.upstream.UpstreamClient

/**
 * Example: Migrate an existing Retrofit/OkHttp app to use Melange Proxy
 *
 * If you already have an app that calls OpenAI (or any LLM API) using
 * Retrofit, OkHttp, or a custom HTTP client, you can wrap it with the
 * proxy in minutes. Your existing auth, retry logic, and error handling
 * stay unchanged.
 *
 * BEFORE (direct API call):
 *   App → Retrofit → OpenAI API → App
 *
 * AFTER (with proxy):
 *   App → MelangeProxy → [Guard → Anonymize → Compress] → Retrofit → OpenAI API
 *       ← MelangeProxy ← [De-anonymize] ← Retrofit ← OpenAI API
 *
 * Migration steps:
 *   1. Implement UpstreamClient with your existing HTTP logic
 *   2. Call MelangeLmProxy.wrap() with your client
 *   3. Done — all your existing code works unchanged
 */

// --- Step 1: Wrap your existing HTTP client ---
// Adapt your Retrofit service (or any HTTP client) to the UpstreamClient interface.

// Imagine you have this existing Retrofit service:
//
// interface MyOpenAIService {
//     @POST("v1/chat/completions")
//     suspend fun chatCompletions(@Body request: MyChatRequest): MyChatResponse
// }

class MyRetrofitUpstream(
    // Your existing Retrofit instance, already configured with
    // base URL, auth interceptors, retry logic, etc.
    // private val api: MyOpenAIService
) : UpstreamClient {

    override suspend fun send(request: ChatRequest): ChatResponse {
        // Convert proxy's ChatRequest to your existing request model
        // and call your existing API

        // In real code, this would be:
        // val myRequest = MyChatRequest(
        //     model = request.model,
        //     messages = request.messages.map { ... },
        //     temperature = request.temperature
        // )
        // val myResponse = api.chatCompletions(myRequest)
        // return myResponse.toChatResponse()

        // Placeholder for compilation:
        throw NotImplementedError("Replace with your Retrofit call")
    }
}

// --- Step 2: Wrap it with the proxy ---

fun migrateToProxy(context: Context, zeticKey: String): MelangeLmProxy {
    // Your existing Retrofit client:
    // val retrofit = Retrofit.Builder()
    //     .baseUrl("https://api.openai.com/")
    //     .addConverterFactory(GsonConverterFactory.create())
    //     .client(myOkHttpClient)  // your existing auth, retry, logging
    //     .build()
    // val api = retrofit.create(MyOpenAIService::class.java)

    val myClient = MyRetrofitUpstream(/* api */)

    // Wrap your client with on-device protection
    return MelangeLmProxy.wrap(
        context = context,
        zeticKey = zeticKey,
        client = myClient
    )
    // That's it! The proxy adds PromptGuard + Anonymizer + Summarizer
    // in front of your existing HTTP client. Your auth interceptors,
    // retry logic, and error handling all still work.
}

// --- Step 3 (optional): Customize which stages to use ---

fun migrateWithCustomStages(context: Context, zeticKey: String): MelangeLmProxy {
    val myClient = MyRetrofitUpstream()

    return MelangeLmProxy.wrap(
        context = context,
        zeticKey = zeticKey,
        client = myClient,
        block = {
            // Only PII redaction, no prompt guard or summarizer
            anonymizer { personalKey = zeticKey; restoreInResponse = true }
        }
    )
}

// --- What changes in your app code: almost nothing ---
//
// BEFORE:
//   val response = api.chatCompletions(request)
//   showReply(response.choices.first().message.content)
//
// AFTER:
//   val result = proxy.chat(messages = messages)
//   when (result) {
//       is ProxyResult.Success -> showReply(result.response.choices.first().message.content)
//       is ProxyResult.Blocked -> showWarning("Message flagged")
//       is ProxyResult.Error   -> showError(result.message)
//   }
//
// The only change is switching from a direct API response to a ProxyResult
// that can also tell you when a message was blocked for safety.
