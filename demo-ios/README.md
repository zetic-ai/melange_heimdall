# Melange Heimdall — iOS Demo App

A SwiftUI demo app that shows the proxy pipeline in action: send a message, watch PromptGuard classify it, PII get redacted, the prompt get summarized, and see how many tokens (and dollars) you saved.

Includes **built-in example prompts** you can tap to try each pipeline stage — no OpenAI API key required.

## Prerequisites

You need a **Zetic Melange Personal Key** to download the on-device models:

1. Sign up at [**zetic.ai**](https://zetic.ai) (free)
2. Go to **Settings → Access Keys** and generate a new key
3. Copy your **Personal Access Key** (starts with `dev_`)

## Quick start

1. **Open the Xcode project**:
   ```
   open demo-ios/ZeticMLangeProxyDemo.xcodeproj
   ```

2. **Set your Zetic key** in the Xcode scheme:

   Product → Scheme → Edit Scheme → Run → Arguments → Environment Variables:

   | Variable | Value | Required |
   |---|---|---|
   | `ZETIC_PERSONAL_KEY` | `dev_your_key_here` | **Yes** |
   | `OPENAI_API_KEY` | `sk-...` | No (pipeline-only mode without it) |
   | `OPENAI_BASE_URL` | `https://api.openai.com` | No |
   | `OPENAI_MODEL` | `gpt-4o-mini` | No |

3. **Build and run** on a physical iOS device (iOS 16+).

4. **Tap an example prompt** to see the pipeline in action:
   - **Prompt Injection** — watch PromptGuard block it on-device
   - **Healthcare / Financial** — see PII (names, SSNs, emails) get redacted
   - **Code Review** — see the summarizer compress tokens by ~45%

## Regenerating the Xcode project

The project is generated from `project.yml` using [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```
cd demo-ios
xcodegen generate
```
