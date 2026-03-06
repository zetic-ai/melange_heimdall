// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MelangeLmProxy",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "MelangeLmProxy", targets: ["MelangeLmProxy"])
    ],
    dependencies: [
        .package(url: "https://github.com/zetic-ai/ZeticMLangeiOS.git", from: "1.5.0")
    ],
    targets: [
        .target(
            name: "MelangeLmProxy",
            dependencies: [
                .product(name: "ZeticMLange", package: "ZeticMLangeiOS")
            ]
        )
    ]
)
