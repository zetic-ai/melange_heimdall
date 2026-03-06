import Foundation
import MelangeLmProxy

/// Example: HIPAA-compliant healthcare chatbot (iOS)
///
/// Patients type messages containing their real names, SSNs, emails,
/// and medical details. The proxy ensures NONE of this PII reaches
/// the LLM provider — it's redacted on-device before the API call,
/// and restored in the response so the user sees a personalized reply.
///
/// What the patient types:
///   "Hi, I'm Sarah Chen (SSN 123-45-6789). I've been having chest
///    pains since Tuesday. My cardiologist Dr. James Wilson at Mount
///    Sinai (james.wilson@mountsinai.org) told me to track symptoms."
///
/// What OpenAI/Google/Anthropic sees:
///   "[Person_1] (SSN [SSN_1]) has chest pains since [Date_1].
///    Cardiologist [Person_2] at [Location_1] ([Email_1]) said
///    track symptoms."
///
/// What the patient sees in the response:
///   "Sarah Chen, please go to the ER immediately if you experience
///    severe chest pain. Contact Dr. James Wilson at
///    james.wilson@mountsinai.org for follow-up."
///
/// Zero PII exposure. HIPAA compliance built-in. 44% fewer tokens.
@MainActor
class HealthcareChatViewModel: ObservableObject {
    @Published var messages: [Message] = []
    @Published var isReady = false

    private let proxy: MelangeLmProxy

    init() {
        // One line to set up everything:
        // prompt guard + PII redaction + compression
        proxy = MelangeLmProxy.allFeatures(
            zeticKey: "your_zetic_personal_key",
            apiKey: "your_openai_api_key",
            model: "gpt-4o"
        )

        Task { await initialize() }
    }

    private func initialize() async {
        try? await proxy.initialize()
        isReady = true
    }

    func sendMessage(_ text: String) async {
        messages.append(Message(role: "user", content: text))

        let history = [
            ChatMessage(
                role: "system",
                content: "You are a medical triage assistant. Help patients "
                    + "understand when to seek emergency care vs. urgent care. "
                    + "Always recommend consulting their doctor for serious symptoms."
            )
        ] + messages.map { ChatMessage(role: $0.role, content: $0.content) }

        let result = await proxy.chat(messages: history)

        switch result {
        case .success(let response):
            let reply = response.choices.first?.message.content ?? ""
            // reply contains: "Sarah Chen, please go to the ER immediately if..."
            // The LLM never saw "Sarah Chen" — only "[Person_1]"
            // But the user sees the fully personalized response.
            messages.append(Message(role: "assistant", content: reply))

        case .blocked(_, let stage):
            // If a patient accidentally pastes a prompt injection
            messages.append(Message(
                role: "assistant",
                content: "This message was flagged by \(stage). Please rephrase."
            ))

        case .failure(let msg, _):
            messages.append(Message(role: "assistant", content: "Error: \(msg)"))
        }
    }
}

struct Message: Identifiable {
    let id = UUID()
    let role: String
    let content: String
}

// --- Usage in SwiftUI ---
//
// struct HealthcareChatView: View {
//     @StateObject private var vm = HealthcareChatViewModel()
//     @State private var input = ""
//
//     var body: some View {
//         VStack {
//             ScrollView {
//                 ForEach(vm.messages) { msg in
//                     HStack {
//                         if msg.role == "user" { Spacer() }
//                         Text(msg.content)
//                             .padding()
//                             .background(msg.role == "user" ? .blue : .gray)
//                             .cornerRadius(12)
//                         if msg.role == "assistant" { Spacer() }
//                     }
//                 }
//             }
//
//             HStack {
//                 TextField("Describe your symptoms...", text: $input)
//                 Button("Send") {
//                     let text = input
//                     input = ""
//                     Task { await vm.sendMessage(text) }
//                 }
//                 .disabled(!vm.isReady)
//             }
//             .padding()
//         }
//     }
// }
