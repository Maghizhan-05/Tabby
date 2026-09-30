import Foundation
import SwiftData

@Model
final class Category {
    @Attribute(.unique) var id: UUID
    var name: String
    var isDefault: Bool
    var sortOrder: Int

    init(
        id: UUID = UUID(),
        name: String,
        isDefault: Bool = false,
        sortOrder: Int = 0
    ) {
        self.id = id
        self.name = name
        self.isDefault = isDefault
        self.sortOrder = sortOrder
    }
}
