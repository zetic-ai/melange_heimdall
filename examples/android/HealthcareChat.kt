package com.example.healthcare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zeticai.melangelm.MelangeLmProxy
import com.zeticai.melangelm.model.ChatMessage
import com.zeticai.melangelm.model.ProxyResult
import kotlinx.coroutines.launch

/**
 * Example: HIPAA-compliant healthcare chatbot
 *
 * Patients type messages containing their real names, SSNs, emails,
 * and medical details. The proxy ensures NONE of this PII reaches
 * the LLM provider — it's redacted on-device before the API call,
 * and restored in the response so the user sees a personalized reply.
 *
 * What the patient types:
 *   "Hi, I'm Sarah Chen (SSN 123-45-6789). I've been having chest
 *    pains since Tuesday. My cardiologist Dr. James Wilson at Mount
 *    Sinai (james.wilson@mountsinai.org) told me to track symptoms."
 *
 * What OpenAI/Google/Anthropic sees:
 *   "[Person_1] (SSN [SSN_1]) has chest pains since [Date_1].
 *    Cardiologist [Person_2] at [Location_1] ([Email_1]) said
 *    track symptoms."
 *
 * What the patient sees in the response:
 *   "Sarah Chen, please go to the ER immediately if you experience
 *    severe chest pain. Contact Dr. James Wilson at
 *    james.wilson@mountsinai.org for follow-up."
 *
 * Zero PII exposure. HIPAA compliance built-in. 44% fewer tokens.
 */
class HealthcareChatActivity : ComponentActivity() {

    private lateinit var proxy: MelangeLmProxy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // One line to set up everything: prompt guard + PII redaction + compression
        proxy = MelangeLmProxy.allFeatures(
            context = applicationContext,
            zeticKey = "your_zetic_personal_key",
            apiKey = "your_openai_api_key",
            model = "gpt-4o"
        )

        // Load on-device models (do this once at app startup)
        lifecycleScope.launch {
            proxy.initialize()
            startChat()
        }
    }

    private suspend fun startChat() {
        // Patient sends a message with sensitive medical & personal data
        val patientMessage = """
            Hi, I'm Sarah Chen (SSN 123-45-6789). I've been having chest
            pains since Tuesday. My cardiologist Dr. James Wilson at Mount
            Sinai (james.wilson@mountsinai.org) told me to track symptoms.
            Can you help me understand when I should go to the ER vs
            urgent care? My insurance is BlueCross policy #BC-445-9921.
        """.trimIndent()

        val result = proxy.chat(
            messages = listOf(
                ChatMessage(
                    role = "system",
                    content = "You are a medical triage assistant. Help patients understand " +
                            "when to seek emergency care vs. urgent care. Always recommend " +
                            "consulting their doctor for serious symptoms."
                ),
                ChatMessage(role = "user", content = patientMessage)
            )
        )

        when (result) {
            is ProxyResult.Success -> {
                val reply = result.response.choices.first().message.content
                // reply contains: "Sarah Chen, please go to the ER immediately if..."
                // The LLM never saw "Sarah Chen" — only "[Person_1]"
                // But the user sees the fully personalized response.

                println("--- Patient sees ---")
                println(reply)

                // Track savings
                result.savings?.let { s ->
                    println("\n--- Proxy savings ---")
                    println("Compression: ${s.compressionLabel}")
                    println("Tokens saved: ${s.tokensSaved}")
                    println("Cost saved: $${String.format("%.5f", s.estimatedSavedUsd)}")
                }
            }

            is ProxyResult.Blocked -> {
                // If a patient accidentally pastes a prompt injection
                // (e.g. from a malicious website), it gets caught here
                println("Message flagged: ${result.reason}")
            }

            is ProxyResult.Error -> {
                println("Error: ${result.message}")
            }
        }
    }
}
