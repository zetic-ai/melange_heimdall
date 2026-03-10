# Melange Heimdall - iOS Demo App

A SwiftUI demo app that shows the proxy pipeline in action: send a message, watch PromptGuard classify it, PII get redacted, the prompt get summarized, and see how many tokens (and dollars) you saved.

Includes **built-in example prompts** you can tap to try each pipeline stage - no OpenAI API key required.

## Quick start

1. **Run the setup script** from the repo root:
   ```bash
   ./setup.sh
   ```
   This prompts for your Melange Personal Key and patches it into the source code.

2. **Open the Xcode project**:
   ```
   open demo-ios/ZeticMLangeProxyDemo.xcodeproj
   ```

3. **Build and run** on a physical iOS device (iOS 16+).

4. **Tap an example prompt** to see the pipeline in action:
   - **Prompt Injection** - watch PromptGuard block it on-device
   - **Healthcare / Financial** - see PII (names, SSNs, emails) get redacted
   - **Code Review** - see the summarizer compress tokens by ~45%

## Manual setup (alternative)

If you prefer not to use the setup script, set `ZETIC_PERSONAL_KEY` as an environment variable in your Xcode scheme:

Product > Scheme > Edit Scheme > Run > Arguments > Environment Variables:

| Variable | Value |
|---|---|
| `ZETIC_PERSONAL_KEY` | `dev_your_key_here` |

OpenAI settings (`OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL`) can also be set here, or configured in the app's Settings screen at runtime.

## Regenerating the Xcode project

The project is generated from `project.yml` using [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```
cd demo-ios
xcodegen generate
```
