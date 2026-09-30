import Foundation
import SwiftData

@Model
final class UserProfile {
    @Attribute(.unique) var id: UUID
    var email: String
    var displayName: String?

    init(
        id: UUID = UUID(),
        email: String,
        displayName: String? = nil
    ) {
        self.id = id
        self.email = email
        self.displayName = displayName
    }
}
