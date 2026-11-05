import Foundation
import NetworkExtension

@main
enum FilterMain {
    static func main() {
        NEProvider.startSystemExtensionMode()
        dispatchMain()
    }
}
