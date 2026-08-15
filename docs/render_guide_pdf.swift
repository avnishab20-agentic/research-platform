import Cocoa
import CoreText

let arguments = CommandLine.arguments
guard arguments.count == 3 else {
    fputs("Usage: render_guide_pdf.swift input.html output.pdf\n", stderr)
    exit(2)
}

let inputURL = URL(fileURLWithPath: arguments[1])
let outputURL = URL(fileURLWithPath: arguments[2])
let htmlData = try Data(contentsOf: inputURL)

let document = try NSAttributedString(
    data: htmlData,
    options: [
        .documentType: NSAttributedString.DocumentType.html,
        .characterEncoding: String.Encoding.utf8.rawValue
    ],
    documentAttributes: nil
)

let pageWidth: CGFloat = 595
let pageHeight: CGFloat = 842
let margin: CGFloat = 48
var mediaBox = CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight)

guard let context = CGContext(outputURL as CFURL, mediaBox: &mediaBox, nil) else {
    fputs("Unable to create PDF context\n", stderr)
    exit(1)
}

let framesetter = CTFramesetterCreateWithAttributedString(document)
let framePath = CGPath(rect: CGRect(x: margin, y: margin, width: pageWidth - 2 * margin, height: pageHeight - 2 * margin), transform: nil)
var location = 0

while location < document.length {
    context.beginPDFPage(nil)
    context.textMatrix = .identity
    let range = CFRange(location: location, length: 0)
    let frame = CTFramesetterCreateFrame(framesetter, range, framePath, nil)
    CTFrameDraw(frame, context)
    let visibleRange = CTFrameGetVisibleStringRange(frame)
    guard visibleRange.length > 0 else { break }
    location += visibleRange.length
    context.endPDFPage()
}

context.closePDF()
