package parsing

open class Declaration

class ChannelDeclaration(
    val type: Type,
    val identifier: String
) : Declaration()

class VariableDeclaration(
    val type: Type,
    val identifier: String,
    val initializer: String?
) : Declaration()

class PartialInstantiation(
    val lhs: String
) : Declaration()

class TypedefDeclaration : Declaration() // TODO: Determine if we care about this