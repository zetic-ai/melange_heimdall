package com.example.support

import android.content.Context
import com.zeticai.melangelm.MelangeLmProxy
import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ProxySavings
import com.zeticai.melangelm.model.ProxyResult

/**
 * Example: Enterprise customer support with cost tracking
 *
 * This shows how a large-scale customer support app can use
 * the proxy to cut costs dramatically. At 100K conversations/day,
 * a 44% token reduction saves:
 *
 *   GPT-4o:       $67,242/year
 *   Claude Opus:  $403,507/year
 *
 * The proxy also blocks prompt injection attacks (surprisingly common
 * in customer support — users try to extract system prompts, get
 * unauthorized refunds, or bypass policies).
 *
 * This example tracks cumulative savings across the entire session
 * and shows them in a dashboard.
 */
class SupportChatManager(context: Context, zeticKey: String, apiKey: String) {

    // Full pipeline: guard + anonymize + compress
    private val proxy = MelangeLmProxy.allFeatures(
        context = context,
        zeticKey = zeticKey,
        apiKey = apiKey,
        model = "gpt-4o-mini",         // cost-efficient for support
        compressionTarget = 0.5         // 50% compression target
    )

    // Session-level savings tracking
    private var totalTokensSaved = 0
    private var totalUsdSaved = 0.0
    private var totalRequestsServed = 0
    private var totalRequestsBlocked = 0

    suspend fun initialize() = proxy.initialize()

    /**
     * Handle a customer message in an ongoing support conversation.
     */
    suspend fun handleMessage(
        conversationHistory: List<ChatMessage>
    ): SupportResponse {
        val result = proxy.chat(messages = conversationHistory)

        return when (result) {
            is ProxyResult.Success -> {
                totalRequestsServed++

                result.savings?.let { s ->
                    totalTokensSaved += s.tokensSaved
                    totalUsdSaved += s.estimatedSavedUsd
                }

                SupportResponse(
                    reply = result.response.choices.first().message.content,
                    blocked = false,
                    savings = result.savings,
                    sessionStats = getSessionStats()
                )
            }

            is ProxyResult.Blocked -> {
                totalRequestsBlocked++
                // Log for security monitoring
                // In production, alert your security team about injection attempts

                SupportResponse(
                    reply = "I'm sorry, I can't process that request. " +
                            "Please rephrase your question about our products or services.",
                    blocked = true,
                    blockedBy = result.stage,
                    sessionStats = getSessionStats()
                )
            }

            is ProxyResult.Error -> {
                SupportResponse(
                    reply = "I'm experiencing a temporary issue. Please try again.",
                    error = result.message,
                    sessionStats = getSessionStats()
                )
            }
        }
    }

    fun getSessionStats() = SessionStats(
        totalTokensSaved = totalTokensSaved,
        totalUsdSaved = totalUsdSaved,
        totalRequestsServed = totalRequestsServed,
        totalRequestsBlocked = totalRequestsBlocked,
        // Project annual savings based on current session rate
        projectedAnnualSavings = if (totalRequestsServed > 0)
            (totalUsdSaved / totalRequestsServed) * 100_000 * 365  // at 100K req/day
        else 0.0
    )
}

data class SupportResponse(
    val reply: String,
    val blocked: Boolean = false,
    val blockedBy: String? = null,
    val error: String? = null,
    val savings: ProxySavings? = null,
    val sessionStats: SessionStats
)

data class SessionStats(
    val totalTokensSaved: Int,
    val totalUsdSaved: Double,
    val totalRequestsServed: Int,
    val totalRequestsBlocked: Int,
    val projectedAnnualSavings: Double
)

// --- Usage in a ViewModel ---
//
// class SupportViewModel(app: Application) : AndroidViewModel(app) {
//     private val manager = SupportChatManager(
//         context = app,
//         zeticKey = BuildConfig.ZETIC_KEY,
//         apiKey = BuildConfig.OPENAI_API_KEY
//     )
//
//     init { viewModelScope.launch { manager.initialize() } }
//
//     fun send(message: String) = viewModelScope.launch {
//         val history = buildConversationHistory(message)
//         val response = manager.handleMessage(history)
//
//         // Show reply to user
//         _messages.value += response.reply
//
//         // Update cost dashboard
//         _stats.value = response.sessionStats
//         // e.g. "Saved 4,231 tokens ($0.0034) this session"
//         // e.g. "Projected annual savings: $124,102 at 100K req/day"
//     }
// }
