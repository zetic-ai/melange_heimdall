# Examples

Copy-paste-ready integration examples for common use cases.

## Android (Kotlin)

| Example | What it shows |
|---|---|
| [HealthcareChat.kt](android/HealthcareChat.kt) | HIPAA-compliant chatbot — PII (names, SSNs, emails) automatically redacted on-device, restored in response |
| [FinancialAdvisor.kt](android/FinancialAdvisor.kt) | Financial advisor with account number protection — multi-turn conversation with PII never leaving device |
| [CustomerSupportWithCostTracking.kt](android/CustomerSupportWithCostTracking.kt) | Enterprise support with session-level savings tracking and injection attack blocking |
| [CustomPipelineStage.kt](android/CustomPipelineStage.kt) | Build your own pipeline stages: language gate, rate limiter, response filter |
| [WrapExistingRetrofitClient.kt](android/WrapExistingRetrofitClient.kt) | Migrate an existing Retrofit/OkHttp app to use Melange Proxy — step by step |

## iOS (Swift)

| Example | What it shows |
|---|---|
| [HealthcareChat.swift](ios/HealthcareChat.swift) | HIPAA-compliant chatbot with SwiftUI integration |
| [CustomerSupport.swift](ios/CustomerSupport.swift) | Enterprise support with cost dashboard and attack blocking |

## Quick comparison

### Without Melange Proxy

```kotlin
// Your app sends raw PII to the LLM provider
val body = """{"messages": [{"role": "user", "content": "I'm Sarah Chen, SSN 123-45-6789..."}]}"""
val response = okhttp.newCall(request).execute()
// OpenAI/Google/Anthropic now has Sarah's real name and SSN
```

### With Melange Proxy

```kotlin
// One line of setup
val proxy = MelangeLmProxy.allFeatures(context, zeticKey, apiKey = apiKey)

// The LLM only sees "[Person_1], SSN [SSN_1]..."
// Sarah's real data NEVER leaves the device
val result = proxy.chat(messages = messages)
```
