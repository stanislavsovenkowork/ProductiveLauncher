import Foundation

enum ServiceCommandFailure: Error { case processFailed(Int32) }

enum ServiceCommand {
    static func run(_ path: String, _ arguments: [String], input: Data? = nil) throws -> String {
        try autoreleasepool {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: path); process.arguments = arguments
            process.environment = ["PATH":"/usr/bin:/bin:/usr/sbin:/sbin", "LANG":"C"]
            let output = Pipe()
            // Pipe/FileHandle destruction is not sufficient in a long-lived
            // Foundation daemon. Release BOTH parent descriptors explicitly,
            // including failed launches and nonzero child exits.
            defer {
                try? output.fileHandleForReading.close()
                try? output.fileHandleForWriting.close()
            }
            process.standardOutput = output; process.standardError = output
            let source = input.map { _ in Pipe() }
            defer {
                try? source?.fileHandleForReading.close()
                try? source?.fileHandleForWriting.close()
            }
            process.standardInput = source.map { $0 as Any } ?? FileHandle.nullDevice
            try process.run()
            try? output.fileHandleForWriting.close()
            try? source?.fileHandleForReading.close()
            if let input, let source {
                do {
                    try source.fileHandleForWriting.write(contentsOf: input)
                    try source.fileHandleForWriting.close()
                } catch {
                    if process.isRunning { process.terminate() }
                    process.waitUntilExit()
                    throw error
                }
            }
            let data = try output.fileHandleForReading.readToEnd() ?? Data()
            process.waitUntilExit()
            guard process.terminationStatus == 0 else { throw ServiceCommandFailure.processFailed(process.terminationStatus) }
            return String(decoding: data, as: UTF8.self)
        }
    }
}
