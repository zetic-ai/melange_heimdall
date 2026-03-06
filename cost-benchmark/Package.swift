// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CostBenchmark",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "CostBenchmark",
            path: "Sources/CostBenchmark"
        )
    ]
)
