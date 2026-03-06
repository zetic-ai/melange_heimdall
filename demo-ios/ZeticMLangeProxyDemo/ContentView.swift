//
//  ContentView.swift
//  MelangeLmProxy Demo
//

import SwiftUI

private let zeticTeal = Color(red: 52/255, green: 169/255, blue: 163/255)
private let dangerRed = Color(red: 0.85, green: 0.25, blue: 0.25)

struct ContentView: View {
    @StateObject private var vm = DemoViewModel()
    @State private var inputText = ""
    @State private var showExamples = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Navigation bar
            headerBar

            // Message list
            ScrollViewReader { proxy in
                ScrollView {
                    if !vm.isReady {
                        modelLoadingView
                    } else if vm.messages.isEmpty {
                        examplePromptsPanel
                    } else {
                        LazyVStack(spacing: 12) {
                            ForEach(vm.messages) { msg in
                                MessageBubble(message: msg)
                                    .id(msg.id)
                            }
                            if vm.isLoading {
                                TypingIndicator()
                                    .id("typing_indicator")
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 16)
                    }
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: vm.messages.count) { _ in
                    if let lastId = vm.messages.last?.id {
                        withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                    }
                }
                .onChange(of: vm.isLoading) { _ in
                    withAnimation { proxy.scrollTo("typing_indicator", anchor: .bottom) }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 0) {
                Divider()
                savingsBar
                Divider()
                inputBar
            }
        }
        .onTapGesture { focused = false }
        .sheet(isPresented: $vm.showSettings) {
            SettingsSheet(vm: vm)
        }
        .sheet(isPresented: $showExamples) {
            ExamplesSheet(vm: vm, isPresented: $showExamples)
        }
    }

    // MARK: - Header

