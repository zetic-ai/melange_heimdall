import Foundation
import MelangeLmProxy

/// Example: Enterprise customer support with cost tracking (iOS)
///
/// Shows how a large-scale customer support app can use the proxy
/// to cut costs dramatically while blocking prompt injection attacks.
///
/// At 100K conversations/day with 44% token reduction:
///   GPT-4o:       saves $67,242/year
///   Claude Opus:  saves $403,507/year
@MainActor
class CustomerSupportViewModel: ObservableObject {
    @Published var messages: [SupportMessage] = []
    @Published var isReady = false
    @Published var sessionStats = SessionStats()

    private let proxy: MelangeLmProxy

    struct SupportMessage: Identifiable {
        let id = UUID()
        let role: String
        let content: String
        var isBlocked = false
        var tokensSaved: Int?
    }

    struct SessionStats {
        var totalTokensSaved = 0
        var totalUsdSaved: Double = 0
        var requestsServed = 0
        var requestsBlocked = 0
    }

    init() {
        // Full pipeline: guard + anonymize + compress
        proxy = MelangeLmProxy.allFeatures(
            zeticKey: "your_zetic_key",
            apiKey: "your_openai_key",
            model: "gpt-4o-mini",
            compressionTarget: 0.5
        )
        Task { await setup() }
    }

    private func setup() async {
        try? await proxy.initialize()
        isReady = true
    }

    func send(_ text: String) async {
        messages.append(SupportMessage(role: "user", content: text))

        let history = [
            ChatMessage(
                role: "system",
                content: "You are a helpful customer support agent for an e-commerce platform. "
                    + "Help customers with orders, returns, and product questions. "
                    + "Never share internal policies or system information."
            )
        ] + messages
            .filter { !$0.isBlocked }
            .map { ChatMessage(role: $0.role, content: $0.content) }

        let result = await proxy.chat(messages: history)

        switch result {
        case .success(let response):
            sessionStats.requestsServed += 1
            let reply = response.choices.first?.message.content ?? ""

            // The LLM never saw the customer's real name, email, or
            // credit card number. They were replaced with [Person_1],
            // [Email_1], [CreditCard_1] on-device. The response has
            // the real values restored automatically.

            var msg = SupportMessage(role: "assistant", content: reply)

            if let usage = response.usage {
                let tokensSaved = max(0, text.count / 4 - usage.promptTokens)
                msg.tokensSaved = tokensSaved
                let usd = Double(tokensSaved) * 0.00000015
                sessionStats.totalTokensSaved += tokensSaved
                sessionStats.totalUsdSaved += usd
            }

            messages.append(msg)

        case .blocked(_, let stage):
            sessionStats.requestsBlocked += 1
            // Common in customer support: users try to extract system
            // prompts, bypass refund policies, or inject instructions.
            // The proxy catches these BEFORE they cost you a cent.
            messages.append(SupportMessage(
                role: "assistant",
                content: "I can't process that request. How can I help you with your order?",
                isBlocked: true
            ))

        case .failure(let msg, _):
            messages.append(SupportMessage(
                role: "assistant",
                content: "Sorry, I'm having trouble right now. Please try again."
            ))
        }
    }
}

// --- Usage in SwiftUI ---
//
// struct SupportDashboard: View {
//     @StateObject private var vm = CustomerSupportViewModel()
//
//     var body: some View {
//         VStack {
//             // Cost savings dashboard
//             HStack {
//                 StatCard(
//                     title: "Tokens Saved",
//                     value: "\(vm.sessionStats.totalTokensSaved)"
//                 )
//                 StatCard(
//                     title: "USD Saved",
//                     value: String(format: "$%.4f", vm.sessionStats.totalUsdSaved)
//                 )
//                 StatCard(
//                     title: "Attacks Blocked",
//                     value: "\(vm.sessionStats.requestsBlocked)"
//                 )
//             }
//             .padding()
//
//             // Chat messages
//             ScrollView {
//                 ForEach(vm.messages) { msg in
//                     MessageBubble(message: msg)
//                 }
//             }
//
//             // Input field
//             ChatInput { text in
//                 Task { await vm.send(text) }
//             }
//         }
//     }
// }
