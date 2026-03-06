//
//  OpenAIUpstreamClient.swift
//  MelangeLmProxy
//
//  OpenAI-compatible upstream client using URLSession.
//  Works with OpenAI, Azure OpenAI, Anthropic (via proxy), and any /v1/chat/completions endpoint.
//

import Foundation

public final class OpenAIUpstreamClient: UpstreamClient {
    private let baseURL: URL
    private let apiKey: String
    private let defaultModel: String?
    private let session: URLSession

    public init(
        baseURL: String,
        apiKey: String,
        defaultModel: String? = nil,
        timeoutSeconds: Double = 60
    ) {
        guard let url = URL(string: baseURL) else {
            fatalError("MelangeLmProxy: invalid upstream baseURL: \(baseURL)")
        }
        self.baseURL = url
        self.apiKey = apiKey
        self.defaultModel = defaultModel
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = timeoutSeconds
        config.timeoutIntervalForResource = timeoutSeconds
        self.session = URLSession(configuration: config)
    }

    public func send(_ chatRequest: ChatRequest) async throws -> ChatResponse {
        let endpoint = baseURL
            .appendingPathComponent("v1")
            .appendingPathComponent("chat")
            .appendingPathComponent("completions")

        var urlRequest = URLRequest(url: endpoint)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = try buildBody(chatRequest)

        let (data, response) = try await session.data(for: urlRequest)

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            let body = String(data: data, encoding: .utf8) ?? "(no body)"
            throw UpstreamError.httpError(statusCode: http.statusCode, body: body)
        }

        return try parseResponse(data)
    }

    // MARK: - Encoding

    private func buildBody(_ request: ChatRequest) throws -> Data {
        var json: [String: Any] = [
            "model": defaultModel ?? request.model,
            "messages": request.messages.map { ["role": $0.role, "content": $0.content] }
        ]
        if let temperature = request.temperature { json["temperature"] = temperature }
        if let maxTokens = request.maxTokens { json["max_tokens"] = maxTokens }
        if request.stream { json["stream"] = true }
        return try JSONSerialization.data(withJSONObject: json)
    }

    // MARK: - Decoding

    private func parseResponse(_ data: Data) throws -> ChatResponse {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw UpstreamError.malformedResponse("Could not parse root object")
        }
        guard let choicesArr = root["choices"] as? [[String: Any]] else {
            throw UpstreamError.malformedResponse("Missing 'choices'")
        }

        let choices: [ChatChoice] = try choicesArr.enumerated().map { (i, c) in
            guard let msgDict = c["message"] as? [String: Any],
                  let role = msgDict["role"] as? String,
                  let content = msgDict["content"] as? String else {
                throw UpstreamError.malformedResponse("Malformed choice at index \(i)")
            }
            return ChatChoice(
                index: c["index"] as? Int ?? i,
                message: ChatMessage(role: role, content: content),
                finishReason: c["finish_reason"] as? String
            )
        }

        var usage: TokenUsage?
        if let u = root["usage"] as? [String: Any] {
            usage = TokenUsage(
                promptTokens: u["prompt_tokens"] as? Int ?? 0,
                completionTokens: u["completion_tokens"] as? Int ?? 0,
                totalTokens: u["total_tokens"] as? Int ?? 0
            )
        }

        return ChatResponse(
            id: root["id"] as? String ?? "",
            model: root["model"] as? String ?? "",
            choices: choices,
            usage: usage
        )
    }
}

public enum UpstreamError: Error, LocalizedError {
    case httpError(statusCode: Int, body: String)
    case malformedResponse(String)

    public var errorDescription: String? {
        switch self {
        case .httpError(let code, let body): return "HTTP \(code): \(body)"
        case .malformedResponse(let detail): return "Malformed response: \(detail)"
        }
    }
}