    private var headerBar: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Image(systemName: "shield.checkered")
                        .foregroundStyle(zeticTeal)
                    Text("Melange LM Proxy")
                        .font(.headline)
                }
                Text(vm.initStatus)
                    .font(.caption)
                    .foregroundStyle(vm.isReady ? zeticTeal : .secondary)
            }
            Spacer()

            // Settings button
            Button { vm.showSettings = true } label: {
                Image(systemName: vm.hasApiKey ? "key.fill" : "key")
                    .foregroundStyle(vm.hasApiKey ? zeticTeal : .secondary)
            }

            if !vm.messages.isEmpty && vm.isReady {
                Button { showExamples = true } label: {
                    Image(systemName: "list.bullet.rectangle")
                        .foregroundStyle(zeticTeal)
                }
            }

            if !vm.messages.isEmpty {
                Button { vm.clearHistory() } label: {
                    Image(systemName: "trash")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.regularMaterial)
    }

    // MARK: - Savings bar

    private var savingsBar: some View {
        VStack(spacing: 6) {
            // Mode indicator
            HStack(spacing: 6) {
                Image(systemName: vm.hasApiKey ? "arrow.up.arrow.down.circle.fill" : "cpu")
                    .foregroundStyle(vm.hasApiKey ? zeticTeal : .orange)
                    .font(.caption)
                Text(vm.hasApiKey ? "Full pipeline + upstream LLM" : "Pipeline-only (no API key)")
                    .font(.caption)
                    .foregroundStyle(vm.hasApiKey ? zeticTeal : .orange)
                Spacer()
                if !vm.hasApiKey {
                    Button {
                        vm.showSettings = true
                    } label: {
                        Text("Add API key")
                            .font(.caption)
                            .foregroundStyle(zeticTeal)
                    }
                }
            }

            // Compression slider
            HStack(spacing: 10) {
                Text("Compress")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(width: 60, alignment: .leading)
                Slider(value: Binding(
                    get: { vm.compressionTargetRatio },
                    set: { vm.setCompressionRatio($0) }
                ), in: 0.2...0.9, step: 0.1)
                .tint(zeticTeal)
                Text("\(Int(vm.compressionTargetRatio * 100))%")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(zeticTeal)
                    .fontWeight(.semibold)
                    .frame(width: 36, alignment: .trailing)
            }

            // Session savings summary
            if vm.savings.totalTokensSaved > 0 {
                HStack(spacing: 20) {
                    savingsStat(label: "Tokens saved", value: "\(vm.savings.totalTokensSaved)")
                    savingsStat(label: "Est. savings", value: "$\(String(format: "%.4f", vm.savings.totalUsdSaved))")
                    if !vm.savings.latestCompressionLabel.isEmpty {
                        savingsStat(label: "Last request", value: vm.savings.latestCompressionLabel)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity)
                .background(zeticTeal.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.regularMaterial)
    }

    private func savingsStat(label: String, value: String) -> some View {
        VStack(spacing: 1) {
            Text(value)
                .font(.caption.monospacedDigit().weight(.bold))
                .foregroundStyle(zeticTeal)
            Text(label)
                .font(.system(size: 9))
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Model loading view

    private var modelLoadingView: some View {
        VStack(spacing: 20) {
            Spacer().frame(height: 40)

            Image(systemName: "shield.checkered")
                .font(.system(size: 48))
                .foregroundStyle(zeticTeal.opacity(0.6))

            if vm.isFirstLaunch {
                Text("First launch setup")
                    .font(.title3.bold())
                Text("Downloading 3 on-device AI models.\nThis only happens once — future launches are instant.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 20)
            } else {
                Text("Loading on-device models...")
                    .font(.title3.bold())
            }

            VStack(spacing: 12) {
                ForEach(vm.loadingSteps) { step in
                    VStack(spacing: 0) {
                        HStack(spacing: 12) {
                            Group {
                                switch step.status {
                                case .pending:
                                    Image(systemName: "circle")
                                        .foregroundStyle(.secondary)
                                case .downloading, .loading:
                                    ProgressView()
                                        .scaleEffect(0.8)
                                case .ready:
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(zeticTeal)
                                case .failed:
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(dangerRed)
                                }
                            }
                            .frame(width: 20)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(step.name)
                                    .font(.subheadline.weight(.medium))
                                Text(step.description)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }

                            Spacer()

                            if step.status == .downloading && step.downloadProgress > 0 && step.downloadProgress < 1.0 {
                                Text("\(Int(step.downloadProgress * 100))%")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(zeticTeal)
                                    .fontWeight(.semibold)
                            } else {
                                Text(step.status.label)
                                    .font(.caption)
                                    .foregroundStyle(step.status == .ready ? zeticTeal : .secondary)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)

                        if step.status == .downloading && step.downloadProgress > 0 && step.downloadProgress < 1.0 {
                            ProgressView(value: Double(step.downloadProgress))
                                .tint(zeticTeal)
                                .padding(.horizontal, 16)
                                .padding(.bottom, 8)
                        }
                    }
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(step.status == .ready
                                  ? zeticTeal.opacity(0.06)
                                  : Color(.systemGray6))
                    )
                }
            }
            .padding(.horizontal, 24)

            if vm.isFirstLaunch {
                Text("Models are cached locally — next launch loads in seconds")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
            }

            Spacer()
        }
    }

    // MARK: - Example prompts panel

    private var examplePromptsPanel: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "shield.checkered")
                    .font(.system(size: 40))
                    .foregroundStyle(zeticTeal.opacity(0.4))
                Text("Try the proxy pipeline")
                    .font(.headline)

                if vm.isLocalDemoMode {
                    VStack(spacing: 4) {
                        Text("Pipeline-only mode")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.orange)
                        Text("See how on-device stages process your messages.\nAdd an API key in settings to get LLM responses.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                }

                Text("Tap an example to see what happens:")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                exampleSection(
                    title: "Prompt Injection (blocked on-device)",
                    color: dangerRed,
                    examples: examplePrompts.filter { $0.category == .injection }
                )

                exampleSection(
                    title: "PII Redaction (names, SSN, email hidden)",
                    color: zeticTeal,
                    examples: examplePrompts.filter { $0.category == .pii }
                )

                exampleSection(
                    title: "Token Compression (~45% saved)",
                    color: Color(red: 0.42, green: 0.48, blue: 0.91),
                    examples: examplePrompts.filter { $0.category == .longPrompt }
                )
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
    }

    private func exampleSection(title: String, color: Color, examples: [ExamplePrompt]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundStyle(color)

            ForEach(examples) { example in
                Button {
                    vm.sendExample(example)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(example.label)
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(color)
                        Text(example.description)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(String(example.prompt.prefix(80)) + (example.prompt.count > 80 ? "..." : ""))
                            .font(.caption)
                            .foregroundStyle(.secondary.opacity(0.7))
                            .lineLimit(2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Input bar

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Type a message...", text: $inputText, axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 22))
                .focused($focused)
                .disabled(!vm.isReady)

            VStack(spacing: 4) {
                Button {
                    focused = false
                } label: {
                    Image(systemName: "keyboard.chevron.compact.down")
                        .font(.system(size: 16))
                        .foregroundStyle(.gray)
                        .frame(width: 34, height: 28)
                        .background(Color(.systemGray5), in: RoundedRectangle(cornerRadius: 8))
                }

                Button {
                    guard !inputText.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                    vm.send(inputText)
                    inputText = ""
                    focused = false
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(
                            (vm.isReady && !inputText.isEmpty) ? zeticTeal : Color(.systemGray4)
                        )
                }
                .disabled(!vm.isReady || inputText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(.regularMaterial)
    }
}

// MARK: - Settings Sheet

struct SettingsSheet: View {
    @ObservedObject var vm: DemoViewModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(
                            vm.hasApiKey ? "Full pipeline mode" : "Pipeline-only mode",
                            systemImage: vm.hasApiKey ? "checkmark.circle.fill" : "info.circle"
                        )
                        .foregroundStyle(vm.hasApiKey ? zeticTeal : .orange)
                        .font(.subheadline.weight(.semibold))

                        Text(vm.hasApiKey
                            ? "Messages are processed on-device, then sent to the upstream LLM."
                            : "Messages are processed on-device only. Add an API key below to enable upstream LLM calls."
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }

                Section("Upstream LLM") {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("API Key")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        SecureField("sk-...", text: $vm.openAIApiKey)
                            .textContentType(.password)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Base URL")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField("https://api.openai.com", text: $vm.openAIBaseURL)
                            .keyboardType(.URL)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text("Model")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField("gpt-4o-mini", text: $vm.openAIModel)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                }

                Section {
                    Text("The API key is stored locally on this device and is only used to call the configured upstream LLM endpoint.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Text("Works with any OpenAI-compatible API:\nOpenAI, Anthropic (via proxy), Groq, Together, Ollama, etc.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                        .foregroundStyle(zeticTeal)
                }
            }
        }
    }
}

// MARK: - Message Bubble

struct MessageBubble: View {
    let message: DemoMessage
    private let zeticTeal = Color(red: 52/255, green: 169/255, blue: 163/255)
    private let dangerRed = Color(red: 0.85, green: 0.25, blue: 0.25)

    var body: some View {
        VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 4) {
            HStack {
                if message.role == "user" { Spacer(minLength: 60) }
                bubbleContent
                if message.role != "user" { Spacer(minLength: 60) }
            }

            // Processed content (shows what pipeline did)
            if let processed = message.processedContent {
                Text(processed)
                    .font(.system(size: 12))
                    .foregroundStyle(.primary.opacity(0.8))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(zeticTeal.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
            }

            if !message.pipelineLog.isEmpty && message.role == "assistant" {
                pipelineLogView
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    private var bubbleContent: some View {
        Group {
            if message.isBlocked {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.shield.fill")
                        .foregroundStyle(dangerRed)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(message.content)
                            .foregroundStyle(dangerRed)
                            .font(.subheadline.weight(.semibold))
                        message.blockedBy.map {
                            Text("Blocked by: \($0)")
                                .font(.caption)
                                .foregroundStyle(dangerRed.opacity(0.7))
                        }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(dangerRed.opacity(0.1), in: RoundedRectangle(cornerRadius: 16))
            } else {
                Text(message.content)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(
                        message.role == "user" ? zeticTeal : Color(.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 16)
                    )
                    .foregroundStyle(message.role == "user" ? .white : .primary)
            }
        }
    }

    private var pipelineLogView: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(message.pipelineLog, id: \.self) { line in
                Text(line)
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color(.systemGray6), in: RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Typing Indicator

struct TypingIndicator: View {
    @State private var phase = 0.0
    private let zeticTeal = Color(red: 52/255, green: 169/255, blue: 163/255)

    var body: some View {
        HStack {
            HStack(spacing: 4) {
                ForEach(0..<3, id: \.self) { i in
                    Circle()
                        .fill(zeticTeal.opacity(0.5))
                        .frame(width: 8, height: 8)
                        .scaleEffect(phase == Double(i) ? 1.3 : 1.0)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
            Spacer()
        }
        .onAppear {
            Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { _ in
                withAnimation(.spring(duration: 0.3)) {
                    phase = (phase + 1).truncatingRemainder(dividingBy: 3)
                }
            }
        }
    }
}

// MARK: - Examples Sheet

struct ExamplesSheet: View {
    @ObservedObject var vm: DemoViewModel
    @Binding var isPresented: Bool
    private let zeticTeal = Color(red: 52/255, green: 169/255, blue: 163/255)
    private let dangerRed = Color(red: 0.85, green: 0.25, blue: 0.25)

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 16) {
                    exampleSection(
                        title: "Prompt Injection (blocked on-device)",
                        color: dangerRed,
                        examples: examplePrompts.filter { $0.category == .injection }
                    )
                    exampleSection(
                        title: "PII Redaction (names, SSN, email hidden)",
                        color: zeticTeal,
                        examples: examplePrompts.filter { $0.category == .pii }
                    )
                    exampleSection(
                        title: "Token Compression (~45% saved)",
                        color: Color(red: 0.42, green: 0.48, blue: 0.91),
                        examples: examplePrompts.filter { $0.category == .longPrompt }
                    )
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .navigationTitle("Examples")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { isPresented = false }
                        .foregroundStyle(zeticTeal)
                }
            }
        }
    }

    private func exampleSection(title: String, color: Color, examples: [ExamplePrompt]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundStyle(color)

            ForEach(examples) { example in
                Button {
                    vm.sendExample(example)
                    isPresented = false
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(example.label)
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(color)
                        Text(example.description)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(String(example.prompt.prefix(80)) + (example.prompt.count > 80 ? "..." : ""))
                            .font(.caption)
                            .foregroundStyle(.secondary.opacity(0.7))
                            .lineLimit(2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

#Preview {
    ContentView()
}
