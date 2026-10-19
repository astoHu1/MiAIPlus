// macOS Vision companion for device_test.py. Never print recognized screen contents.
import AppKit
import Foundation
import Vision

guard CommandLine.arguments.count == 3,
      let image = NSImage(contentsOfFile: CommandLine.arguments[1]),
      let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
    fputs("invalid owned screenshot arguments\n", stderr)
    exit(2)
}
let expected = CommandLine.arguments[2]
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["en-US"]
request.usesLanguageCorrection = false
do {
    try VNImageRequestHandler(cgImage: cgImage).perform([request])
    let lines = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
    let normalized = lines.joined().filter { !$0.isWhitespace }
    // The request contains the nonce but not this reply prefix, so it cannot satisfy this check.
    exit(normalized.contains(expected) ? 0 : 1)
} catch {
    fputs("owned screenshot recognition failed\n", stderr)
    exit(2)
}
